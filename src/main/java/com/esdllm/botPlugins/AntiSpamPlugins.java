package com.esdllm.botPlugins;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.common.BotAdminChecker;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.model.Admin;
import com.esdllm.service.AdminService;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Order;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import com.mikuac.shiro.enums.MsgTypeEnum;
import com.mikuac.shiro.model.ArrayMsg;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反刷屏检测（AntiSpam）。
 *
 * <p>背景：群里会出现几类"机器人式"刷屏 —— 斗图轰炸、合并转发刷屏、语音轰炸、
 * 病毒式转发同一条消息、恶意反复调机器人。本插件在<b>每一条群消息</b>上做统计，
 * 命中就按配置的级别处置（默认<b>仅私信告警</b>给机器人所有者，不动任何消息）。
 *
 * <p><b>四层管线</b>：
 * <ol>
 *   <li><b>分类</b>：取消息的主导段类型（{@code image / forward / video / record / share /
 *       face / text / other}）；@ 机器人 的消息<b>额外</b>计入 {@code command} 一类。
 *       "不同类型不同策略"就落在这一步的<b>每类一套阈值</b>上。</li>
 *   <li><b>计数</b>：滑动时间窗（默认 60s）内分别统计
 *       <b>单人×类型</b> 与 <b>全群×类型</b> 两个维度 —— 前者防一个人刷屏，
 *       后者防多人一起轰炸 / 病毒转发（单看前者拦不住）。</li>
 *   <li><b>判定</b>：① 任一维度在窗口内<b>越过</b>阈值（{@code ==} 阈值那次触发一次）；
 *       ② <b>相同消息</b>：内容归一化取指纹，全群维度在窗口内出现 {@code ≥} 阈值次触发。</li>
 *   <li><b>处置</b>：按 {@code antiSpamAction} 分级 —— {@code alert}（仅私信所有者，默认）→
 *       {@code recall}（再撤回触发消息）→ {@code ban}（再禁言发送者 10 分钟）。</li>
 * </ol>
 *
 * <p><b>设计取舍</b>（为什么这么做而不是别的做法）：
 * <ul>
 *   <li><b>固定阈值而不是自适应基线</b>：基线要学习时间、群活跃度差异大、还难解释。
 *       固定阈值可配置、行为可预测，告警里能直接写"60s 内 12 条图片 / 阈值 6"。</li>
 *   <li><b>默认只告警、不动消息</b>：撤回/禁言都要求机器人是<b>群管理员</b>，
 *       且 QQ 拦不住<b>已发出</b>的消息（只能事后撤回）。先用 alert 跑一阵看误报，
 *       确认可信再升级动作 —— 这是 {@code antiSpamAction} 的默认值取 alert 的原因。</li>
 *   <li><b>命令复读不算重复刷屏</b>：全群都发「签到」「查询」是<b>合法</b>高频，
 *       所以 @ 机器人 的消息、以及过短的纯文本（{@code <}{@link #DUP_MIN_TEXT_LENGTH} 字）
 *       <b>不参与全群去重</b>，否则把正常指令使用误报。</li>
 *   <li><b>告警自身要冷却</b>（同群同原因默认 5 分钟一次）：刷屏是持续的，
 *       没有冷却机器人会把所有者的私信箱刷爆 —— 本末倒置。</li>
 *   <li><b>统计纯内存、零 DDL</b>：重启清零是合理代价（窗口只有几十秒），
 *       与 {@code TodayWifePlugins} 的轮转状态同一判例 —— 不像
 *       {@code pushedDynamicIds} 那种"必须落库否则功能坏了"。</li>
 * </ul>
 *
 * <p><b>豁免</b>（这些人发的消息<b>不统计</b>）：机器人自己、群主 / 群管理员
 * （{@code sender.role}）、{@code admin} 表白名单。监控范围用
 * {@code antiSpamGroups} 白名单控制（空 = 所有群）。
 *
 * <p><b>命令</b>（<b>仅机器人所有者</b>，因为是全局配置；权限判定与异常兜底见 {@link #guard}）：
 * <pre>
 * 反刷屏                     ← 状态面板
 * 反刷屏 开 ｜ 反刷屏 关      ← 总闸
 * 反刷屏 动作 alert           ← 处置级别：alert / recall / ban
 * </pre>
 */
@Slf4j
@Shiro
@Component
public class AntiSpamPlugins {

    // ------------------------------------------------------------------ 命令正则（两两互斥：锚 ^$ + 结尾限定）

    /** 允许出现在前置的 CQ 码（@机器人 / 图片等），匹配时忽略 */
    private static final String LEADING_CQ = "(\\[CQ:[^]]*\\]\\s*)*";
    /** 状态面板：「反刷屏」/「反刷屏 状态」（结尾必须是状态/面板或直接结束，避免吃掉「反刷屏 开」） */
    private static final String CMD_PANEL = "(?is)^" + LEADING_CQ + "(?:反刷屏|防刷屏)(?:\\s*(?:状态|面板))?$";
    /** 总闸：「反刷屏 开 / 关」 */
    private static final String CMD_TOGGLE = "(?is)^" + LEADING_CQ + "(?:反刷屏|防刷屏)\\s+(开|关)$";
    /** 处置级别：「反刷屏 动作 alert」 */
    private static final String CMD_ACTION = "(?is)^" + LEADING_CQ + "(?:反刷屏|防刷屏)\\s+动作\\s+(\\S+)$";
    /** 参数设置：「反刷屏 设置 窗口 60」（值里允许逗号/冒号，如阈值表） */
    private static final String CMD_SET = "(?is)^" + LEADING_CQ + "(?:反刷屏|防刷屏)\\s+设置\\s+\\S+\\s+\\S.*$";

    /** 合法的处置级别 */
    private static final Set<String> ACTIONS = Set.of("alert", "recall", "ban");

    /** 参与全群去重的纯文本最小长度：比这短的（多为「签到」「查询」等合法指令）不去重 */
    private static final int DUP_MIN_TEXT_LENGTH = 5;
    /** 禁言时长（秒）= 10 分钟 */
    private static final int BAN_SECONDS = 600;
    /** owner / 管理员白名单缓存有效期（毫秒） */
    private static final long CACHE_TTL_MILLIS = 60_000;
    /** 单条命令长度上限，防异常输入 */
    private static final int MAX_LENGTH = 2000;

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private AdminService adminService;

    // ------------------------------------------------------------------ 统计状态（纯内存）

    /** 频率计数：key = {@code u:群:用户:类型} 或 {@code g:群:类型}，值为窗口内时间戳队列 */
    private final Map<String, Deque<Long>> counters = new ConcurrentHashMap<>();
    /** 相同消息去重：key = {@code d:群:指纹} */
    private final Map<String, DupEntry> dups = new ConcurrentHashMap<>();
    /** 告警冷却：key = {@code c:群:原因:类型}，值为上次告警时间戳 */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    /** 阈值缓存（原始串变了才重解析，hot-reload 生效） */
    private volatile String userLimitsRaw = "";
    private volatile Map<String, Integer> userLimits = Map.of();
    private volatile String groupLimitsRaw = "";
    private volatile Map<String, Integer> groupLimits = Map.of();

    /** 机器人所有者 QQ（admin 表 group_id 为空那条），带 TTL 缓存 */
    private volatile Long ownerQq;
    private volatile long ownerQqAt;
    /** admin 表白名单（QQ 集合），带 TTL 缓存 */
    private volatile Set<Long> botAdminCache = Set.of();
    private volatile long botAdminAt;

    /** 相同消息去重条目：窗口起点 + 已出现次数 */
    private static final class DupEntry {
        private long firstAt;
        private int count;
    }

    // ================================================================== 全局钩子

    /**
     * 全局消息钩子：每条群消息都过一遍。
     *
     * <p>入口就挡掉绝大多数情况（总闸关 / 非群消息 / 群不在白名单 / 豁免人），
     * 真正进 {@link #detect} 的只是少数。整个方法包一层 try —— 它是只读旁路，
     * <b>绝不能因为统计出错而影响正常消息分发</b>。
     */
    @Async
    @AnyMessageHandler
    @Order(2)
    public void monitor(Bot bot, AnyMessageEvent event) {
        try {
            if (!loadDSConfig.isEnabled(LoadDSConfig.KEY_ANTI_SPAM_ENABLED)) {
                return;
            }
            Long groupId = event.getGroupId();
            if (groupId == null) {
                return;                     // 只管群消息（私聊刷屏暂时不管）
            }
            if (!inScope(groupId)) {
                return;
            }
            if (isExempt(event, event.getSelfId())) {
                return;
            }
            detect(bot, event, groupId);
        } catch (Exception e) {
            log.error("反刷屏检测异常（不影响正常消息处理）", e);
        }
    }

    // ================================================================== 引擎

    /**
     * 分类 → 计数 → 判定 → 处置。
     */
    private void detect(Bot bot, AnyMessageEvent event, long groupId) {
        long now = System.currentTimeMillis();
        long windowMillis = windowMillis();
        Long userId = event.getUserId();
        List<ArrayMsg> segs = event.getArrayMsg();

        String bucket = classify(segs);                 // 主导段类型；纯 at/回复时为 null
        boolean atSelf = hasAtSelf(segs, event.getSelfId());

        // 频率判定：主导类型 + （@ 机器人 ⇒ 再计入 command）
        if (bucket != null) {
            checkFrequency(bot, event, groupId, userId, bucket, now, windowMillis);
        }
        if (atSelf) {
            checkFrequency(bot, event, groupId, userId, "command", now, windowMillis);
        }

        // 相同消息判定（全群维度）
        checkDuplicate(bot, event, groupId, userId, bucket, atSelf, now, windowMillis);
    }

    /** 频率判定：单人×类型 与 全群×类型 两个维度，越过阈值那次各触发一次。 */
    private void checkFrequency(Bot bot, AnyMessageEvent event, long groupId, Long userId,
                                String bucket, long now, long windowMillis) {
        int userLimit = userLimitOf(bucket);
        if (userId != null && userLimit > 0) {
            int userCount = bump("u:" + groupId + ":" + userId + ":" + bucket, now, windowMillis);
            if (userCount == userLimit) {
                dispatch(bot, Incident.frequency(event, groupId, bucket, "单人", userCount, userLimit, userId));
            }
        }
        int groupLimit = groupLimitOf(bucket);
        if (groupLimit > 0) {
            int groupCount = bump("g:" + groupId + ":" + bucket, now, windowMillis);
            if (groupCount == groupLimit) {
                dispatch(bot, Incident.frequency(event, groupId, bucket, "全群", groupCount, groupLimit, userId));
            }
        }
    }

    /** 相同消息判定：内容归一化取指纹，全群维度在窗口内 ≥ 阈值次触发。 */
    private void checkDuplicate(Bot bot, AnyMessageEvent event, long groupId, Long userId,
                                String bucket, boolean atSelf, long now, long windowMillis) {
        if (atSelf) {
            return;                         // @机器人 的命令复读是合法调用，不算重复刷屏
        }
        String normalized = normalize(event.getMessage());
        if (normalized.isEmpty()) {
            return;
        }
        // 短文本（多为合法指令）不参与全群去重 —— 否则全群都发「签到」会误报
        if ("text".equals(bucket) && normalized.length() < DUP_MIN_TEXT_LENGTH) {
            return;
        }
        int threshold = dupThreshold();
        if (threshold <= 0) {
            return;
        }
        String key = "d:" + groupId + ":" + fingerprint(normalized);
        DupEntry entry = dups.computeIfAbsent(key, k -> new DupEntry());
        int count;
        synchronized (entry) {
            if (now - entry.firstAt > windowMillis) {
                entry.firstAt = now;
                entry.count = 0;
            }
            entry.count++;
            count = entry.count;
        }
        if (count == threshold) {
            dispatch(bot, Incident.duplicate(event, groupId, normalized, count, threshold, userId));
        }
    }

    /** 滑动窗口计数：把 {@code now} 加进 key 对应的队列，返回窗口内条数。 */
    private int bump(String key, long now, long windowMillis) {
        Deque<Long> queue = counters.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            Long head;
            while ((head = queue.peekFirst()) != null && now - head > windowMillis) {
                queue.pollFirst();
            }
            queue.addLast(now);
            return queue.size();
        }
    }

    /**
     * 命中后的分级处置：告警永远发（受冷却约束），撤回/禁言按级别叠加。
     */
    private void dispatch(Bot bot, Incident inc) {
        String action = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_ACTION,
                LoadDSConfig.DEFAULT_ANTI_SPAM_ACTION).toLowerCase();
        boolean doRecall = "recall".equals(action) || "ban".equals(action);
        boolean doBan = "ban".equals(action);

        alertOwner(bot, inc, action);
        if (doRecall && inc.culpritMessageId != null) {
            recall(bot, inc);
        }
        if (doBan && inc.culpritUserId != null) {
            ban(bot, inc);
        }
    }

    /**
     * 私信告警给机器人所有者（同群同原因冷却一次）。
     */
    private void alertOwner(Bot bot, Incident inc, String action) {
        String coolKey = "c:" + inc.groupId + ":" + inc.reason + ":" + inc.bucketLabel;
        long now = System.currentTimeMillis();
        Long last = lastAlertAt.get(coolKey);
        if (last != null && now - last < alertCooldownMillis()) {
            log.debug("反刷屏告警在冷却期内，已抑制：{}", coolKey);
            return;
        }
        String text = describe(inc, action);
        log.info("反刷屏命中：{}", text.replace('\n', ' '));

        Long owner = resolveOwnerQq();
        if (owner == null) {
            log.warn("反刷屏命中但未配置机器人所有者（admin 表无 group_id 为空的记录），只记日志不发私信");
            return;
        }
        try {
            var resp = bot.sendPrivateMsg(owner, text, false);
            boolean ok = resp != null && resp.getRetCode() != null && resp.getRetCode() == 0;
            if (ok) {
                lastAlertAt.put(coolKey, now);      // 只有发出去了才进冷却，失败不抑制下次
            } else {
                log.warn("反刷屏告警私信发送失败，retCode={}", resp == null ? null : resp.getRetCode());
            }
        } catch (Exception e) {
            log.warn("反刷屏告警私信发送异常", e);
        }
    }

    /** 撤回触发消息（要求机器人是群管理员；失败只记日志，不向外抛）。 */
    private void recall(Bot bot, Incident inc) {
        try {
            var result = bot.deleteMsg(inc.culpritMessageId);
            boolean ok = result != null && result.getRetCode() != null && result.getRetCode() == 0;
            log.info("反刷屏撤回消息 {}（群{}）：{}", inc.culpritMessageId, inc.groupId,
                    ok ? "成功" : "失败 retCode=" + (result == null ? null : result.getRetCode()));
        } catch (Exception e) {
            log.warn("反刷屏撤回异常（机器人可能不是群管理）：{}", e.toString());
        }
    }

    /** 禁言发送者 {@link #BAN_SECONDS} 秒（要求机器人是群管理员）。 */
    private void ban(Bot bot, Incident inc) {
        try {
            var result = bot.setGroupBan(inc.groupId, inc.culpritUserId, BAN_SECONDS);
            boolean ok = result != null && result.getRetCode() != null && result.getRetCode() == 0;
            log.info("反刷屏禁言 用户{}（群{}）{}s：{}", inc.culpritUserId, inc.groupId, BAN_SECONDS,
                    ok ? "成功" : "失败 retCode=" + (result == null ? null : result.getRetCode()));
        } catch (Exception e) {
            log.warn("反刷屏禁言异常（机器人可能不是群管理）：{}", e.toString());
        }
    }

    // ================================================================== 分类 / 归一化

    /**
     * 主导段类型：跳过前缀的 at / 回复，取第一个实义段归入桶。
     *
     * @return 桶名（{@code image / forward / video / record / share / face / text / other}）；
     *         整条消息只有 at / 回复时返回 {@code null}
     */
    private static String classify(List<ArrayMsg> segs) {
        if (segs == null) {
            return "other";
        }
        for (ArrayMsg seg : segs) {
            if (seg == null) {
                continue;
            }
            MsgTypeEnum type = seg.getType();
            if (type == null || type == MsgTypeEnum.at || type == MsgTypeEnum.reply) {
                continue;
            }
            return bucketOf(type);
        }
        return null;
    }

    /** 把 {@link MsgTypeEnum} 归并进少数几个统计桶。 */
    private static String bucketOf(MsgTypeEnum type) {
        if (type == null) {
            return "other";
        }
        return switch (type) {
            case image -> "image";
            case forward -> "forward";
            case video -> "video";
            case record -> "record";
            case share, contact, music -> "share";
            case face, mface, marketface, poke, rps, new_rps, dice, new_dice, basketball -> "face";
            case text -> "text";
            default -> "other";
        };
    }

    /** 消息里是否 @ 了机器人（命令意图信号）。 */
    private static boolean hasAtSelf(List<ArrayMsg> segs, long selfId) {
        if (segs == null) {
            return false;
        }
        for (ArrayMsg seg : segs) {
            if (seg == null || seg.getType() != MsgTypeEnum.at) {
                continue;
            }
            try {
                var data = seg.getData();
                if (data != null && data.has("qq") && data.get("qq").asLong() == selfId) {
                    return true;
                }
            } catch (Exception ignore) {
                // 单个 at 段解析失败不影响整体判定
            }
        }
        return false;
    }

    /** 归一化：折叠所有空白为单个空格并去首尾 —— 作为去重指纹与内容预览的基础。 */
    private static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    /** 内容指纹：归一化串的 SHA-1（定长，避免超长合并转发内容直接当 key 占内存）。 */
    private static String fingerprint(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    // ================================================================== 豁免 / 范围 / 缓存

    /**
     * 是否豁免（这些人发的消息不统计）。<b>拿不准按豁免处理</b>，绝不误伤。
     */
    private boolean isExempt(AnyMessageEvent event, long selfId) {
        Long userId = event.getUserId();
        if (userId == null) {
            return true;
        }
        if (userId == selfId) {
            return true;                                // 机器人自己
        }
        String role = event.getSender() == null ? null : event.getSender().getRole();
        if ("owner".equals(role) || "admin".equals(role)) {
            return true;                                // 群主 / 群管理员
        }
        return botAdmins().contains(userId);            // admin 表白名单
    }

    /** 群是否在监控范围（{@code antiSpamGroups} 为空 = 所有群）。 */
    private boolean inScope(long groupId) {
        String raw = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_GROUPS, "");
        if (raw.isEmpty()) {
            return true;
        }
        String target = String.valueOf(groupId);
        for (String part : raw.split(",")) {
            if (part.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 机器人所有者 QQ（admin 表 group_id 为空那条），TTL 缓存；找不到返回 {@code null}。 */
    private Long resolveOwnerQq() {
        long now = System.currentTimeMillis();
        if (ownerQq != null && now - ownerQqAt < CACHE_TTL_MILLIS) {
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

    /** admin 表白名单（QQ 集合），TTL 缓存。 */
    private Set<Long> botAdmins() {
        long now = System.currentTimeMillis();
        if (now - botAdminAt < CACHE_TTL_MILLIS) {
            return botAdminCache;
        }
        try {
            List<Admin> list = adminService.list();
            Set<Long> set = new HashSet<>();
            if (list != null) {
                for (Admin a : list) {
                    if (a != null && a.getQqUid() != null) {
                        set.add(a.getQqUid());
                    }
                }
            }
            botAdminCache = set;
            botAdminAt = now;
        } catch (Exception e) {
            log.warn("刷新 bot 管理员白名单缓存失败", e);
        }
        return botAdminCache;
    }

    // ================================================================== 配置读取（每次现读，hot-reload）

    private int windowSeconds() {
        return Math.max(5, loadDSConfig.intOf(LoadDSConfig.KEY_ANTI_SPAM_WINDOW_SECONDS,
                LoadDSConfig.DEFAULT_ANTI_SPAM_WINDOW_SECONDS));
    }

    private long windowMillis() {
        return windowSeconds() * 1000L;
    }

    private long alertCooldownMillis() {
        return Math.max(1, loadDSConfig.intOf(LoadDSConfig.KEY_ANTI_SPAM_ALERT_COOLDOWN_MINUTES,
                LoadDSConfig.DEFAULT_ANTI_SPAM_ALERT_COOLDOWN_MINUTES)) * 60_000L;
    }

    private int dupThreshold() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_ANTI_SPAM_DUP_THRESHOLD,
                LoadDSConfig.DEFAULT_ANTI_SPAM_DUP_THRESHOLD);
    }

    private int userLimitOf(String bucket) {
        String raw = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_USER_LIMITS,
                LoadDSConfig.DEFAULT_ANTI_SPAM_USER_LIMITS);
        if (!raw.equals(userLimitsRaw)) {
            userLimits = parseLimits(raw);
            userLimitsRaw = raw;
        }
        return userLimits.getOrDefault(bucket, userLimits.getOrDefault("other", 0));
    }

    private int groupLimitOf(String bucket) {
        String raw = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_GROUP_LIMITS,
                LoadDSConfig.DEFAULT_ANTI_SPAM_GROUP_LIMITS);
        if (!raw.equals(groupLimitsRaw)) {
            groupLimits = parseLimits(raw);
            groupLimitsRaw = raw;
        }
        return groupLimits.getOrDefault(bucket, groupLimits.getOrDefault("other", 0));
    }

    /** 解析 {@code 类型:条数,类型:条数}；非法片段跳过，条数 {@code ≤0} 视为不启用该类型。 */
    private static Map<String, Integer> parseLimits(String raw) {
        Map<String, Integer> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            int colon = token.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String type = token.substring(0, colon).trim();
            try {
                int n = Integer.parseInt(token.substring(colon + 1).trim());
                if (!type.isEmpty() && n > 0) {
                    map.put(type, n);
                }
            } catch (NumberFormatException ignore) {
                // 单个阈值写错不影响其它项
            }
        }
        return map;
    }

    // ================================================================== 定时清理

    /**
     * 清掉窗口外的旧统计，防止计数表无限增长（key 空间 = 群 × 用户 × 类型）。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void sweep() {
        try {
            long now = System.currentTimeMillis();
            long windowMillis = windowMillis();
            counters.entrySet().removeIf(e -> {
                Deque<Long> queue = e.getValue();
                synchronized (queue) {
                    Long last = queue.peekLast();
                    return last == null || now - last > windowMillis;
                }
            });
            dups.entrySet().removeIf(e -> now - e.getValue().firstAt > windowMillis);
            long cooldown = alertCooldownMillis();
            lastAlertAt.entrySet().removeIf(e -> now - e.getValue() > cooldown);
        } catch (Exception e) {
            log.warn("反刷屏统计清理异常", e);
        }
    }

    // ================================================================== 命令

    /** 状态面板。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_PANEL, at = AtEnum.BOTH)
    public void antiSpamPanel(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doPanel(bot, event));
    }

    /** 总闸开关。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_TOGGLE, at = AtEnum.BOTH)
    public void antiSpamToggle(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doToggle(bot, event));
    }

    /** 处置级别。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_ACTION, at = AtEnum.BOTH)
    public void antiSpamAction(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doAction(bot, event));
    }

    /** 参数设置。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SET, at = AtEnum.BOTH)
    public void antiSpamSet(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doSet(bot, event));
    }

    private void doPanel(Bot bot, AnyMessageEvent event) {
        boolean enabled = loadDSConfig.isEnabled(LoadDSConfig.KEY_ANTI_SPAM_ENABLED);
        String action = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_ACTION,
                LoadDSConfig.DEFAULT_ANTI_SPAM_ACTION);
        String groups = loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_GROUPS, "");
        Long owner = resolveOwnerQq();
        StringBuilder sb = new StringBuilder("反刷屏状态\n")
                .append("总闸：").append(enabled ? "开" : "关").append("（").append(LoadDSConfig.KEY_ANTI_SPAM_ENABLED).append("）\n")
                .append("动作：").append(action).append("（").append(actionText(action)).append("）\n")
                .append("滑窗：").append(windowSeconds()).append("s　去重阈值：").append(dupThreshold()).append("次　告警冷却：")
                .append(alertCooldownMillis() / 60000).append("分钟\n")
                .append("监控群：").append(groups.isEmpty() ? "所有群" : groups).append("\n")
                .append("单人阈值：").append(loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_USER_LIMITS,
                        LoadDSConfig.DEFAULT_ANTI_SPAM_USER_LIMITS)).append("\n")
                .append("全群阈值：").append(loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_GROUP_LIMITS,
                        LoadDSConfig.DEFAULT_ANTI_SPAM_GROUP_LIMITS)).append("\n")
                .append("告警私信对象：").append(owner == null ? "未配置所有者！" : owner.toString()).append("\n")
                .append("改：反刷屏 开/关　｜　反刷屏 动作 alert|recall|ban\n")
                .append("反刷屏 设置 窗口 60　｜　反刷屏 设置 去重 3　｜　反刷屏 设置 冷却 5\n")
                .append("反刷屏 设置 监控群 123,456（或 全部）　｜　反刷屏 设置 单人阈值 image:6,text:12\n")
                .append("反刷屏 设置 全群阈值 image:20,text:40　（全部即时生效）");
        if (!enabled) {
            sb.append("\n\n当前是关的，发「反刷屏 开」启用（先保持 alert 跑一阵看误报）");
        }
        send(bot, event, sb.toString());
    }

    private void doToggle(Bot bot, AnyMessageEvent event) {
        Boolean on = parseBool(lastToken(event.getMessage()));
        if (on == null) {
            send(bot, event, "只认 开 / 关（也接受 on/off、true/false、1/0）");
            return;
        }
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_ANTI_SPAM_ENABLED, on.toString());
        } catch (Exception e) {
            log.error("保存反刷屏总闸失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        send(bot, event, on
                ? "✅ 反刷屏已开启\n当前动作：" + currentActionText() + "\n建议先保持 alert 跑一阵看误报，可信再升级"
                : "⏹ 反刷屏已关闭，不再做任何统计");
        log.info("反刷屏总闸已{}", on ? "开启" : "关闭");
    }

    private void doAction(Bot bot, AnyMessageEvent event) {
        String value = lastToken(event.getMessage()).toLowerCase();
        if (!ACTIONS.contains(value)) {
            send(bot, event, "动作只能是 alert / recall / ban\n"
                    + "alert=仅告警　recall=告警+撤回　ban=告警+撤回+禁言10分钟\n"
                    + "（后两者要求机器人是群管理员，误伤代价递增，建议从 alert 起步）\n收到：" + value);
            return;
        }
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_ANTI_SPAM_ACTION, value);
        } catch (Exception e) {
            log.error("保存反刷屏处置级别失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        StringBuilder reply = new StringBuilder("✅ 反刷屏处置级别 → ").append(value)
                .append("（").append(actionText(value)).append("）");
        if (!"alert".equals(value)) {
            reply.append("\n⚠️ 撤回/禁言都要求机器人是目标群的管理员，否则只告警、动作不生效");
        }
        send(bot, event, reply.toString());
        log.info("反刷屏处置级别已设为 {}", value);
    }

    /**
     * 参数设置：「反刷屏 设置 <项> <值>」。
     *
     * <p>支持项（中文/英文别名均可）：窗口/window、去重/dup、冷却/cooldown、
     * 监控群/groups、单人阈值/user、全群阈值/group。全部走 {@code updateConfig} 即时生效。
     */
    private void doSet(Bot bot, AnyMessageEvent event) {
        List<String> tokens = tokens(event.getMessage());
        // tokens: [反刷屏, 设置, 项, 值...]（值可能含空格？阈值表不含，拼接兜底）
        if (tokens.size() < 4) {
            send(bot, event, setUsage());
            return;
        }
        String item = tokens.get(2).toLowerCase();
        String value = String.join("", tokens.subList(3, tokens.size())).trim();

        String key;
        String checked = validateSetValue(item, value);
        if (checked != null) {
            send(bot, event, checked + "\n\n" + setUsage());
            return;
        }
        switch (item) {
            case "窗口", "window" -> key = LoadDSConfig.KEY_ANTI_SPAM_WINDOW_SECONDS;
            case "去重", "dup" -> key = LoadDSConfig.KEY_ANTI_SPAM_DUP_THRESHOLD;
            case "冷却", "cooldown" -> key = LoadDSConfig.KEY_ANTI_SPAM_ALERT_COOLDOWN_MINUTES;
            case "监控群", "groups" -> {
                key = LoadDSConfig.KEY_ANTI_SPAM_GROUPS;
                if (value.equals("全部") || value.equalsIgnoreCase("all")) {
                    value = "";
                }
            }
            case "单人阈值", "user" -> key = LoadDSConfig.KEY_ANTI_SPAM_USER_LIMITS;
            case "全群阈值", "group" -> key = LoadDSConfig.KEY_ANTI_SPAM_GROUP_LIMITS;
            default -> {
                send(bot, event, "不认识的设置项「" + tokens.get(2) + "」\n\n" + setUsage());
                return;
            }
        }
        try {
            loadDSConfig.updateConfig(key, value);
        } catch (Exception e) {
            log.error("保存反刷屏参数失败：{}={}", key, value, e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        send(bot, event, "✅ 已设置 " + tokens.get(2) + " → " + (value.isEmpty() ? "（空=全部群）" : value)
                + "\n配置键 " + key + "，即时生效");
        log.info("反刷屏参数已对话设置：{} = {}", key, value);
    }

    /** 校验设置值，返回 {@code null} 表示通过，否则返回错误提示。 */
    private static String validateSetValue(String item, String value) {
        switch (item) {
            case "窗口", "window" -> {
                Integer n = parsePositiveInt(value);
                if (n == null || n < 5 || n > 3600) {
                    return "窗口必须是 5~3600 的整数（秒），收到：" + value;
                }
            }
            case "去重", "dup" -> {
                Integer n = parsePositiveInt(value);
                if (n == null || n < 2 || n > 100) {
                    return "去重阈值必须是 2~100 的整数（次），收到：" + value;
                }
            }
            case "冷却", "cooldown" -> {
                Integer n = parsePositiveInt(value);
                if (n == null || n < 1 || n > 1440) {
                    return "告警冷却必须是 1~1440 的整数（分钟），收到：" + value;
                }
            }
            case "监控群", "groups" -> {
                if (!value.equals("全部") && !value.equalsIgnoreCase("all") && !value.matches("\\d+(,\\d+)*")) {
                    return "监控群必须是逗号分隔的群号（如 123,456）或「全部」，收到：" + value;
                }
            }
            case "单人阈值", "user", "全群阈值", "group" -> {
                Map<String, Integer> parsed = parseLimits(value);
                if (parsed.isEmpty()) {
                    return "阈值表格式：类型:条数,类型:条数（如 image:6,text:12），收到：" + value;
                }
                int tokens = value.split(",").length;
                if (parsed.size() < tokens) {
                    return "有 " + (tokens - parsed.size()) + " 个片段无法解析（类型:正整数），请检查：" + value;
                }
            }
            default -> {
                return null; // 不认识的项由调用方的 switch 处理
            }
        }
        return null;
    }

    private static Integer parsePositiveInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String setUsage() {
        return "用法：反刷屏 设置 <项> <值>\n"
                + "窗口 60（秒）　去重 3（次）　冷却 5（分钟）\n"
                + "监控群 123,456 或 全部\n"
                + "单人阈值 image:6,text:12,other:15\n"
                + "全群阈值 image:20,text:40,other:50";
    }

    /** 把消息剥掉 CQ 码后按空白切成 token。 */
    private static List<String> tokens(String message) {
        if (message == null || message.length() > MAX_LENGTH) {
            return List.of();
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ").replace('　', ' ').trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return List.of(body.split("\\s+"));
    }

    // ================================================================== 命令辅助

    /** 取命令最后一个参数（剥掉 CQ 码后按空白切）。 */
    private static String lastToken(String message) {
        if (message == null || message.length() > MAX_LENGTH) {
            return "";
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ").replace('　', ' ').trim();
        if (body.isEmpty()) {
            return "";
        }
        String[] parts = body.split("\\s+");
        return parts[parts.length - 1].trim();
    }

    /** 解析布尔，认不出返回 {@code null}（fail-closed，与 {@link LoadDSConfig#isEnabled} 同口径）。 */
    private static Boolean parseBool(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "开", "开启", "启用", "是", "on", "true", "1", "yes" -> Boolean.TRUE;
            case "关", "关闭", "禁用", "否", "off", "false", "0", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private String currentActionText() {
        return actionText(loadDSConfig.stringOf(LoadDSConfig.KEY_ANTI_SPAM_ACTION,
                LoadDSConfig.DEFAULT_ANTI_SPAM_ACTION));
    }

    private static String actionText(String action) {
        return switch (action == null ? "alert" : action) {
            case "recall" -> "告警 + 撤回该消息";
            case "ban" -> "告警 + 撤回 + 禁言10分钟";
            default -> "仅告警（不动消息）";
        };
    }

    private String describe(Incident inc, String action) {
        StringBuilder sb = new StringBuilder("🚨 反刷屏告警\n")
                .append("群：").append(inc.groupId).append("\n")
                .append("原因：").append(inc.reason);
        if (inc.bucketLabel != null && !inc.bucketLabel.isEmpty()) {
            sb.append("（").append(inc.bucketLabel).append("）");
        }
        sb.append("\n情况：").append(inc.scope).append("在 ").append(windowSeconds())
                .append("s 内 ").append(inc.count).append(" 条，阈值 ").append(inc.threshold).append("\n");
        if (inc.topUserId != null) {
            sb.append("触发：用户 ").append(inc.topUserId).append("\n");
        }
        if (inc.contentPreview != null && !inc.contentPreview.isEmpty()) {
            sb.append("内容：").append(inc.contentPreview).append("\n");
        }
        sb.append("动作：").append(actionText(action));
        return sb.toString();
    }

    private static void send(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }

    /**
     * 权限不足的提示（全局配置 ⇒ 只有机器人所有者能改，与「开关 / 设置cookie」同一档）。
     */
    private static String denyMessage() {
        return """
                你没有权限改这个配置。
                反刷屏是整个机器人共用的全局设置，只允许机器人所有者修改。
                （群主 / 群管理员不算，「按群授权」的管理员也不算，私聊也不例外）
                成为所有者的方式：application.yaml 把 bot.admin 设成你的 QQ，\
                或手工往 admin 表加一行 qq_uid=你的QQ、group_id 留空。""";
    }

    /**
     * 「改全局配置」命令的统一外壳：<b>权限判定（机器人所有者）+ 异常兜底</b>。
     * 与 {@code BiliConfigPlugins#guard} 同一套纪律：fail-closed、异常不穿出 handler。
     */
    private boolean guard(Bot bot, AnyMessageEvent event, Runnable action) {
        try {
            if (!botAdminChecker.isBotOwner(event)) {
                send(bot, event, denyMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("反刷屏命令的权限判定异常，已按拒绝处理", e);
            safeSend(bot, event, denyMessage());
            return false;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.error("处理反刷屏命令失败", e);
            safeSend(bot, event, "处理失败：" + e.getMessage());
        }
        return true;
    }

    /** 回复命令结果，保证"回复失败"不会再抛出去（用在 catch 路径）。 */
    private static void safeSend(Bot bot, AnyMessageEvent event, String message) {
        try {
            send(bot, event, message);
        } catch (Exception e) {
            log.debug("回复反刷屏命令结果时再次异常，已忽略：{}", e.toString());
        }
    }

    // ================================================================== 内部类

    /** 一次命中（频率异常 或 相同消息刷屏）。 */
    private static final class Incident {
        private Long groupId;
        private String bucketLabel;     // 类型中文名
        private String reason;          // 频率异常 / 相同消息刷屏
        private String scope;           // 单人 / 全群
        private int count;
        private int threshold;
        private Long topUserId;         // 触发者
        private String contentPreview;
        private Integer culpritMessageId;   // 撤回对象（可能为 null）
        private Long culpritUserId;         // 禁言对象（可能为 null）

        private static Incident frequency(AnyMessageEvent event, long groupId, String bucket,
                                          String scope, int count, int threshold, Long topUserId) {
            Incident i = new Incident();
            i.groupId = groupId;
            i.bucketLabel = labelOf(bucket);
            i.reason = "频率异常";
            i.scope = scope;
            i.count = count;
            i.threshold = threshold;
            i.topUserId = topUserId;
            i.contentPreview = preview(event.getMessage());
            // 单人超频才有明确的撤回/禁言对象；全群轰炸没有单一责任人，不绑定
            if ("单人".equals(scope)) {
                i.culpritMessageId = event.getMessageId();
                i.culpritUserId = topUserId;
            }
            return i;
        }

        private static Incident duplicate(AnyMessageEvent event, long groupId, String content,
                                          int count, int threshold, Long userId) {
            Incident i = new Incident();
            i.groupId = groupId;
            i.bucketLabel = "重复消息";
            i.reason = "相同消息刷屏";
            i.scope = "全群";
            i.count = count;
            i.threshold = threshold;
            i.topUserId = userId;
            i.contentPreview = preview(content);
            i.culpritMessageId = event.getMessageId();
            i.culpritUserId = userId;
            return i;
        }

        private static String preview(String message) {
            String s = normalize(message);
            return s.length() > 40 ? s.substring(0, 40) + "…" : s;
        }
    }

    /** 桶名 → 中文名（告警与面板里给人看）。 */
    private static String labelOf(String bucket) {
        return switch (bucket) {
            case "image" -> "图片";
            case "forward" -> "合并转发";
            case "video" -> "短视频";
            case "record" -> "语音";
            case "share" -> "链接分享";
            case "face" -> "表情";
            case "text" -> "文本";
            case "command" -> "命令(@机器人)";
            default -> "其它";
        };
    }
}
