package com.esdllm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.model.Admin;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * B 站凭据异常时的<b>主动私聊提醒</b>：把「机器人悄悄不推了」变成一条会自己找上门的消息。
 *
 * <h3>它治什么</h3>
 * 凭据失效 / 待更换本来只在两个地方可见：日志里一行 WARN，以及用户主动发「登录状态」。
 * 可用户的第一反应几乎总是"网络挂了 / B 站抽风 / 订阅没了"，<b>不会想到是 Cookie 过期</b>；
 * 等他自己想起来去查，可能已经漏推了好几天。本类在探测得出结论的那一刻，直接把话送到所有者私聊。
 *
 * <h3>推哪两种情况（都推）</h3>
 * <ul>
 *   <li>{@link Kind#EXPIRED}：服务端明确不认这枚凭据 —— 此刻动态推送已经在静默失败；</li>
 *   <li>{@link Kind#EXPIRING}：<b>还能用</b>，但服务端答"该刷新了"。本库不实现刷新
 *       （web 端换 Cookie 是 iframe + WASM 流程），所以含义就是"该重新登录"。
 *       <b>这才是最值得提前提醒的时机</b> —— 此时登录还是好的，重扫一次成本最低，
 *       而不是等它彻底失效、用户已经漏推几天之后再补救。</li>
 * </ul>
 *
 * <h3>三条防打扰规则</h3>
 * <ol>
 *   <li><b>不打扰群</b>：只私聊<b>机器人所有者</b>（{@code admin} 表 {@code group_id} 为空的记录）。
 *       凭据是全局的一件事，发到群里等于让所有人替一个人操心。</li>
 *   <li><b>同一结论不重复推</b>：结论没变时按
 *       {@link LoadDSConfig#KEY_BILI_CREDENTIAL_ALERT_INTERVAL_HOURS}（默认 12 小时）节流；
 *       <b>结论一变立刻推</b>（失效 ↔ 待更换 ↔ 恢复正常 都算变化）。</li>
 *   <li><b>用户刚问过就不推</b>：{@link CredentialGuard.Reason#USER_COMMAND} 触发的那次探测
 *       已经在聊天里回了完整状态，再私聊一遍就是纯噪音。</li>
 * </ol>
 *
 * <h3>为什么发送失败不改状态</h3>
 * 只有真正发出去（{@code retCode == 0}）才记"已提醒"。否则网络抖一下就把这次提醒"吃掉"，
 * 用户永远等不到那条消息 —— 而这恰恰是最不能静默失败的场景。
 *
 * <p>本类<b>不发任何 B 站请求</b>，只在探测结论已经拿到之后做一次 QQ 私聊。
 */
@Slf4j
@Component
public class CredentialNotifier {

    /** 同一结论的默认提醒间隔（小时）：一天最多两次 */
    public static final int DEFAULT_ALERT_INTERVAL_HOURS = 12;

    /** 提醒的类型。{@code NONE} 表示"健康"，用于比较与恢复通知 */
    public enum Kind {
        /** 一切正常 */
        NONE,
        /** 还能用，但服务端提示该换新凭据了 */
        EXPIRING,
        /** 服务端明确不认这枚凭据 */
        EXPIRED
    }

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private BotContainer botContainer;
    @Resource
    private AdminService adminService;

    /** 上一次真正发出去的提醒类型（{@code null} = 本进程还没提醒过） */
    private volatile Kind lastAlertKind = null;

    /** 上一次真正发出去的提醒时间戳（毫秒） */
    private volatile long lastAlertAt = 0L;

    /**
     * 探测结束后调用：该提醒就提醒，该恢复就报个平安。
     *
     * <p>调用方是 {@link CredentialGuard}（事件驱动探测 + 定时兜底探测两条路径都会经过），
     * 本方法<b>绝不抛异常</b> —— 提醒失败不能反过来打断探测/推送链路。
     *
     * @param status 探测结论，{@code null} 时直接返回
     * @param reason 触发探测的原因；用户主动查询时不提醒
     */
    public void onProbe(CredentialGuard.Status status, CredentialGuard.Reason reason) {
        if (status == null) {
            return;
        }
        // 用户刚发「登录状态」问过一次，答案已经在聊天里了 —— 再私聊一遍纯属打扰
        if (reason == CredentialGuard.Reason.USER_COMMAND) {
            return;
        }
        if (!alertEnabled()) {
            return;
        }
        try {
            Kind kind = classify(status);
            // UNKNOWN（网络/出口问题）没有对应结论，别拿它去猜凭据坏没坏：
            // 一次超时就把人叫去重新扫码头，比不提醒还糟。用 NONE 走"恢复"分支也不对 ——
            // 那会在网络抖动时误报"已恢复"，所以这里直接返回，什么都不动。
            if (kind == null) {
                return;
            }

            Kind previous = lastAlertKind;
            if (kind == Kind.NONE) {
                // 从"提醒过"回到健康：报个平安，然后把状态清掉（下次坏了能立刻再报）
                if (previous != null && previous != Kind.NONE) {
                    send(ownerText("✅ B 站登录已恢复正常",
                            status, "之前提示的问题已经没了，动态推送照常。"));
                }
                lastAlertKind = Kind.NONE;
                lastAlertAt = 0L;
                return;
            }

            // 结论没变 + 还在节流窗口内 ⇒ 跳过
            if (kind == previous && !intervalElapsed()) {
                log.debug("B 站凭据提醒在节流窗口内，已抑制：kind={}", kind);
                return;
            }

            String text = kind == Kind.EXPIRED ? expiredText(status) : expiringText(status);
            if (send(text)) {
                lastAlertKind = kind;
                lastAlertAt = System.currentTimeMillis();
            }
        } catch (Throwable e) {
            // 与项目其它异步入口同口径：Java2D 那类 Error 也要兜住，别让提醒把探测链带崩
            log.warn("发送 B 站凭据提醒时异常（不影响探测本身）：{}", e.toString());
        }
    }

    /**
     * 探测结论 → 提醒类型。
     *
     * @return {@link Kind#NONE} 健康 / {@link Kind#EXPIRING} 待更换 / {@link Kind#EXPIRED} 已失效；
     *         {@code null} 表示<b>这次得不出结论</b>（网络/出口问题），调用方应原样跳过
     */
    private static Kind classify(CredentialGuard.Status status) {
        return switch (status.getVerdict()) {
            case VALID -> (status.isRefreshChecked() && status.isRefreshNeeded())
                    ? Kind.EXPIRING : Kind.NONE;
            case INVALID -> Kind.EXPIRED;
            // NOT_CONFIGURED：用户主动清掉了 Cookie，是"有意的操作"，不是故障；
            // UNKNOWN：拿不到结论，不能下判断。两者都按"不动状态"处理。
            case NOT_CONFIGURED, UNKNOWN -> null;
        };
    }

    /**
     * 拼消息：标题 + 账号 + 一行说明 + 修法。
     *
     * <p>⚠️ 只出现 uid / 昵称与判定结论，<b>绝不出现 Cookie 值</b>（与
     * {@code CredentialGuard.Status#getSummary()} 同一口径）。
     */
    private static String ownerText(String title, CredentialGuard.Status status, String body) {
        StringBuilder sb = new StringBuilder(title).append('\n');
        if (status.getUid() > 0L) {
            sb.append("账号：").append(status.getUname() == null ? status.getUid() : status.getUname())
                    .append("（uid ").append(status.getUid()).append("）\n");
        }
        return sb.append(body).toString();
    }

    private static String expiredText(CredentialGuard.Status status) {
        return ownerText("❌ B 站登录已失效", status,
                "服务端已经不认这枚 Cookie（" + status.getSummary() + "）。\n"
                        + "\n影响：动态推送会静默失败（直播推送不受影响）；"
                        + "日志里表现为拉取失败，容易和 -412 混在一起。\n"
                        + "\n修法：私聊机器人发「登录」扫码重新登录，或发「设置cookie SESSDATA:...」。\n"
                        + "（这条与 -412「路径/出口被封」不是一回事，换代理无效）");
    }

    private static String expiringText(CredentialGuard.Status status) {
        return ownerText("⚠️ B 站登录该续了（现在还能用）", status,
                """
                        服务端提示这枚凭据该更换了。本库不支持自动刷新，\
                        所以含义就是「请重新扫一次」。
                        
                        现在登录仍然是好的，动态推送还正常 —— 趁现在换掉，比失效后再补救省事得多。
                        
                        修法：私聊机器人发「登录」扫码即可（会自动覆盖旧凭据，其余订阅不受影响）。""");
    }

    /**
     * 私聊机器人所有者。
     *
     * @return 是否<b>确实发出去了</b>（只有成功才允许调用方记"已提醒"）
     */
    private boolean send(String text) {
        Long owner = resolveOwnerQq();
        if (owner == null) {
            log.warn("B 站凭据需要提醒，但找不到机器人所有者（admin 表没有 group_id 为空的记录），只记日志不发私信。内容：{}",
                    text.replace('\n', ' '));
            return false;
        }
        Bot bot = botContainer.robots.isEmpty() ? null
                : botContainer.robots.values().iterator().next();
        if (bot == null) {
            log.warn("B 站凭据需要提醒，但机器人尚未连接（OneBot 未就绪），本次不发私信：{}", text.replace('\n', ' '));
            return false;
        }
        try {
            ActionData<?> resp = bot.sendPrivateMsg(owner, text, false);
            boolean ok = resp != null && Objects.equals(resp.getRetCode(), 0);
            if (ok) {
                log.info("已私聊提醒机器人所有者 {} B 站凭据状态：{}", owner, text.split("\n")[0]);
            } else {
                log.warn("B 站凭据提醒私聊发送失败，retCode={}", resp == null ? null : resp.getRetCode());
            }
            return ok;
        } catch (Throwable e) {
            log.warn("B 站凭据提醒私聊发送异常：{}", e.toString());
            return false;
        }
    }

    /**
     * 机器人所有者 QQ = {@code admin} 表里 {@code group_id} 为空的那条
     * （即 {@code application.yaml} 的 {@code bot.admin}）。
     *
     * <p>与 {@code AntiSpamPlugins#resolveOwnerQq} 同一口径；这里不做缓存 ——
     * 本方法只在"真要提醒"时调用一次（几小时一次的量级），多查一次库无所谓。
     */
    private Long resolveOwnerQq() {
        try {
            List<Admin> list = adminService.list(
                    new LambdaQueryWrapper<Admin>().isNull(Admin::getGroupId));
            if (list != null) {
                for (Admin admin : list) {
                    if (admin != null && admin.getQqUid() != null) {
                        return admin.getQqUid();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析机器人所有者失败", e);
        }
        return null;
    }

    /** 提醒开关：<b>没配也算开</b>（这是兜底提醒，默认不该静默） */
    private boolean alertEnabled() {
        return loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_CREDENTIAL_ALERT, true);
    }

    /** 同一结论是否已过最小提醒间隔；间隔 ≤0 表示"同一结论只提醒一次" */
    private boolean intervalElapsed() {
        int hours = alertIntervalHours();
        if (hours <= 0) {
            return false;
        }
        return System.currentTimeMillis() - lastAlertAt >= hours * 3600_000L;
    }

    /** 读提醒间隔（小时）；缺失/非法时回落默认值 */
    private int alertIntervalHours() {
        String raw = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_CREDENTIAL_ALERT_INTERVAL_HOURS);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ALERT_INTERVAL_HOURS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是整数（原值：{}），本次按默认 {} 小时处理",
                    LoadDSConfig.KEY_BILI_CREDENTIAL_ALERT_INTERVAL_HOURS, raw, DEFAULT_ALERT_INTERVAL_HOURS);
            return DEFAULT_ALERT_INTERVAL_HOURS;
        }
    }
}
