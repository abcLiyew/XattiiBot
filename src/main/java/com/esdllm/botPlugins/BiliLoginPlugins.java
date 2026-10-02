package com.esdllm.botPlugins;

import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.model.data.pojo.login.LoginCredential;
import com.esdllm.bilibiliApi.model.data.pojo.login.QrCodeLogin;
import com.esdllm.common.BotAdminChecker;
import com.esdllm.common.CookieUtils;
import com.esdllm.common.QrCodeUtils;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.service.CredentialGuard;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用聊天命令做 <b>B 站扫码登录</b>，把拿到的凭据写进 {@code config.biliCookie}。
 *
 * <p>解决的是"Cookie 过期后要手工去浏览器复制"这件事 —— 扫码是<b>纯 HTTP</b> 的
 * （{@code Login} 门面自己轮询 {@code passport.bilibili.com}），不需要浏览器、
 * 不需要账号密码，正好是机器人能承载的形态。
 *
 * <p><b>支持的命令</b>（<b>仅机器人所有者</b>；{@code 登录} 还限私聊，{@code 登录状态} 群聊私聊都可用）：
 * <pre>
 * 登录                      ← 机器人私聊发一张二维码，用【哔哩哔哩 App】扫 → 自动写入 Cookie
 * 登录状态 / 查询登录状态    ← 问服务端"当前这枚凭据还算不算数"（会发一次请求）
 *                             附带：上次探测时间、兜底探测间隔
 * </pre>
 *
 * <p><b>🔴 为什么只能私聊</b>：二维码本身就是一次<b>登录授权</b> —— 谁扫到，
 * 机器人就会以<b>谁的</b>账号登录。发到群里等于把"谁能控制机器人出站身份"交给群里所有人。
 * 这个限制不是保守，是二维码的语义决定的。
 *
 * <p><b>为什么"登录"这么敏感</b>：{@code SESSDATA} 等价于账号密码（拿到即可读写该账号的全部接口，
 * 含写操作）。所以本插件全程遵守项目既有约定：<b>值一律不出现在回复与日志里</b>，
 * 只出现键名与长度。
 *
 * <p><b>与 {@code BiliConfigPlugins} 的关系</b>：那个插件处理手工粘贴 Cookie（增量合并语义），
 * 本插件提供"不用手工"的获取路径，两者写的是同一个配置键，都会即时生效。
 * 合并口径统一走 {@link CookieUtils}。
 *
 * <p><b>⚠️ 但两个插件的权限口径不同，不要"顺手统一"</b>：本插件（{@code 登录}）用
 * {@link BotAdminChecker#isBotOwner}，那个插件（{@code 设置cookie / 设置代理}）用
 * {@link BotAdminChecker#isBotAdmin}。区别在于动作性质：
 * <ul>
 *   <li>{@code 设置cookie} 是<b>配置</b> —— 管理员交出的凭据是他<b>自己已经持有</b>的；</li>
 *   <li>{@code 登录} 是<b>授权转移</b> —— 二维码谁扫到，机器人就以<b>谁</b>的账号出站，
 *       本插件的 {@code loginEpoch} 也只能作废上一轮，管不了"谁扫的"。</li>
 * </ul>
 */
@Slf4j
@Component
@Shiro
public class BiliLoginPlugins {

    /** 允许出现在前置的 CQ 码（@机器人 / 图片等），匹配时忽略 */
    private static final String LEADING_CQ = "(\\[CQ:[^]]*\\]\\s*)*";

    /** `登录`（精确，不能写成前缀式，否则会把「登录状态」一起吃掉） */
    private static final String CMD_LOGIN = "(?is)^" + LEADING_CQ + "(?:b站|B站)?登录\\s*$";
    /**
     * `登录状态`，也接受「查询登录状态」「登录查询」「凭据状态」等口语说法。
     *
     * <p>⚠️ 触发词<b>刻意放宽</b>：这是只读查询，用户嘴里怎么说都应该查得到。
     * 而上面的 {@link #CMD_LOGIN}（扫码登录）<b>必须保持精确匹配</b> ——
     * 一旦放宽，「登录状态」会被它先吃掉，用户只是想问一句，机器人却丢出一张二维码。
     */
    private static final String CMD_LOGIN_STATUS = "(?is)^" + LEADING_CQ
            + "(?:b站|B站)?(?:登录状态|登录查询|登录情况|查询登录状态|查询登录|凭据状态).*$";

    /**
     * 等待扫码的总超时（毫秒）。
     *
     * <p>取 180000 与 B 站二维码寿命一致（见 {@code Login#waitForLogin} 的说明）。
     * 库内轮询间隔固定 2 秒，且轮询打的是 {@code passport.bilibili.com} ——
     * 与动态推送用的 {@code api.bilibili.com} 是<b>两个域</b>，
     * 所以这段轮询不会去踩动态 feed 那条已经被封的路径。
     */
    private static final long LOGIN_TIMEOUT_MS = 180_000L;

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private BotAdminChecker botAdminChecker;
    /**
     * 凭据状态探测器。
     *
     * <p>「登录状态」命令走它、动态推送失败后的自动探测也走它 —— <b>同一口径</b>，
     * 免得出现"命令说有效、推送那边说失效"这种两套判断。
     */
    @Resource
    private CredentialGuard credentialGuard;

    /**
     * 登录轮次号：每发起一次「登录」就 +1，<b>用来作废上一轮</b>。
     *
     * <p>扫码是阻塞等待（最长 3 分钟），期间用户完全可能再发一次「登录」。
     * 若不做作废，先发的那一轮醒来后会把<b>后一轮刚拿到的</b>凭据覆盖掉。
     * 所以每轮结束前都比一次自己的轮次号，不是最新的就静默放弃写入。
     */
    private final AtomicLong loginEpoch = new AtomicLong(0L);

    /**
     * 扫码登录：发二维码 → 等扫码 → 写入 Cookie。
     *
     * <p><b>整个方法标 {@code @Async}</b>：等待扫码最长 3 分钟，
     * 不能占住消息处理线程。（{@code BotFactory} 用 {@code ultimateTargetClass} 扫注解，
     * 代理不影响 handler 注册 —— 与 {@code BiliBiliPushPlugins.dynamicPush} 同一形态。）
     */
    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_LOGIN, at = AtEnum.BOTH)
    public void login(Bot bot, AnyMessageEvent event) {
        try {
            // ★ 所有者（admin 表里 group_id 为空的那条），不是 isBotAdmin：
            //   扫码登录是"夺取全局 B 站身份"，比改 Cookie 还顶格，只给所有者。
            if (!botAdminChecker.isBotOwner(event)) {
                send(bot, event, denyMessage());
                return;
            }
            // ★ 二维码 = 登录授权，只能私聊（理由见类注释）
            if (event.getGroupId() != null) {
                send(bot, event, "扫码登录只能在【私聊】里用。\n"
                        + "原因：二维码本身就是一次登录授权 —— 谁扫到，机器人就会以谁的账号登录。"
                        + "发到群里没法控制被谁扫走。");
                return;
            }

            long myEpoch = loginEpoch.incrementAndGet();

            Login login = new Login();
            QrCodeLogin qr;
            try {
                qr = login.getLoginQrCode();
            } catch (Exception e) {
                log.error("申请 B 站登录二维码失败", e);
                send(bot, event, "申请登录二维码失败：" + e.getMessage());
                return;
            }

            // 二维码内容与 qrcode_key 都是凭据的"半成品"，日志里只打长度
            log.info("已申请 B 站登录二维码，内容长度 {}，key 长度 {}",
                    qr.getUrl() == null ? 0 : qr.getUrl().length(),
                    qr.getQrcode_key() == null ? 0 : qr.getQrcode_key().length());

            String base64;
            try {
                base64 = QrCodeUtils.renderBase64(qr.getUrl());
            } catch (Exception e) {
                log.error("渲染登录二维码失败", e);
                send(bot, event, "二维码渲染失败：" + e.getMessage());
                return;
            }

            send(bot, event, MsgUtils.builder()
                    .text("请用【哔哩哔哩 App】扫描下面的二维码，并在手机上确认登录\n")
                    .text("（3 分钟内有效；再次发送「登录」会作废这张）\n")
                    .img("base64://" + base64)
                    .build());

            LoginCredential credential;
            try {
                credential = login.waitForLogin(qr.getQrcode_key(), LOGIN_TIMEOUT_MS);
            } catch (Exception e) {
                // 超时 / 二维码失效 / 网络异常，文案不同，原样透出（内层消息带业务码）
                log.info("扫码登录未完成：{}", e.getMessage());
                send(bot, event, "登录未完成：" + e.getMessage()
                        + "\n（二维码 3 分钟过期，需要时重新发一次「登录」）");
                return;
            }

            // 期间用户又发了「登录」⇒ 本轮作废，不写库（否则会覆盖更新的那枚凭据）
            if (loginEpoch.get() != myEpoch) {
                log.info("本轮扫码登录已被更新的一次请求作废，放弃写入 Cookie");
                return;
            }

            // 合并而不是覆盖：保留手工配过的 buvid3/buvid4
            // （缺 buvid 时库每次出站都要额外领一次匿名指纹，纯浪费请求）
            String header = credential.getCookieHeader();
            Map<String, String> fresh = CookieUtils.parse(header);
            if (fresh.isEmpty()) {
                send(bot, event, "登录返回的凭据为空，已忽略（未改动现有 Cookie）");
                log.warn("登录返回的 cookieHeader 解析为空，长度 {}",
                        header == null ? 0 : header.length());
                return;
            }
            String saved = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_COOKIE);
            String merged = CookieUtils.merge(saved, fresh);
            try {
                loadDSConfig.updateConfig(LoadDSConfig.KEY_BILI_COOKIE, merged);
            } catch (Exception e) {
                log.error("保存扫码登录得到的 Cookie 失败", e);
                send(bot, event, "登录成功但保存失败：" + e.getMessage());
                return;
            }
            // updateConfig → afterConfigChanged → applyBiliCookie → HttpPolicy.setCookie，即时生效

            send(bot, event, buildSuccessReply(credential, saved, merged));
            log.info("扫码登录成功，uid={}，Cookie 键=[{}]，长度 {}",
                    credential.getDedeUserId(), CookieUtils.keys(merged), merged.length());
        } catch (Throwable e) {
            // ★ Throwable 而非 Exception：与项目其它异步入口保持一致，
            //   避免 Error（例如渲染链路的 InternalError）直接冲到 AsyncUncaughtExceptionHandler
            log.error("扫码登录执行异常", e);
            send(bot, event, "登录过程出现异常：" + e.getMessage());
        }
    }

    /**
     * 查询机器人当前这枚 B 站凭据<b>还算不算数</b>（仅所有者；群聊与私聊都可用）。
     *
     * <p>输出分三层，从"本地事实"到"服务端权威判定"：
     * <ol>
     *   <li>config 表配没配、Cookie 键名与长度（本地，零成本）；</li>
     *   <li>服务端判定（{@link CredentialGuard#probeNow}，<b>会发请求</b>）；</li>
     *   <li>这条链路自己的运行状态：上次探测是什么时候、兜底探测间隔多少。</li>
     * </ol>
     *
     * <p>⚠️ 探测走 {@link CredentialGuard} 而不是直接 {@code new Login()}：这样命令与
     * "推送失败后的自动探测"用<b>同一套判断、同一份缓存</b>，不会出现
     * "命令说有效、推送那边说失效"。命令是用户主动触发的，所以用 {@code probeNow} 绕过
     * 节流，拿此刻的真实答案。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_LOGIN_STATUS, at = AtEnum.BOTH)
    public void loginStatus(Bot bot, AnyMessageEvent event) {
        try {
            // 与「登录」同一道闸：状态里会出现账号 uid / 昵称，不该给非所有者看
            if (!botAdminChecker.isBotOwner(event)) {
                send(bot, event, denyMessage());
                return;
            }
            String saved = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_COOKIE);
            StringBuilder reply = new StringBuilder("B站登录状态\n")
                    .append("config 表：").append(saved == null || saved.isBlank() ? "未配置" : "已配置")
                    .append("\n");
            if (saved != null && !saved.isBlank()) {
                reply.append("键：").append(CookieUtils.keys(saved))
                        .append("（长度 ").append(saved.length()).append("）\n");
            }

            CredentialGuard.Status status =
                    credentialGuard.probeNow(CredentialGuard.Reason.USER_COMMAND);

            switch (status.getVerdict()) {
                case NOT_CONFIGURED -> reply.append("\n服务端判定：—（没配 Cookie，无从校验）\n")
                        .append("实际影响：动态推送会因 B 站风控（-352）拉不到列表。\n")
                        .append("修法：私聊发「登录」扫码，或发「设置cookie SESSDATA:...」。");
                case VALID -> {
                    reply.append("\n服务端判定：✅ 已登录\n")
                            .append("账号：").append(status.getUname() == null ? status.getUid() : status.getUname())
                            .append("（uid ").append(status.getUid()).append("）");
                    if (status.isRefreshChecked() && status.isRefreshNeeded()) {
                        // 本库不实现"刷新"，这个 true 的当前含义就是"请重新登录"
                        reply.append("\n⚠️ 服务端提示这枚凭据该换了 —— 本库不支持自动刷新，")
                                .append("建议私聊发「登录」重新扫一次。");
                    }
                }
                case INVALID -> reply.append("\n服务端判定：❌ 已失效（").append(status.getSummary()).append("）\n")
                        .append("修法：私聊发「登录」扫码，或发「设置cookie SESSDATA:...」。\n")
                        .append("（这与 -412 路径/出口被封不是一回事，换代理无效）");
                default -> reply.append("\n服务端判定：⚠️ 未能确认（").append(status.getSummary()).append("）\n")
                        .append("这属于网络/出口问题，不是凭据失效 —— 先别急着重新登录。");
            }

            if (status.getProbedAt() > 0L) {
                reply.append("\n\n上次探测：").append(ago(status.getProbedAt()));
                if (status.isFromCache()) {
                    reply.append("（10 分钟内已探过，直接复用结论，没有重复发请求）");
                }
            }
            reply.append("\n兜底探测：");
            int hours = credentialCheckHours();
            if (hours <= 0) {
                reply.append("已关闭（config.").append(LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS).append("=0）");
            } else {
                reply.append("每 ").append(hours).append(" 小时一次（config.")
                        .append(LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS).append("）");
            }

            send(bot, event, reply.toString());
        } catch (Throwable e) {
            log.error("查询 B 站登录状态异常", e);
            send(bot, event, "查询登录状态异常：" + e.getMessage());
        }
    }

    /** 把时间戳说成「x 秒前 / x 分钟前 / x 小时前」 */
    private static String ago(long timestampMs) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestampMs) / 1000L);
        if (seconds < 60L) {
            return seconds + " 秒前";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + " 分钟前";
        }
        return (seconds / 3600L) + " 小时前";
    }

    /** 读兜底探测间隔（小时）；没配或配错时按 {@link CredentialGuard} 的默认值 */
    private int credentialCheckHours() {
        String raw = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS);
        if (raw == null || raw.isBlank()) {
            return CredentialGuard.DEFAULT_CHECK_HOURS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return CredentialGuard.DEFAULT_CHECK_HOURS;
        }
    }

    /**
     * 组装成功回执。
     *
     * <p><b>只出现键名与长度，绝不出现值</b>。
     */
    private String buildSuccessReply(LoginCredential credential, String oldCookie, String newCookie) {
        StringBuilder sb = new StringBuilder("✅ 登录成功，B 站凭据已更新并即时生效（无需重启）\n")
                .append("账号 uid：").append(credential.getDedeUserId()).append("\n")
                .append("键：").append(CookieUtils.keys(newCookie))
                .append("（长度 ").append(newCookie.length()).append("）\n");

        long expiresAt = credential.getExpiresAt();
        if (expiresAt > 0L) {
            String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochSecond(expiresAt));
            sb.append("到期：").append(when);
            long days = (expiresAt - System.currentTimeMillis() / 1000L) / 86400L;
            if (days > 0) {
                sb.append("（约 ").append(days).append(" 天后）");
            }
            sb.append("\n");
        } else {
            sb.append("到期：服务端未下发（web 端凭据实测约 30 天）\n");
        }

        // 原来没有 buvid 时提醒一下：不是错，只是每次出站会多领一次匿名指纹
        String oldKeys = CookieUtils.keys(oldCookie);
        if (!newCookie.contains("buvid")) {
            sb.append("\n💡 Cookie 里没有 buvid3/buvid4 —— 不影响登录态，")
                    .append("但库每次出站会额外领一次匿名指纹（多一个请求）。\n");
        }
        if (!oldKeys.isEmpty()) {
            sb.append("\n（本次是把新凭据并入原有 Cookie，不是整体覆盖）");
        }
        sb.append("\n下一轮动态推送（≤60s）就会用上新凭据。");
        return sb.toString();
    }

    private static void send(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }

    /**
     * 权限不足的提示。
     *
     * <p>只对<b>机器人所有者</b>开放：即 {@code admin} 表里 {@code group_id} 为空的那条记录
     * （由 {@code application.yaml} 的 {@code bot.admin} 写入）。
     * <b>「按群授权」的管理员、群主、群管理员一律不算</b> ——
     * 他们能在某个群里管事，不等于能把机器人的全局出站身份交出去。
     */
    private static String denyMessage() {
        return "你没有权限执行这个操作。\n"
                + "扫码登录会把机器人的 B 站出站身份换成扫码者本人的账号，"
                + "影响所有群、所有订阅的推送，因此只允许机器人所有者使用。\n"
                + "（群主 / 群管理员 / 按群授权的管理员都不算）";
    }
}
