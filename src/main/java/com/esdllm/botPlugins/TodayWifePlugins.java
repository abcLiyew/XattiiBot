package com.esdllm.botPlugins;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.esdllm.common.CmdPrefix;
import com.esdllm.config.LoadDSConfig;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 今日老婆插件
 * <p>
 * 群友发送关键词「今日老婆」，抽取群内另一位成员作为其今日老婆，
 * 并以 base64 图片的形式发送该成员头像。
 *
 * <p><b>抽签规则（2026-10-07 改）</b>：从「每人一次机会的均匀随机」改成
 * <b>按群轮转发牌（无放回）</b>——
 * <ol>
 *   <li><b>一轮内每个人恰好被抽到一次</b>：不会再出现"人多的群里总是那几个人
 *       被抽到、新人/潜水党永远抽不到"（均匀随机下这不是 bug 而是必然，
 *       只是观感上像"有人被漏掉了"）。</li>
 *   <li><b>一轮走完才重洗</b>，且<b>新一轮的第一次不抽上一轮最后那位</b>
 *       ⇒ 跨轮"紧接着又抽到同一个人"也被挡掉，重复率降到最低。</li>
 *   <li>候选集每次现取，所以<b>新入群的人立刻进池、退群的人自动出池</b>，
 *       不需要额外的成员变更监听。</li>
 * </ol>
 *
 * <p><b>每人每天次数闸（2026-10-11 加）</b>：{@code wifeDailyLimit}（默认 1，热更）。
 * <ol>
 *   <li><b>= 1（默认，"每天固定"）</b>：当天第一次抽中谁就<b>锁定</b>，之后再发「今日老婆」
 *       回的是<b>同一位</b>，不再重新发牌；</li>
 *   <li><b>&gt; 1</b>：每人每天至多抽这么多次，超了回一句提示；</li>
 *   <li><b>≤ 0</b>：不限（旧行为，仅留作回退对照）。</li>
 * </ol>
 * 为什么要有这道闸：本插件原本对"一个人当天抽几次"毫无约束，连点 11 次就是 11 条群消息，
 * 直接打穿出口熔断（{@code sendGuardLimitWife}=10/60s）；而熔断是按<b>群</b>生效的 ——
 * 刷屏的人只被挡 60s，被连累的是整群 60s 收不到任何消息。所以闸门做在<b>入口</b>。
 * 另：超限后<b>只提示一次</b>，之后静默 —— 超限了还一直回消息，等于把熔断的活自己又干一遍。
 *
 * <p><b>离婚冷静期（2026-10-12 加）</b>：{@code wifeDivorce*} 系列键。
 * <ol>
 *   <li>{@code wifeDailyLimit = 1} 时当天老婆是<b>锁定</b>的 —— 离婚是唯一"当天换人"的路；</li>
 *   <li>发「离婚」<b>不是立刻生效</b>，而是进入<b>冷静期</b>；冷静期到点后<b>再发一次「离婚」</b>才正式离婚；</li>
 *   <li>冷静期<b>逐次递增</b>：{@code 首次 = wifeDivorceBaseMinutes}（默认 30），
 *       当天每多申请一次<b>翻倍</b>，封顶 {@code wifeDivorceMaxMinutes}（默认 1440 = 一天）；</li>
 *   <li>冷静期内发「离婚」只回一句"还剩多久"（不算确认、不重置倒计时）；</li>
 *   <li>「撤回离婚」可撤销申请（不退还当天申请次数）；</li>
 *   <li>离婚成功后当天次数<b>归零</b> ⇒ 可以重新抽今日老婆。</li>
 * </ol>
 * 为什么要冷静期：{@code wifeDailyLimit=1} 的整套价值就是"今天就是这位"，
 * 一按就换等于这条规则不存在；而"抽到不喜欢就离婚重抽"的连点正是把出站熔断打穿的典型场景
 * （见上方每日次数闸的注释）。
 *
 * <p>⚠️ 轮转状态、每日次数、离婚状态都是<b>进程内内存</b>的（不落库）：重启后轮转从新一轮开始、
 * 当日次数与离婚申请清零。代价只是"重启后可能重复/多抽一次 / 冷静期作废"，
 * 换来的是零 DDL —— 与 {@code pushedDynamicIds} 那种"必须落库否则功能坏了"的情形不同，
 * 这里不值得为它开表。
 */
@Slf4j
@Shiro
@Component
public class TodayWifePlugins {

    /**
     * 命令的<b>可选前缀</b>：行首空白 / CQ 码（@机器人 / 图片等）/ <b>手打的 @机器人</b>。
     *
     * <p>取 {@link CmdPrefix#PREFIX}，与搜索 / cookie / 录播等命令<b>同一口径</b>。
     *
     * <p><b>2026-10-11 收紧</b>：改之前是 {@code .*今日老婆.*}（<b>「包含」</b>匹配），
     * 于是 {@code @张三 今日老婆}、{@code 我想抽今日老婆} 都会触发 —— 群里互相 @ 时被机器人插话。
     * 现在要求<b>关键词紧跟在（可选的）@机器人 之后</b>：
     * <pre>
     * 今日老婆              ← 仍然可以（裸命令不受影响）
     * @Hana 今日老婆         ← 可以（手打 @机器人）
     * [CQ:at,qq=…] 今日老婆   ← 可以（真 @ 由 shiro 剥掉后就是裸命令）
     * @张三 今日老婆         ← ❌ 不再触发
     * 我想抽今日老婆         ← ❌ 不再触发（代价：这类口语失灵，已与用户确认要收紧）
     * </pre>
     */
    private static final String CMD_PREFIX = CmdPrefix.PREFIX;

    /**
     * QQ 头像接口，s 为头像尺寸
     */
    private static final String QQ_AVATAR_URL = "https://q1.qlogo.cn/g?b=qq&nk=%d&s=640";

    /**
     * 头像下载超时时间，单位毫秒
     */
    private static final int TIMEOUT_MILLIS = 5000;

    /**
     * "今天"按哪个时区切 —— 群友在中国，跨天点必须是<b>北京时间 0 点</b>。
     *
     * <p>服务器时区（中国香港 = UTC+8）虽然也是 +8，但写成常量更稳：
     * 换机器 / 容器镜像时区跑偏也不会让"今天"变成 UTC 的那一天。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private LoadDSConfig loadDSConfig;

    /**
     * 每个群的轮转状态。
     *
     * <p>键是群号；条目只有在群真的用过这个功能后才会出现（自然按群懒加载）。
     */
    private final Map<Long, GroupRotation> rotations = new ConcurrentHashMap<>();

    /**
     * 每人每天的抽签记录：群号 → (QQ → 当天记录)。条目按群/按人懒加载。
     *
     * <p>只在 {@code wifeDailyLimit > 0} 时才会被写；{@link #sweep()} 负责清非当天的。
     */
    private final Map<Long, Map<Long, DailyDraw>> dailyDraws = new ConcurrentHashMap<>();

    /**
     * 一个人在一个群里"今天"的抽签状态。
     *
     * <p>锁对象就是这个实例本身（{@code synchronized (record)}）：粒度是 (群, 人)，
     * 把同一个人的并发请求串行化，不影响群里其他人。
     *
     * <p>离婚状态也挂在这里（见 {@link #divorceWaitUntil}），所以「抽老婆」与「离婚」
     * 用的是<b>同一把锁</b> —— 不会出现"确认离婚的同时又抽了一次"这类交错。
     */
    private static final class DailyDraw {
        /** 记录属于哪一天（{@link LocalDate#toEpochDay()}，按 {@link #ZONE} 切天） */
        private long day;
        /** 当天已抽次数（先占坑、抽失败回滚，所以"占坑中"也会暂时计 1） */
        private int count;
        /** 当天是否已经回过一次"抽满了"的提示 —— 只提示一次，之后静默 */
        private boolean notified;
        /**
         * 当天第一次抽中的人；{@code wifeId} 就是"每天固定"的实现：
         * 当天再抽不再发牌，直接把它回给用户。
         */
        private Long wifeId;

        // ==================== 离婚冷静期（2026-10-12） ====================

        /**
         * 冷静期结束时间（epoch millis）；{@code 0} = <b>没有待确认的离婚申请</b>。
         *
         * <p>有值即表示"已申请、等确认"：到点前发「离婚」只提示剩余时间，到点后发「离婚」才正式离婚。
         */
        private long divorceWaitUntil;
        /**
         * 当天已发起过几次离婚申请（<b>只增不减</b>，撤回也不回退）。
         *
         * <p>它是"逐次增加"的实现：等待时长 = {@code min(基准 << 已申请次数, 上限)}。
         * 故意不因撤回而回退 —— 否则"申请→撤回→申请"就能永远停在最短冷静期。
         */
        private int divorceCount;
        /** 上次"还没到时间"提醒的时间（epoch millis），驱动提醒冷却；{@code 0} = 还没提醒过 */
        private long divorceRemindedAt;
    }

    /**
     * 一个群的「发牌」状态：本轮已抽过谁 + 上一次抽中的是谁。
     */
    private static final class GroupRotation {
        /** 本轮已经被抽到过的人（一轮走完会被清空） */
        private final Set<Long> servedThisRound = new HashSet<>();
        /** 上一次抽中的人，仅用于"新一轮第一抽避开他" */
        private Long lastDrawn;
    }

    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "^" + CMD_PREFIX + "今日老婆.*", at = AtEnum.BOTH)
    public void todayWife(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();

        // 该功能仅在群聊中可用
        if (Objects.isNull(groupId)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 该功能仅限群聊使用哦~").build(), false);
            return;
        }

        int limit = dailyLimit();
        long today = todayEpochDay();
        DailyDraw record = recordOf(groupId, qqUid);

        // 每个用户一把锁：把"同一个人"的并发请求串行化。
        // 必要性：本方法是 @Async 的，连点 3 次就并发进 3 个线程；若各自读到 count=0
        // 就都能通过次数闸，闸门形同虚设。锁粒度是 (群, 人)，不阻塞其他人。
        synchronized (record) {
            // 跨天：第一次抽当天就把它清干净（所以初始化时的 day 填 0 也无所谓）
            if (record.day != today) {
                record.day = today;
                record.count = 0;
                record.notified = false;
                record.wifeId = null;
                // 离婚状态与"今天"同源：跨天一并作废（不然昨天没确认的申请今天还在倒计时）
                record.divorceWaitUntil = 0L;
                record.divorceCount = 0;
                record.divorceRemindedAt = 0L;
            }

            // —— 次数闸 ——
            if (limit > 0 && record.count >= limit) {
                if (!record.notified) {
                    // 只在"第一次超限"回一句，之后彻底静默 ——
                    // 超限了还一直回消息，等于把出口熔断那份活自己又干一遍（还是刷屏）。
                    record.notified = true;
                    bot.sendMsg(event, buildLimitMsg(qqUid, record.wifeId, limit), false);
                } else {
                    log.debug("今日老婆：群 {} 用户 {} 当日已抽满 {} 次，静默忽略", groupId, qqUid, limit);
                }
                return;
            }

            // 先占坑后抽：抽失败（取成员失败/群里没人/头像挂了）会回滚，不白扣一次机会
            record.count++;
            boolean drawn = false;
            try {
                List<GroupMemberInfoResp> candidates = getCandidates(bot, groupId, qqUid, event.getSelfId());
                if (Objects.isNull(candidates)) {
                    bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 获取群成员失败，稍后再试试吧~").build(), false);
                    return;
                }
                if (candidates.isEmpty()) {
                    bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 群里暂时没有别人，找不到老婆呢~").build(), false);
                    return;
                }

                GroupMemberInfoResp wife = pickWife(groupId, candidates);
                bot.sendMsg(event, buildWifeMsg(qqUid, wife), false);
                if (Objects.isNull(record.wifeId)) {
                    // limit=1 的"固定"全靠它：只认当天第一次抽中的人
                    record.wifeId = wife.getUserId();
                }
                drawn = true;
            } finally {
                if (!drawn) {
                    record.count--;
                }
            }
        }
    }

    /**
     * 「离婚」：申请离婚 / 冷静期到点后确认离婚。
     *
     * <p><b>同一条命令承担两个语义</b>（这是需求要的"到时间再来确认"）：
     * <ol>
     *   <li><b>还没申请</b> ⇒ 进入冷静期，回一句"多久之后再来"；</li>
     *   <li><b>冷静期未到</b> ⇒ 只提示还剩多久，<b>不</b>算确认；</li>
     *   <li><b>冷静期已到</b> ⇒ 正式离婚，当天抽签次数归零 ⇒ 可以重新抽今日老婆。</li>
     * </ol>
     *
     * <p>⚠️ cmd 必须写 {@code 离婚\s*$} 而<b>不能</b>带 {@code .*}：shiro 是整串
     * {@code matches()}，写成 {@code .*} 会把「撤回离婚」也吃进来 ⇒ 两条 handler 同时命中、双回复。
     */
    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "^" + CMD_PREFIX + "离婚\\s*$", at = AtEnum.BOTH)
    public void divorce(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();
        if (Objects.isNull(groupId)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 该功能仅限群聊使用哦~").build(), false);
            return;
        }
        if (!divorceEnabled()) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 离婚功能暂时关闭了呢~").build(), false);
            return;
        }

        DailyDraw record = existingRecord(groupId, qqUid);
        // 没有任何当天记录 ⇒ 今天根本没抽过/已经离过 ⇒ 直接回"没老婆"。
        // 这里**不能用 recordOf()**：那会为每个发过「离婚」的人建一个空条目。
        if (Objects.isNull(record)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 你今天还没有老婆呢，先去抽一个吧~").build(), false);
            return;
        }

        String reply;
        // 与 todayWife 同一把锁（锁对象就是这个 record 实例）
        synchronized (record) {
            long today = todayEpochDay();
            if (record.day != today) {
                // 记录是昨天的（sweep 还没扫到）：等同于"今天没抽过"
                bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 你今天还没有老婆呢，先去抽一个吧~").build(), false);
                return;
            }
            if (Objects.isNull(record.wifeId)) {
                // 抽过但没抽成（当时群里没人 / 成员接口失败）⇒ 没有可离的婚
                bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 你今天还没有老婆呢，先去抽一个吧~").build(), false);
                return;
            }

            long now = System.currentTimeMillis();

            if (record.divorceWaitUntil == 0L) {
                // ---- 第 ① 种：还没申请 ⇒ 进入冷静期 ----
                int maxPerDay = divorceMaxPerDay();
                if (maxPerDay > 0 && record.divorceCount >= maxPerDay) {
                    bot.sendMsg(event, MsgUtils.builder().at(qqUid)
                            .text(" 今天已经申请过 " + maxPerDay + " 次离婚啦，不能再折腾了，明天再说吧~")
                            .build(), false);
                    return;
                }
                long waitMillis = divorceWaitMillis(record.divorceCount);
                record.divorceCount++;
                record.divorceWaitUntil = now + waitMillis;
                record.divorceRemindedAt = 0L;
                reply = MsgUtils.builder().at(qqUid)
                        .text(" 离婚申请已提交，冷静期 " + humanDuration(waitMillis) + "。\n"
                                + "到点后再发一次「离婚」才算数哦~（到 " + clockAt(record.divorceWaitUntil) + "）")
                        .build();
                log.info("今日老婆-离婚：群 {} 用户 {} 申请离婚（第 {} 次，冷静期 {}），前妻 {}", groupId, qqUid,
                        record.divorceCount, humanDuration(waitMillis), record.wifeId);
            } else if (now < record.divorceWaitUntil) {
                // ---- 第 ② 种：冷静期未到 ⇒ 只提示，不算确认 ----
                long left = record.divorceWaitUntil - now;
                long cooldownMillis = divorceRemindCooldownMillis();
                if (cooldownMillis > 0 && record.divorceRemindedAt > 0
                        && now - record.divorceRemindedAt < cooldownMillis) {
                    // 提醒冷却内：静默（"还差几分钟"的那几分钟不该被反复催出群消息）
                    log.debug("今日老婆-离婚：群 {} 用户 {} 冷静期提醒冷却中，静默忽略", groupId, qqUid);
                    return;
                }
                record.divorceRemindedAt = now;
                reply = MsgUtils.builder().at(qqUid)
                        .text(" 还没到时间呢，冷静期还剩 " + humanDuration(left)
                                + "（" + clockAt(record.divorceWaitUntil) + " 之后发「离婚」才能离）")
                        .build();
            } else {
                // ---- 第 ③ 种：冷静期已到 ⇒ 正式离婚 ----
                Long exWife = record.wifeId;
                record.wifeId = null;
                record.divorceWaitUntil = 0L;
                record.divorceRemindedAt = 0L;
                // 当天次数归零：离婚的唯一意义就是"今天可以重新抽"
                record.count = 0;
                // 已抽满的提示标记也要复位，否则重抽被拦时不会再提示
                record.notified = false;
                reply = MsgUtils.builder().at(qqUid)
                        .text(" 离婚成功！今天和你过的是 ")
                        .at(exWife)
                        .text("，缘尽于此……\n现在可以重新抽「今日老婆」了~")
                        .build();
                log.info("今日老婆-离婚：群 {} 用户 {} 离婚成功，前妻 {}", groupId, qqUid, exWife);
            }
        }
        bot.sendMsg(event, reply, false);
    }

    /**
     * 「撤回离婚」：撤销还没确认的离婚申请（冷静期作废）。
     *
     * <p><b>不退还</b>当天已消耗的申请次数 —— 否则"申请→撤回"就是免费的，
     * {@code 逐次递增} 会被彻底绕过（每次撤回都回到最短冷静期）。
     */
    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "^" + CMD_PREFIX + "撤回离婚\\s*$", at = AtEnum.BOTH)
    public void cancelDivorce(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();
        if (Objects.isNull(groupId)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 该功能仅限群聊使用哦~").build(), false);
            return;
        }
        DailyDraw record = existingRecord(groupId, qqUid);
        if (Objects.isNull(record)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 你还没有在办离婚呢~").build(), false);
            return;
        }
        synchronized (record) {
            if (record.day != todayEpochDay() || record.divorceWaitUntil == 0L) {
                bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 你还没有在办离婚呢~").build(), false);
                return;
            }
            record.divorceWaitUntil = 0L;
            record.divorceRemindedAt = 0L;
        }
        bot.sendMsg(event, MsgUtils.builder().at(qqUid)
                .text(" 离婚申请已撤回，继续和你老婆过日子吧~（申请次数不退哦）")
                .build(), false);
    }

    // ==================== 离婚冷静期：参数与工具 ====================

    /** 离婚功能总闸（{@code wifeDivorceEnabled}，默认开，热更）。 */
    private boolean divorceEnabled() {
        return loadDSConfig.isEnabled(LoadDSConfig.KEY_WIFE_DIVORCE_ENABLED,
                LoadDSConfig.DEFAULT_WIFE_DIVORCE_ENABLED != 0);
    }

    /** 每人每天最多发起几次离婚申请（{@code ≤0} 不限）。 */
    private int divorceMaxPerDay() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_WIFE_DIVORCE_MAX_PER_DAY,
                LoadDSConfig.DEFAULT_WIFE_DIVORCE_MAX_PER_DAY);
    }

    /** 冷静期内提醒冷却（毫秒）；{@code 0} = 不冷却。 */
    private long divorceRemindCooldownMillis() {
        return minutesToMillis(loadDSConfig.intOf(LoadDSConfig.KEY_WIFE_DIVORCE_REMIND_COOLDOWN_MINUTES,
                LoadDSConfig.DEFAULT_WIFE_DIVORCE_REMIND_COOLDOWN_MINUTES));
    }

    /**
     * 第 {@code filedCount} 次申请（从 0 数）要等多久。<b>逐次翻倍、封顶</b>。
     *
     * <p>{@code min(基准 << filedCount, 上限)} —— 默认 30 → 60 → 120 → 240 …（封顶 1440 分钟）。
     * <ul>
     *   <li>基准 {@code ≤0} 视为 1 分钟（防成 0 分钟 = 没有冷静期）；</li>
     *   <li>移位前先夹住 {@code filedCount}（{@code >>63} 这类会得到 0 而不是溢出）；</li>
     *   <li>上限 {@code >0} 时封顶，且不会低于基准（基准配得比上限还大时以基准为准）。</li>
     * </ul>
     */
    private long divorceWaitMillis(int filedCount) {
        long base = minutesToMillis(Math.max(1, loadDSConfig.intOf(
                LoadDSConfig.KEY_WIFE_DIVORCE_BASE_MINUTES,
                LoadDSConfig.DEFAULT_WIFE_DIVORCE_BASE_MINUTES)));
        long max = minutesToMillis(loadDSConfig.intOf(
                LoadDSConfig.KEY_WIFE_DIVORCE_MAX_MINUTES,
                LoadDSConfig.DEFAULT_WIFE_DIVORCE_MAX_MINUTES));
        long millis = base << Math.min(31, Math.max(0, filedCount));
        if (max > 0 && millis > max) {
            millis = Math.max(base, max);
        }
        return millis;
    }

    private static long minutesToMillis(int minutes) {
        return minutes <= 0 ? 0L : minutes * 60_000L;
    }

    /** 把毫秒差说成人话（"1 小时 5 分钟" / "3 分钟"）。向上取整到分钟，避免"还剩 0 分钟"。 */
    private static String humanDuration(long millis) {
        long totalMinutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return minutes > 0 ? hours + " 小时 " + minutes + " 分钟" : hours + " 小时";
        }
        return minutes + " 分钟";
    }

    /** 冷静期到点时刻（北京时间 {@code HH:mm}，跨天加 {@code 明天}）。 */
    private static String clockAt(long epochMillis) {
        ZonedDateTime at = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
        String clock = String.format("%02d:%02d", at.getHour(), at.getMinute());
        return at.toLocalDate().isAfter(LocalDate.now(ZONE)) ? "明天 " + clock : clock;
    }

    /**
     * 取<b>已存在</b>的当天记录；没有就返回 {@code null}（<b>不</b>创建）。
     *
     * <p>与 {@link #recordOf} 的区别是这里的调用方（离婚/撤回）<b>不该</b>因为被调用而留下状态 ——
     * 一个从没抽过老婆的人发「离婚」，不该在内存里留下一个空壳条目。
     */
    private DailyDraw existingRecord(Long groupId, Long qqUid) {
        Map<Long, DailyDraw> perUser = dailyDraws.get(groupId);
        return Objects.isNull(perUser) ? null : perUser.get(qqUid);
    }

    /**
     * 「今日老婆」每人每天可抽次数（{@code wifeDailyLimit}，热更）。
     *
     * @return 次数上限；{@code ≤0} 表示不限（旧行为）
     */
    private int dailyLimit() {
        return loadDSConfig.intOf(LoadDSConfig.KEY_WIFE_DAILY_LIMIT, LoadDSConfig.DEFAULT_WIFE_DAILY_LIMIT);
    }

    /** 当前"天"（按 {@link #ZONE} 切），用于跨天重置。 */
    private static long todayEpochDay() {
        return LocalDate.now(ZONE).toEpochDay();
    }

    /** 取（或懒建）某人在某群的当天记录；跨天重置在调用方的临界区里做。 */
    private DailyDraw recordOf(Long groupId, Long qqUid) {
        Map<Long, DailyDraw> perUser = dailyDraws.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        return perUser.computeIfAbsent(qqUid, k -> new DailyDraw());
    }

    /**
     * 超限时的回复。
     *
     * <p>{@code limit = 1}（每天固定）时回<b>同一位</b>，让"每天一位"这件事对用户是可见的；
     * 其余情况只回"抽满了"。
     */
    private String buildLimitMsg(Long qqUid, Long wifeId, int limit) {
        if (limit == 1 && !Objects.isNull(wifeId)) {
            return MsgUtils.builder()
                    .at(qqUid).text(" 今天的老婆已经定啦，还是 ")
                    .at(wifeId).text(" 哦（每人每天一次）")
                    .build();
        }
        return MsgUtils.builder()
                .at(qqUid).text(" 今天已经抽满 " + limit + " 次啦，明天再来吧~")
                .build();
    }

    /**
     * 定期清掉非当天的抽签记录；顺带把空掉的群条目一并摘掉，防 Map 只涨不降。
     *
     * <p>离婚状态（冷静期/申请次数）与 {@code day} 同源，所以"跨天作废"由这里和
     * {@code todayWife} 的跨天重置一起兜住，不需要额外的清理分支。
     */
    @Scheduled(fixedDelay = 3_600_000L)
    public void sweep() {
        try {
            long today = todayEpochDay();
            dailyDraws.values().forEach(perUser ->
                    perUser.entrySet().removeIf(e -> e.getValue().day != today));
            dailyDraws.entrySet().removeIf(e -> e.getValue().isEmpty());
        } catch (Exception e) {
            log.warn("今日老婆记录清扫异常", e);
        }
    }

    /**
     * 从候选成员里抽一位「老婆」，按<b>群轮转</b>保证公平。
     *
     * <p>算法（一轮 = 群里每个人恰好一次）：
     * <ol>
     *   <li>把已经退群的人从本轮记录里剔掉（否则本轮永远走不完）；</li>
     *   <li>本轮记录已覆盖当前全部候选 ⇒ 说明一轮走完了，清空重洗；</li>
     *   <li>在「本轮还没被抽到过」的人里均匀随机取一个；</li>
     *   <li>若这次是<b>新一轮的第一次</b>，先把上一轮的收尾那位排除在本次抽取之外
     *       （他仍留在本轮的池子里，只是不第一个再出现）—— 这一条专门用来压低
     *       "连续两次同一个人"的观感。</li>
     * </ol>
     *
     * @param groupId    群号（轮转状态的键）
     * @param candidates 候选成员（已排除发起人与机器人）
     * @return 抽中的成员
     */
    private GroupMemberInfoResp pickWife(Long groupId, List<GroupMemberInfoResp> candidates) {
        GroupRotation rotation = rotations.computeIfAbsent(groupId, k -> new GroupRotation());
        synchronized (rotation) {
            // 1) 退群的人不再占着"本轮名额"
            if (!rotation.servedThisRound.isEmpty()) {
                Set<Long> alive = new HashSet<>(candidates.size() * 2);
                for (GroupMemberInfoResp member : candidates) {
                    alive.add(member.getUserId());
                }
                rotation.servedThisRound.retainAll(alive);
            }

            // 2) 本轮的人已经走完 ⇒ 新的一轮
            if (rotation.servedThisRound.size() >= candidates.size()) {
                rotation.servedThisRound.clear();
            }

            // 3) 本轮还没被抽到过的人
            List<GroupMemberInfoResp> remaining = new ArrayList<>(candidates.size());
            for (GroupMemberInfoResp member : candidates) {
                if (!rotation.servedThisRound.contains(member.getUserId())) {
                    remaining.add(member);
                }
            }
            // 兜底：理论上不会为空（上面刚清过），真为空也不让功能挂掉
            if (remaining.isEmpty()) {
                remaining = new ArrayList<>(candidates);
                rotation.servedThisRound.clear();
            }

            // 4) 新一轮的第一抽：先把上一轮最后那位挪出"这一次"的选择范围
            if (rotation.servedThisRound.isEmpty() && remaining.size() > 1
                    && !Objects.isNull(rotation.lastDrawn)) {
                List<GroupMemberInfoResp> withoutLast = new ArrayList<>(remaining.size());
                for (GroupMemberInfoResp member : remaining) {
                    if (!rotation.lastDrawn.equals(member.getUserId())) {
                        withoutLast.add(member);
                    }
                }
                // withoutLast 必然非空（remaining.size() > 1 时才进来）
                if (!withoutLast.isEmpty()) {
                    remaining = withoutLast;
                }
            }

            GroupMemberInfoResp wife = remaining.get(ThreadLocalRandom.current().nextInt(remaining.size()));
            rotation.servedThisRound.add(wife.getUserId());
            rotation.lastDrawn = wife.getUserId();
            return wife;
        }
    }

    /**
     * 获取可作为「老婆」的候选群成员，排除发起人本人与机器人自己
     *
     * @param bot     机器人实例
     * @param groupId 群号
     * @param qqUid   发起人 QQ
     * @param selfId  机器人 QQ
     * @return 候选列表；获取失败时返回 null
     */
    private List<GroupMemberInfoResp> getCandidates(Bot bot, Long groupId, Long qqUid, long selfId) {
        List<GroupMemberInfoResp> candidates = new ArrayList<>();
        try {
            ActionList<GroupMemberInfoResp> memberList = bot.getGroupMemberList(groupId);
            if (Objects.isNull(memberList) || Objects.isNull(memberList.getData())) {
                log.error("获取群成员列表失败，群号：{}", groupId);
                return null;
            }
            for (GroupMemberInfoResp member : memberList.getData()) {
                if (Objects.isNull(member) || Objects.isNull(member.getUserId())) {
                    continue;
                }
                long memberId = member.getUserId();
                if (memberId == qqUid || memberId == selfId) {
                    continue;
                }
                candidates.add(member);
            }
        } catch (Exception e) {
            log.error("获取群成员列表异常，群号：{}", groupId, e);
            return null;
        }
        return candidates;
    }

    /**
     * 构建「今日老婆」消息：@发起人 老婆来咯~ + 老婆头像(base64) + @老婆 是你今日的老婆
     *
     * @param qqUid 发起人 QQ
     * @param wife  抽中的群成员
     * @return 消息字符串
     */
    private String buildWifeMsg(Long qqUid, GroupMemberInfoResp wife) {
        Long wifeId = wife.getUserId();
        try {
            String base64 = getAvatarBase64(wifeId);
            return MsgUtils.builder()
                    .at(qqUid).text(" 老婆来咯~\n")
                    .img("base64://" + base64)
                    .text("\n")
                    .at(wifeId).text(" 是你今日的老婆")
                    .build();
        } catch (Exception e) {
            // 头像获取失败时降级为纯文本，避免整个功能不可用
            log.error("获取老婆头像失败，qq：{}", wifeId, e);
            return MsgUtils.builder()
                    .at(qqUid).text(" 老婆来咯~\n")
                    .at(wifeId).text(" 是你今日的老婆")
                    .build();
        }
    }

    /**
     * 下载 QQ 头像并转为 base64
     *
     * @param qq QQ 号
     * @return 头像的 base64 编码
     * @throws IOException 下载或校验失败
     */
    private String getAvatarBase64(Long qq) throws IOException {
        URL url = new URL(String.format(QQ_AVATAR_URL, qq));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36");
        try (InputStream inputStream = connection.getInputStream()) {
            String contentType = connection.getContentType();
            if (Objects.isNull(contentType) || !contentType.startsWith("image/")) {
                throw new IOException("头像响应不是图片，Content-Type：" + contentType);
            }
            byte[] bytes = inputStream.readAllBytes();
            if (bytes.length == 0) {
                throw new IOException("头像内容为空");
            }
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            connection.disconnect();
        }
    }
}
