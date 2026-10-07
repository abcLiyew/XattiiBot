package com.esdllm.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.model.Admin;
import com.esdllm.service.AdminService;
import com.mikuac.shiro.common.utils.ConnectionUtils;
import com.mikuac.shiro.common.utils.JsonObjectWrapper;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotFactory;
import com.mikuac.shiro.enums.ActionPath;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出站发送熔断器（SendGuard）—— 防机器人<b>自己</b>刷屏的保险丝。
 *
 * <p>背景：曾经发生过推送循环 bug 让机器人每 10 秒向群里发一条消息、永远无法自愈。
 * 入站反刷屏（AntiSpamPlugins）防的是"别人刷"，本切面防的是"自己刷"：
 * <b>任何</b>故障或设计缺陷导致向同一目标高频/重复发送时，在出口处熔断。
 *
 * <p><b>拦截点</b>：shiro 的 {@code Bot} 是具体类，全部 30 个发送调用点最终都汇入
 * {@code ActionHandler.action(session, action, params)}；{@code ActionHandler} 是单例
 * {@code @Component}，经构造器注入每个 Bot ⇒ 一个 {@code @Around} 切面覆盖 100% 出站发送，
 * 零调用点改动。
 *
 * <p><b>判定规则</b>（按目标维度：群 {@code g:<id>} / 私聊 {@code p:<id>}，各一个滑窗）：
 * <ol>
 *   <li><b>频率</b>：窗口（默认 60s）内发往同一目标超过阈值（群 20 / 私聊 10）⇒ 熔断；</li>
 *   <li><b>重复</b>：窗口内完全相同内容（SHA-1 指纹）达到阈值（默认 3）⇒ 熔断。</li>
 * </ol>
 * 熔断 = 之后 {@code sendGuardCircuitSeconds}（默认 60s）内发往该目标的消息<b>一律丢弃</b>，
 * 并向所有者私信告警（每目标 5 分钟冷却）。熔断自动恢复，无需人工介入。
 *
 * <p><b>被拦消息不抛异常</b>：返回 {@code retcode=-1 / status=failed} 的伪失败结果，
 * 与发送失败同形 —— 调用点原有的 retcode 判断天然兼容，不会因熔断引入新的异常路径。
 *
 * <p><b>三条铁律</b>：
 * <ul>
 *   <li><b>fail-safe</b>：熔断器自身任何异常都放行本次发送 —— 宁可不熔断，绝不误伤正常业务；</li>
 *   <li><b>告警走旁路</b>：告警私信经 {@link #bypass} ThreadLocal 标记跳过检查 ——
 *       否则"所有者私聊"这个目标本身被刷爆时，告警也发不出去；</li>
 *   <li><b>默认开</b>：{@code sendGuardEnabled} 缺省开（保险丝常开），
 *       正常业务远低于阈值、零感知；阈值全部热更（{@code sendGuard*} 配置键）。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class SendGuardAspect {

    /** 受保护的发送类 action（按 path 字符串匹配，不依赖具体枚举类） */
    private static final Set<String> SEND_ACTIONS = Set.of(
            "send_group_msg", "send_private_msg",
            "send_group_forward_msg", "send_private_forward_msg", "send_forward_msg");

    /** 每个目标滑窗的最大保留条数（防内存被异常流量撑大；正常远到不了） */
    private static final int MAX_WINDOW_ENTRIES = 500;

    /** 所有者 QQ 缓存 TTL */
    private static final long OWNER_CACHE_TTL_MILLIS = 60_000L;

    /** 滑窗记录：时间戳 + 内容指纹 */
    private static final class Entry {
        final long ts;
        final String fp;

        Entry(long ts, String fp) {
            this.ts = ts;
            this.fp = fp;
        }
    }

    /** 熔断触发原因（内部用） */
    private enum TripReason {RATE, DUP, CIRCUIT}

    /** 熔断判定结果：null = 放行 */
    private record Trip(TripReason reason, int count, int limit) {
    }

    @Resource
    private LoadDSConfig loadDSConfig;

    @Resource
    private AdminService adminService;

    @Resource
    private BotFactory botFactory;

    /** 告警旁路标记：告警私信本身跳过熔断检查（防"告警通道被熔断"的死锁） */
    private final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 每目标滑窗：target -> 发送记录 */
    private final Map<String, Deque<Entry>> windows = new ConcurrentHashMap<>();

    /** 熔断截止时刻：target -> epoch millis */
    private final Map<String, Long> circuitUntil = new ConcurrentHashMap<>();

    /** 熔断期间被丢弃的条数（熔断解除时进日志/告警，衡量"挡了多少"） */
    private final Map<String, Long> suppressed = new ConcurrentHashMap<>();

    /** 告警冷却：target -> 上次告警时刻 */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    /** 所有者 QQ 缓存 */
    private volatile Long ownerQq;
    private volatile long ownerQqAt;

    /**
     * 出口切面：包住 {@code ActionHandler.action(..)} —— shiro 所有发送方法的唯一汇入口。
     */
    @Around("execution(* com.mikuac.shiro.handler.ActionHandler.action(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        try {
            if (!Boolean.TRUE.equals(bypass.get())
                    && loadDSConfig.isEnabled(LoadDSConfig.KEY_SEND_GUARD_ENABLED, true)) {
                Object[] args = pjp.getArgs();
                if (args != null && args.length == 3
                        && args[0] instanceof WebSocketSession session
                        && args[1] instanceof ActionPath action
                        && SEND_ACTIONS.contains(action.getPath())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) args[2];
                    Trip trip = check(params);
                    if (trip != null) {
                        String target = targetOf(params);
                        if (trip.reason() != TripReason.CIRCUIT) {
                            // 新熔断：告警；CIRCUIT 是熔断期内的常规丢弃，不重复告警
                            alert(session, target, action.getPath(), trip);
                        }
                        return blockedResult(trip);
                    }
                }
            }
        } catch (Exception e) {
            // fail-safe 铁律：熔断器自身出问题绝不阻塞发送
            log.error("发送熔断器自身异常（已放行本次发送）", e);
        }
        return pjp.proceed();
    }

    /**
     * 判定一次发送。返回 {@code null} 放行，否则熔断/丢弃。
     *
     * <p>顺序：熔断中 ⇒ 直接丢（计数 suppressed）；否则滑窗计数 + 重复指纹判定，
     * 越线则置熔断并返回原因。本次越线的那条<b>也被丢弃</b>（它正是刷屏的一部分）。
     */
    private Trip check(Map<String, Object> params) {
        String target = targetOf(params);
        if (target == null) {
            return null; // 提取不到目标的发送（理论上不会），不拦
        }
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds() * 1000L;
        int limit = target.charAt(0) == 'g' ? groupLimit() : privateLimit();
        int dupLimit = dupThreshold();
        long circuitMillis = circuitSeconds() * 1000L;

        Long until = circuitUntil.get(target);
        if (until != null) {
            if (now < until) {
                suppressed.merge(target, 1L, Long::sum);
                return new Trip(TripReason.CIRCUIT, 0, 0);
            }
            // 熔断解除：清空该目标滑窗 —— 否则旧记录还在窗内，第一条恢复消息会立刻再次越线，
            // "60s 后自动恢复"就变成不可预测的"最迟 120s 恢复"。熔断=罚时，出来就是清白身。
            circuitUntil.remove(target, until);
            Long dropped = suppressed.remove(target);
            Deque<Entry> stale = windows.get(target);
            if (stale != null) {
                synchronized (stale) {
                    stale.clear();
                }
            }
            log.warn("发送熔断已解除：{}（熔断期间丢弃 {} 条，滑窗已清零）", target, dropped == null ? 0 : dropped);
        }

        String fp = fingerprint(params == null ? null : params.get("message"));
        Deque<Entry> deque = windows.computeIfAbsent(target, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst().ts > windowMillis) {
                deque.pollFirst();
            }
            int rate = deque.size();
            int dup = 0;
            for (Entry e : deque) {
                if (e.fp.equals(fp)) {
                    dup++;
                }
            }
            if (rate + 1 > limit) {
                circuitUntil.put(target, now + circuitMillis);
                return new Trip(TripReason.RATE, rate + 1, limit);
            }
            if (dup + 1 >= dupLimit) {
                circuitUntil.put(target, now + circuitMillis);
                return new Trip(TripReason.DUP, dup + 1, dupLimit);
            }
            deque.addLast(new Entry(now, fp));
            while (deque.size() > MAX_WINDOW_ENTRIES) {
                deque.pollFirst();
            }
        }
        return null;
    }

    /** 目标标识：群消息 {@code g:<groupId>}，私聊 {@code p:<userId>}；提取不到返回 {@code null}。 */
    private static String targetOf(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object gid = params.get("group_id");
        if (gid != null) {
            return "g:" + gid;
        }
        Object uid = params.get("user_id");
        if (uid != null) {
            return "p:" + uid;
        }
        return null;
    }

    /** 内容指纹：message 可能是 String 或 List<ArrayMsg>，统一 toString 后 SHA-1。 */
    private static String fingerprint(Object message) {
        String s = message == null ? "" : String.valueOf(message);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return s.length() > 64 ? s.substring(0, 64) : s;
        }
    }

    /** 伪失败结果：与发送失败同形（retcode=-1），调用点既有 retcode 判断天然兼容。 */
    private static JsonObjectWrapper blockedResult(Trip trip) {
        JsonObjectWrapper r = new JsonObjectWrapper();
        r.put("status", "failed");
        r.put("retcode", -1);
        r.put("wording", "send-guard: " + describeReason(trip));
        return r;
    }

    private static String describeReason(Trip trip) {
        return switch (trip.reason()) {
            case RATE -> "频率越线（" + trip.count() + "/" + trip.limit() + " 条每窗口），已熔断";
            case DUP -> "重复内容越线（第 " + trip.count() + " 条相同消息，阈值 " + trip.limit() + "），已熔断";
            case CIRCUIT -> "目标处于熔断期，消息已丢弃";
        };
    }

    /**
     * 熔断告警：私信所有者（每目标冷却 {@code sendGuardAlertCooldownMinutes} 分钟）。
     *
     * <p>用 {@code botFactory.createBot(selfId, session)} 基于当前会话临时建 Bot 发私信；
     * 发送前置 {@link #bypass} —— 告警必须发得出去，哪怕"所有者私聊"目标本身已被熔断。
     */
    private void alert(WebSocketSession session, String target, String actionPath, Trip trip) {
        long now = System.currentTimeMillis();
        long cooldownMillis = alertCooldownMinutes() * 60_000L;
        Long last = lastAlertAt.get(target);
        if (last != null && now - last < cooldownMillis) {
            log.warn("发送熔断（告警冷却中，仅记日志）：{} {}", target, describeReason(trip));
            return;
        }
        String text = "🚨 发送熔断触发\n"
                + "目标：" + ("g:".equals(target.substring(0, 2))
                        ? "群 " + target.substring(2) : "私聊 " + target.substring(2)) + "\n"
                + "原因：" + describeReason(trip) + "\n"
                + "动作：接下来 " + circuitSeconds() + " 秒内发往该目标的消息全部丢弃，之后自动恢复\n"
                + "建议：检查最近部署/推送任务是否有循环发送 bug\n"
                + "（出自 " + actionPath + "；调阈值用配置键 sendGuard*，总闸 sendGuardEnabled）";
        log.warn("发送熔断触发：{} {}（{}）", target, describeReason(trip), actionPath);

        Long owner = resolveOwnerQq();
        if (owner == null) {
            log.warn("发送熔断但未配置机器人所有者（admin 表无 group_id 为空的记录），只记日志不发私信");
            return;
        }
        bypass.set(Boolean.TRUE);
        try {
            long selfId = ConnectionUtils.parseSelfId(session);
            Bot bot = botFactory.createBot(selfId, session);
            var resp = bot.sendPrivateMsg(owner, text, false);
            if (resp != null && resp.getRetCode() == 0) {
                lastAlertAt.put(target, now);
            } else {
                log.warn("发送熔断告警私信失败：retcode={}", resp == null ? "null" : resp.getRetCode());
            }
        } catch (Exception e) {
            log.warn("发送熔断告警私信异常", e);
        } finally {
            bypass.remove();
        }
    }

    /** 机器人所有者 QQ（admin 表 group_id 为空那条），TTL 缓存；找不到返回 {@code null}。 */
    private Long resolveOwnerQq() {
        long now = System.currentTimeMillis();
        if (ownerQq != null && now - ownerQqAt < OWNER_CACHE_TTL_MILLIS) {
            return ownerQq;
        }
        try {
            List<Admin> list = adminService.list(new LambdaQueryWrapper<Admin>().isNull(Admin::getGroupId));
            if (list != null && !list.isEmpty() && list.get(0).getQqUid() != null) {
                ownerQq = list.get(0).getQqUid();
                ownerQqAt = now;
            }
        } catch (Exception e) {
            log.warn("解析机器人所有者失败", e);
        }
        return ownerQq;
    }

    /** 定期清扫：移除滑窗已空、熔断/告警冷却早已过期的目标项，防 Map 缓慢膨胀。 */
    @Scheduled(fixedDelay = 600_000L)
    public void sweep() {
        try {
            long now = System.currentTimeMillis();
            long windowMillis = windowSeconds() * 1000L;
            windows.entrySet().removeIf(e -> {
                Deque<Entry> d = e.getValue();
                synchronized (d) {
                    while (!d.isEmpty() && now - d.peekFirst().ts > windowMillis) {
                        d.pollFirst();
                    }
                    return d.isEmpty();
                }
            });
            circuitUntil.entrySet().removeIf(e -> now > e.getValue());
            long cooldownMillis = alertCooldownMinutes() * 60_000L;
            lastAlertAt.entrySet().removeIf(e -> now - e.getValue() > cooldownMillis * 2);
            // suppressed 只清没有对应熔断的孤儿（正常解除路径已 remove）
            suppressed.keySet().removeIf(k -> !circuitUntil.containsKey(k));
        } catch (Exception e) {
            log.warn("发送熔断器清扫异常", e);
        }
    }

    private int windowSeconds() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_WINDOW_SECONDS,
                LoadDSConfig.DEFAULT_SEND_GUARD_WINDOW_SECONDS);
    }

    private int groupLimit() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_GROUP_LIMIT,
                LoadDSConfig.DEFAULT_SEND_GUARD_GROUP_LIMIT);
    }

    private int privateLimit() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_PRIVATE_LIMIT,
                LoadDSConfig.DEFAULT_SEND_GUARD_PRIVATE_LIMIT);
    }

    private int dupThreshold() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_DUP_THRESHOLD,
                LoadDSConfig.DEFAULT_SEND_GUARD_DUP_THRESHOLD);
    }

    private int circuitSeconds() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_CIRCUIT_SECONDS,
                LoadDSConfig.DEFAULT_SEND_GUARD_CIRCUIT_SECONDS);
    }

    private int alertCooldownMinutes() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_SEND_GUARD_ALERT_COOLDOWN_MINUTES,
                LoadDSConfig.DEFAULT_SEND_GUARD_ALERT_COOLDOWN_MINUTES);
    }
}
