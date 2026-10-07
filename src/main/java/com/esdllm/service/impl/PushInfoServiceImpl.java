package com.esdllm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.esdllm.bilibiliApi.bilibiliApi.BilibiliClient;
import com.esdllm.bilibiliApi.bilibiliApi.CardInfo;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Live;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.common.BotAdminChecker;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.mapper.PushInfoMapper;
import com.esdllm.model.PushInfo;
import com.esdllm.model.respObj.PushInfoResp;
import com.esdllm.service.CredentialGuard;
import com.esdllm.service.PushInfoService;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* @author LiYehe
* @description 针对表【push_info】的数据库操作Service实现
* @createDate 2025-04-22 23:58:21
*/
@Service
@Slf4j
public class PushInfoServiceImpl extends ServiceImpl<PushInfoMapper, PushInfo>
    implements PushInfoService{
    private static final ThreadLocal<SimpleDateFormat> SAFE_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    /**
     * 每个订阅（{@code pid}）<b>已经推送过</b>的动态 ID 集合，用于去重。
     *
     * <p><b>为什么必须有</b>：动态推送的触发依据是「这条动态的发布时间文案很新」，
     * 而这类文案（如「刚刚」）会在约 1 分钟内持续命中 —— 在轮询间隔下，
     * 同一条新动态会被连续几轮都判成「新」，没有去重就会刷出好几条一模一样的推送。
     *
     * <p><b>为什么是集合而不是"最后一条"</b>（2026-09-14 真机日志抓到）：
     * 原来只记"最后推的那一条"，于是当 UP <b>连续发动态</b>时（实测某个 UP 每分钟一条）——
     * 第 N 轮推了 B，第 N+1 轮 B 被去重跳过、但又轮到上一轮推过的 A（仍在新鲜窗口内）→
     * <b>A 被重复推送</b>。只要"新鲜窗口内同时存在两条以上动态"，这个漏洞就会暴露。
     *
     * <p><b>为什么必须落库</b>（2026-09-14 真机日志实锤）：这个集合原来只在内存里，
     * 进程一重启就清空；而触发推送的依据是「这条动态的发布时间落在 {@link #RECENT_MINUTES}
     * 分钟窗口内」，于是<b>每次重启都会把上一轮已经推过的动态原样再推一遍</b>。
     * 实测：{@code dynamicId=1247605274155417607}（发布于 01:06）先被推过一次，
     * 01:12 重启后 01:13 又推了一次 —— 用户看到的现象就是"去重没生效"。
     * 重启在开发期很频繁（每次改完代码 {@code ./start.sh} 都算），所以这不是小概率事件。
     *
     * <p>现在每次推送后都会把整个映射写回 {@code config} 表的
     * {@link LoadDSConfig#KEY_PUSHED_DYNAMIC_IDS}，启动时再读回来（见 {@link #loadPushedIds}）。
     * 之所以放在既有的 {@code config} 键值表里而不是新建表：SQLite/MySQL 两套数据源都得建表，
     * 而这张表本来就是通用的 KV，加一个键零迁移、且用户可以直接用 SQL 查看/清空。
     */
    private final Map<Long, Set<String>> pushedDynamicIds = new ConcurrentHashMap<>();

    /**
     * 保护 {@link #pushedDynamicIds} 里每个 Set 的锁。
     *
     * <p><b>为什么不直接用 Set 自己当锁</b>（原来是 {@code synchronized (pushed)} /
     * {@code synchronized (ids)}）：那把锁的<b>身份是从 map 里取出来的</b> ——
     * {@link #loadPushedIds} 的 {@code putAll} 就能把 entry 的值换成新实例，
     * 于是两个线程各自锁在"不同的对象"上，互斥悄悄失效；
     * 而且 {@link #alreadyPushed} 那条读路径当时<b>根本没加锁</b>。
     * 换成一把固定对象的锁之后，纪律只剩一条：<b>碰这些 Set 就先进这把锁</b>。
     *
     * <p>粒度从"每个 Set 一把"变成"全局一把"是<b>故意的</b>：这几个操作都只在动态推送一轮里
     * 跑几十次、每次是纯内存操作，串行化的代价可以忽略；换来的是不必再逐点推敲
     * "这把锁和那把锁是不是同一个对象"。
     */
    private final Object pushedIdsLock = new Object();

    /**
     * 进程启动时刻（毫秒），只用于冷启动判定，见 {@link #isColdStartBacklog}。
     */
    private final long bootTimeMillis = System.currentTimeMillis();

    /**
     * <b>冷启动订阅</b>：进程启动时就存在、但库里<b>没有</b>它们的去重记录的订阅（{@code pid}）。
     *
     * <p>只可能是两种情况：① 本特性首次部署；② 去重记录被清空。这两种情况下我们"失忆"了，
     * 无法知道 15 分钟窗口里的动态有没有推过 —— 此时的选择是<b>不回补</b>（把它们静默记为已推），
     * 因为"重启就重刷一遍旧动态"正是用户投诉的问题，而漏推一条旧动态的代价小得多。
     *
     * <p>注意<b>不含</b>之后才新增的订阅：新订阅的语义是"从现在起有动静就告诉我"，
     * 它的第一轮该推什么就推什么，不受这里影响（见 {@link #loadPushedIds}）。
     */
    private final Set<Long> coldStartPids = ConcurrentHashMap.newKeySet();

    /** 去重映射有变化、还没写回库里（见 {@link #persistPushedIdsIfDirty}） */
    private volatile boolean pushedStateDirty = false;

    /**
     * 每个订阅最多记住多少条已推动态（超出后按插入顺序淘汰最旧的）。
     *
     * <p>关注流首页只有 ~22 条，200 远超"新鲜窗口内可能出现的条数"，实际不会触发淘汰；
     * 设上限只是为了防止长期运行后无限增长。
     */
    private static final int PUSHED_HISTORY_PER_SUB = 200;

    /** 「N 分钟前」的匹配器，配合 {@link #RECENT_MINUTES} 使用，见 {@link #isFresh(String)} */
    private static final Pattern MINUTES_AGO = Pattern.compile("^(\\d+)分钟前");

    /**
     * 动态推送的「新鲜度」窗口（分钟）。
     *
     * <p>见 {@link #isFresh(String)}：放宽到 N 分钟是为了补回被轮询间隔甩掉的新动态。
     *
     * <p><b>必须大于风控冷却上限</b>（见 {@link #RISK_COOLDOWN_MAX_MS}）：否则冷却期间发出的动态，
     * 等冷却结束回来时已经"超过 N 分钟"，会被判成不新鲜而<b>永久漏推</b>。
     * 末次去重（{@link #pushedDynamicIds}）保证窗口放宽不会变成重复刷屏。
     */
    private static final int RECENT_MINUTES = 15;

    /** 风控冷却的起始时长（毫秒） */
    private static final long RISK_COOLDOWN_BASE_MS = 60_000L;
    /**
     * 风控冷却的上限（毫秒）。
     *
     * <p><b>刻意压在 {@link #RECENT_MINUTES} 分钟以内</b>：冷却期间发出的动态，
     * 恢复后仍然落在新鲜窗口里，所以「退避」不会变成「漏推」。
     */
    private static final long RISK_COOLDOWN_MAX_MS = 8 * 60_000L;

    /**
     * 风控冷却截止时间戳（毫秒）。大于当前时间表示还在冷却中，本轮直接跳过。
     *
     * <p><b>为什么必须有</b>：实测 B 站的 412 是<b>惩罚窗口</b>行为 —— 短时间内对
     * {@code api.bilibili.com} 连发几个请求就被判 412，之后一段时间内<b>所有</b>请求继续 412。
     * 动态推送原本是"每 40 秒拉一次，失败就轮换身份并立刻重试"，等于<b>每轮主动把窗口续期</b>，
     * 结果就是永久 412（2026-09-13 真机日志实测：连续多轮 100% 412）。
     * 冷却让出口 IP 真正安静下来，窗口才会过期。
     */
    private volatile long riskCooldownUntil = 0L;

    /**
     * 连续失败的轮数，用于让冷却时长递增（1 → 2 → 4 → 8 分钟封顶）。
     *
     * <p><b>为什么是 {@link AtomicInteger} 而不是 {@code volatile int}</b>：这个字段要做
     * {@code min(v + 1, 16)} 这种<b>读-改-写</b>，而 {@code volatile} 只保证可见性、
     * <b>不保证复合操作原子</b> —— 写出来是"看着线程安全、其实不是"。
     * 目前写入确实被动态推送的重入闸（{@code BiliBiliPushPlugins#dynamicPushRunning}）收敛成单写者，
     * 但那是<b>另一个类的调用约定</b>：{@link #dynamicPush} 是 public 接口方法，
     * 换个入口（手动触发、测试、将来的命令）就能并发进来，不该把正确性押在那上面。
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 已经打过完整堆栈的失败轮数计数（见 {@link #logFetchFailure}）。
     *
     * <p>风控持续期间每轮都会失败，若每次都打 20 行堆栈，日志会被淹掉、真正有用的
     * "出站身份 / 策略"那几行反而看不见。所以只在第 1 次和每 10 次打完整堆栈，其余打一行。
     *
     * <p>同样用 {@link AtomicInteger} 而非 {@code volatile int}（理由见 {@link #consecutiveFailures}）：
     * 它要自增，而且"自增后的值"必须与随后打日志用的值是<b>同一个</b>。
     */
    private final AtomicInteger failuresLogged = new AtomicInteger(0);


    /**
     * 是否改用<b>关注流</b>（{@code feed/all}）作为动态数据源。
     *
     * <p><b>为什么需要</b>（2026-09-14 真机实测）：B 站 WAF 会按客户端封禁
     * {@code x/polymer/web-dynamic/v1/feed/space} —— 同机同 Cookie 下
     * {@code x/frontend/finger/spi} 返回 200、{@code feed/all} 返回 200/code=0，
     * 只有 feed/space 返回 {@code {"code":-412,"message":"request was banned"}}。
     * 换 buvid、换请求头形状、拉长间隔都无效（不是频率问题，是"这条路被封"）。
     *
     * <p>一旦命中就<b>粘住</b>（重启后重新探测）：关注流一轮 1 次请求覆盖所有已关注 UP，
     * 比原来"每个 uid 1 次"更省请求，且实测在被封的机器上可用。
     *
     * <p>代价：关注流只包含该 B 站账号<b>已关注</b>的 UP；未关注的订阅会推不到，
     * 由 {@link #warnUidsMissingFromFollowFeed} 告警提示。
     */
    private volatile boolean preferFollowFeed = false;

    /** 上次提醒"某 UP 不在关注流里"的时间戳，避免每轮刷屏 */
    private volatile long lastMissingUidWarnAt = 0L;

    /** 「不在关注流里」告警的最小间隔（毫秒） */
    private static final long MISSING_UID_WARN_INTERVAL_MS = 30 * 60_000L;

    /** 上次应用过的「数据源偏好」配置值，只在配置变化时重新应用（见 {@link #applyConfiguredSource}） */
    private volatile String appliedSourceConfig = null;

    /**
     * 关注流模式下，多久回探一次 {@code feed/space}（毫秒）。
     *
     * <p><b>为什么需要回探</b>：切关注流的原因不是"接口不能用"，而是
     * <b>这条路径对该客户端被封</b> —— 真机对照（2026-09-14，同一枚 Cookie、同一分钟、同一客户端）：
     * 住宅出口 {@code HTTP 200 / code=0 / 13 条}，服务器香港出口
     * {@code HTTP 412 {"code":-412,"message":"request was banned"}}。
     * 这种封禁不会永久有效，但它也不会通知我们，所以每 30 分钟花<b>一个请求</b>去敲门：
     * 通了就切回空间动态（语义更准，且不受"账号必须已关注该 UP"的限制）。
     */
    private static final long SPACE_FEED_PROBE_INTERVAL_MS = 30 * 60_000L;

    /**
     * 下次回探 {@code feed/space} 的时间戳。
     *
     * <p>{@link Long#MAX_VALUE} 表示"永不回探"—— 用于配置里显式写了
     * {@code biliDynamicSource=follow} 的场景（用户明确要求用关注流，就别自作主张切回去）。
     */
    private volatile long nextSpaceFeedProbeAt = 0L;

    @Resource
    PushInfoMapper pushInfoMapper;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private LoadDSConfig loadDSConfig;
    /**
     * B 站凭据状态的探测器（P0-1）。
     *
     * <p>只在这一轮<b>确实拉取失败</b>时才被调用 —— 常态（Cookie 有效）下
     * 一轮推送的请求数与以前<b>完全一致</b>。
     */
    @Resource
    private CredentialGuard credentialGuard;

    @Override
    public PushInfoResp pushAdd(Long roomId, AnyMessageEvent event) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        return pushAdd(roomId, event, 0, 0);
    }

    @Override
    public PushInfoResp pushAdd(Long roomId, AnyMessageEvent event, Integer livePush, Integer dynamicPush) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        PushInfoResp resp = new PushInfoResp();
        if (hasPush(roomId, event.getUserId(), event.getGroupId())) {
            resp.setHas(true);
            return resp;
        }
        resp.setHas(false);

        Long uid = getUid(roomId);
        String userName = getUserName(uid);
        resp.setName(Objects.isNull(userName) ? "未知" : userName);

        int[] pushSettings = parsePushSettings(livePush, dynamicPush);
        int livePushStatus = pushSettings[0];
        int dynamicPushStatus = pushSettings[1];

        PushInfo pushInfo = createPushInfo(roomId, event.getUserId(), event.getGroupId(), livePushStatus, dynamicPushStatus);
        boolean save = this.save(pushInfo);

        if (save) {
            resp.setLivePush(livePushStatus == 0);
            resp.setDynamicPush(dynamicPushStatus == 0);
            return resp;
        }
        return null;
    }

    @Override
    public boolean pushDel(AnyMessageEvent event) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        LambdaQueryWrapper<PushInfo> queryWrapper = new LambdaQueryWrapper<>();
        setWrapper(event.getUserId(), event.getGroupId(), queryWrapper);
        try {
            List<PushInfo> list = this.list(queryWrapper);
            if (list.size() == 1){
                return remove(queryWrapper);
            }else if (list.isEmpty()){
                throw new RuntimeException("没有订阅任何房间");
            }else {
                throw new RuntimeException("请指定房间号");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean pushDel(AnyMessageEvent event, Long roomId) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        LambdaQueryWrapper<PushInfo> queryWrapper = getWrapper(roomId, event.getUserId(), event.getGroupId());
        try {
            int size = pushInfoMapper.selectList(queryWrapper).size();
            if (size == 1){
                return remove(queryWrapper);
            }else {
                throw new RuntimeException("房间号不正确");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直播推送
     * @param bot 机器人对象
     */
    @Override
    public void livePush(Bot bot) {
        List<PushInfo> list = pushInfoMapper.selectList(null);
        if (list.isEmpty()) {
            return;
        }
        // 创建一次对象，避免在循环中重复创建
        Live live = new Live();
        CardInfo cardInfo = new CardInfo();

        // ★ 一轮内「房间 → 直播间信息」缓存。
        //
        // 上游 Live 门面的每个 getter 都是一次真实 HTTP：它们各自调
        // LiveService.INSTANCE.load(roomId)，而 load 每次都发请求（门面注释 §6.4 明确写了
        // "本门面不提供跨调用的实例缓存"，并要求调用方 getLiveRoom() 取一次再分发）。
        // 所以原来推一条开播消息要打 6 次同一个接口：
        //   getLiveStatus / getUid / getLiveTime / getLiveTitle / getLiveArea / getImageUrl
        // 同一房间被 N 个群/私聊订阅时更是 ×N —— 而 B 站的 412 是「请求密度敏感」型风控
        // （真机实测 1 秒内 3 个请求即触发），这种打法本身就在招风控。
        // 缓存后：同一房间一轮只 1 次请求，其余全部读内存里的对象。
        Map<Long, LiveRoom> roomCache = new HashMap<>();
        // ★ 同一「目标」（群 / 私聊）一轮内只发一条，见 firstForTarget
        Set<String> sentTargets = new HashSet<>();

        for (PushInfo pushInfo : list) {
            // 0 = 开启直播推送（模型默认值 1 是"关闭"，见 pushAdd 传 0）
            if (!Objects.equals(pushInfo.getLivePush(), 0)) {
                continue;
            }
            try {
                Long roomId = pushInfo.getRoomId();
                LiveRoom room = roomCache.get(roomId);
                if (room == null) {
                    try {
                        room = live.getLiveRoom(roomId);
                    } catch (Throwable t) {
                        // 该房间本轮取不到信息：跳过它的所有订阅（其余订阅不受影响）。
                        // 不缓存失败结果 —— 直播推送每 10 秒一轮，下一轮自然会重试。
                        log.error("获取直播间信息失败，本轮跳过该订阅，房间ID: {}", roomId, t);
                        continue;
                    }
                    roomCache.put(roomId, room);
                }

                // ⚠️ LiveRoom.live_status 是 Integer 且可能为 null
                //   （门面的 getLiveStatus 返回 int，自动拆箱会直接 NPE —— 自己取字段就得自己兜）
                Integer currentLiveStatus = room.getLive_status();
                if (currentLiveStatus == null) {
                    log.warn("直播间 {} 未返回 live_status，本轮跳过", roomId);
                    continue;
                }
                // 库里 live_status 可能是 NULL（历史数据 / 手工插入），同样不能直接拆箱
                int savedStatus = pushInfo.getLiveStatus() == null ? 0 : pushInfo.getLiveStatus();

                // ★★ 通知判据 = 「在播 / 不在播」这个**二值语义**的翻转，而不是原值本身。
                //
                //   B 站 live_status 有三种取值：0 未开播 / 1 直播中 / 2 轮播中，
                //   而主播下播后房间**通常直接进 2（轮播）**并长时间停在 2。
                //
                //   🔴 2026-10-07 修的线上 bug（"下播的时候会发好多消息"）：
                //   ① 判据原来是"原值变了就发一条"，而轮播分支的守卫写成了 `savedStatus == 0` ——
                //      正确的守卫是"**我们之前认为他在播**"= `savedStatus == 1`。10-02 那次把
                //      "0↔1 翻转"改成"写回服务端真实值"之后，轮播房间的库值变成 2 ⇒ 守卫永远
                //      不成立 ⇒ 房间一进轮播就**每 10 秒推一条「下播了」，永不停止**
                //      （真机三个订阅房间当时全是 live_status=2，等于常驻刷屏）。
                //   ② `buildMessage` 当时用库里的原值反推"开播/下播"，于是「轮播 → 直播」
                //      会被判成「下播」，新的一场永远等不到开播通知。
                boolean nowLive = currentLiveStatus == 1;
                boolean wasLive = savedStatus == 1;

                // 场次标识：开播时服务端给真实开播时刻（本方法存进 live_time），
                // 轮播 / 未开播恒为 "0000-00-00 00:00:00"（被解析成 null）。
                // 用它区分「新的一场」与「同一场的重复观测 / 抖动」，
                // 从而保证 **一场直播最多一条「开播」+ 最多一条「下播」**。
                Long sessionStart = parseLiveStartMillis(room.getLive_time());

                if (nowLive) {
                    if (wasLive) {
                        // 一直在播：同一场不会有第二条「开播」
                        continue;
                    }
                    if (sessionStart != null && sessionStart.equals(pushInfo.getLiveTime())) {
                        // 本场（开播于 sessionStart）已经处理过 —— 要么「开播」已发，
                        // 要么「下播」已发而服务端又报直播中（轮播接手的抖动）。
                        // ⚠️ 这里**刻意不回写 live_status=1**：一旦回写，下一轮服务端再报
                        //   不在播时 wasLive 又为真，就会再发一条「下播」—— 抖动被重新放大。
                        log.info("直播推送：roomId={} 本场（开播于 {}）已处理过，服务端又报直播中，按抖动忽略",
                                roomId, sessionStart);
                        continue;
                    }
                    String message = buildMessage(bot, pushInfo, room, cardInfo, live, true);
                    // 先把状态写库（占位），再发消息 —— 缩短"读到旧状态"的窗口，见 updatePushInfoStatus
                    updatePushInfoStatus(pushInfo, currentLiveStatus);
                    if (firstForTarget(sentTargets, pushInfo)) {
                        logLivePush(pushInfo, room, savedStatus, currentLiveStatus);
                        sendMessage(bot, pushInfo, message);
                    }
                    continue;
                }

                // —— 不在播（0 未开播 / 2 轮播中 / 其它）——
                if (!wasLive) {
                    // 本来就不在播：0↔2（轮播起停）怎么动都不该发消息，
                    // 只把原值对齐一下便于排查，绝不推送。
                    if (!Objects.equals(currentLiveStatus, savedStatus)) {
                        updatePushInfoStatus(pushInfo, currentLiveStatus);
                    }
                    continue;
                }

                // 在播 → 不在播：**整场只发这一条「下播」**。
                // ⚠️ live_time 保持本场开播时刻不动（它是场次标识，见上），
                //    所以服务端随后再抖回 1 会被上面的场次判断挡住，不会重复推送。
                String sendMsg = buildMessage(bot, pushInfo, room, cardInfo, live, false);
                // ★ 顺序是「构建 → 写库 → 发送」，与原实现一致，但写库改用按字段更新。
                //   先占位写库是关键：否则"读到旧状态"到"写回新状态"之间的窗口 = 1 次
                //   直播间请求 + 1 次名片请求，慢一点就会让下一轮看到旧状态而重推。
                updatePushInfoStatus(pushInfo, currentLiveStatus);
                if (firstForTarget(sentTargets, pushInfo)) {
                    logLivePush(pushInfo, room, savedStatus, currentLiveStatus);
                    sendMessage(bot, pushInfo, sendMsg);
                }
            } catch (Throwable e) {
                // ★ 兜 Throwable：与 dynamicPush 同一口径（无字体环境下 Java2D 抛的是
                //   java.lang.InternalError，是 Error 不是 Exception）。
                //   单个推送失败不应影响其他推送。
                log.error("处理推送信息时发生异常，房间ID: " + pushInfo.getRoomId(), e);
            }
        }
    }

    /**
     * 同一轮里，这个「推送目标」是不是第一次要发。
     *
     * <p><b>为什么需要</b>：{@code push_info} 的去重口径是「房间 + 发命令的人 + 目标」，
     * 所以同一个群里 A、B 两人各发一次「添加订阅 &lt;同一个房间&gt;」会产生<b>两条记录</b>；
     * 而发送只看 {@code group_id}（见 {@link #sendMessage}）⇒ <b>群里连收两条一模一样</b>的开播通知。
     *
     * <p>这里有意识地收敛在「发送侧」而不去动数据：改去重口径会连带改掉
     * {@code 取消订阅} 的语义（现在每人只能取消自己那条），风险远大于收益。
     *
     * <p>注意集合是<b>每轮新建</b>的 —— 只做"一轮内不重复"，不影响下一轮的正常推送。
     *
     * @param sentTargets 本轮已发过的目标集合
     * @param pushInfo    当前订阅
     * @return {@code true} 表示本目标是本轮第一条（可以发）
     */
    private static boolean firstForTarget(Set<String> sentTargets, PushInfo pushInfo) {
        String key = pushInfo.getGroupId() != null
                ? "g:" + pushInfo.getGroupId()
                : "p:" + pushInfo.getQqUid();
        return sentTargets.add(key);
    }

    /**
     * 规则化打一行直播推送日志。
     *
     * <p>刻意与动态推送的 {@code 推送动态：uid=..., dynamicId=...} 同一形态 ——
     * 出问题时可以直接 grep 这一行统计"同一事件推了几次"，不必去翻图或猜。
     */
    private void logLivePush(PushInfo pushInfo, LiveRoom room, int from, Integer to) {
        log.info("推送直播：roomId={}, uid={}, 状态 {}→{}, 目标={}",
                pushInfo.getRoomId(), room.getUid(), from, to,
                pushInfo.getGroupId() != null ? "群" + pushInfo.getGroupId()
                        : "私聊" + pushInfo.getQqUid());
    }

    @Override
    public void dynamicPush(Bot bot) {
        // 风控冷却中：直接跳过这一轮，别再去续期惩罚窗口
        long now = System.currentTimeMillis();
        if (now < riskCooldownUntil) {
            log.info("动态推送处于风控冷却中，还需 {} 秒（让 B 站惩罚窗口自然过期，避免越打越死）",
                    (riskCooldownUntil - now) / 1000);
            return;
        }

        List<PushInfo> list = pushInfoMapper.selectList(null);
        // 配置表里的数据源偏好（auto/follow/space），改了即时生效
        applyConfiguredSource();
        // 创建一次对象，避免在循环中重复创建
        Live liveRoom = new Live();
        CardInfo cardInfo = new CardInfo();
        Dynamic dynamic = new Dynamic();

        // 同一个 UP（uid）往往被多个群/私聊同时订阅。若每条订阅都独立拉一次 feed，
        // 一轮内就会对 B 站重复请求 N 次，既浪费也更易撞上 -352 风控。
        // 这里按 uid 做「一轮内」缓存：同一 uid 只请求一次；
        // ★ 拉取失败也写缓存（空列表），否则同一 uid 的其它订阅会在同一轮里继续重试，
        //   把一次风控放大成 N 次。
        Map<Long, List<Dynamic.DynamicInfo>> feedCache = new HashMap<>();
        boolean anyFetchFailure = false;

        // ★ 一轮内「房间 → uid」缓存。Live 门面的每个 getter 都是一次真实 HTTP（见 livePush 的注释），
        //   而这里原先是每条订阅调一次 getUid(roomId) —— 同一个房间被 N 个群/私聊订阅就白白打 N 次。
        //   取不到时**不写缓存**，让下一轮的其它订阅重新试（失败是偶发的，缓存失败值反而会漏推）。
        Map<Long, Long> uidCache = new HashMap<>();

        // —— 关注流模式（见 #preferFollowFeed）：整轮只拉一次，全部订阅共用 ——
        // null 表示"本轮还没拉"；拉失败后置 followFeedFailed，避免同一轮里重复撞
        Map<Long, List<Dynamic.DynamicInfo>> followByUid = null;
        boolean followFeedFailed = false;
        // 本轮涉及的订阅 uid，用于"某 UP 没出现在关注流里"的告警（关注流只含已关注的 UP）
        Set<Long> subscribedUids = new HashSet<>();

        for (PushInfo pushInfo : list) {
            if (!Objects.equals(pushInfo.getDynamicPush(), 0)) {
                continue;
            }
            try {
                // 注意：取 uid 也可能抛异常，必须放在 try 内；留在外面会让一条订阅的失败
                // 直接中断整个 for 循环，后面的订阅全部不再推送。
                Long roomId = pushInfo.getRoomId();
                Long uid = uidCache.get(roomId);
                if (uid == null) {
                    uid = liveRoom.getUid(roomId);
                    if (uid != null) {
                        uidCache.put(roomId, uid);
                    }
                }
                if (uid == null) {
                    log.warn("直播间 {} 取不到 uid，跳过其动态推送", roomId);
                    continue;
                }
                subscribedUids.add(uid);

                // ★ 关注流只是"feed/space 被封时的替代品"，不是永久选择。定期回探一次：
                //   通了就切回空间动态（语义更准，也不要求"该账号必须已关注这个 UP"）。
                //   一轮最多回探一次（markSpaceFeedProbed 会把下次时间推后）。
                List<Dynamic.DynamicInfo> probedSpaceFeed = null;
                if (preferFollowFeed && spaceFeedProbeDue()) {
                    markSpaceFeedProbed();
                    probedSpaceFeed = tryFetchSpaceFeed(uid);
                    if (probedSpaceFeed != null) {
                        preferFollowFeed = false;
                        feedCache.put(uid, probedSpaceFeed);
                    }
                }

                List<Dynamic.DynamicInfo> dynamicInfoList;
                if (preferFollowFeed) {
                    if (followByUid == null && !followFeedFailed) {
                        try {
                            followByUid = groupByUid(dynamic.getFollowFeed());
                            log.info("关注流已加载：{} 条动态，覆盖 {} 个 UP",
                                    followByUid.values().stream().mapToInt(List::size).sum(),
                                    followByUid.size());
                        } catch (Exception e) {
                            followFeedFailed = true;
                            anyFetchFailure = true;
                            logFetchFailure(uid, e);
                        }
                    }
                    if (followByUid == null) {
                        // 关注流没拉到手：本轮放弃（下面统一进冷却）
                        continue;
                    }
                    dynamicInfoList = followByUid.getOrDefault(uid, List.of());
                } else if (probedSpaceFeed != null) {
                    // 回探成功的那一次结果直接复用，避免同一 uid 在同一轮里请求两次
                    dynamicInfoList = probedSpaceFeed;
                } else {
                    dynamicInfoList = feedCache.get(uid);
                    if (dynamicInfoList == null) {
                        try {
                            dynamicInfoList = dynamic.getDynamicInfoList(String.valueOf(uid));
                        } catch (Exception e) {
                            dynamicInfoList = List.of();
                            logFetchFailure(uid, e);
                            if (looksLikeBlocked(e)) {
                                // ★ feed/space 被判 -412 是"这条路被封"，不是"环境暂时不稳"：
                                //   换 buvid、换请求头、拉长间隔都没用（真机实测），所以
                                //   ① 立刻切到关注流（本轮起后面的订阅就走新源）；
                                //   ② **不计入风控冷却** —— 冷却解决不了路径级封禁，只会白白推迟推送。
                                switchToFollowFeed(e);
                            } else {
                                // 真·瞬时失败（超时/网络）：本轮该 uid 放弃，并让下一轮退避
                                anyFetchFailure = true;
                            }
                        }
                        feedCache.put(uid, dynamicInfoList);
                    }
                }
                if (dynamicInfoList.isEmpty()) {
                    continue;
                }

                // 昵称优先取动态自带的（关注流里就有），省掉一次名片接口请求 —— 请求密度正是风控敏感项
                String username = firstNonBlank(dynamicInfoList.get(0).getUserName(), null);
                if (username == null) {
                    username = cardInfo.getUserName(uid);
                }
                for (Dynamic.DynamicInfo dynamicInfo : dynamicInfoList) {
                    if (!isFresh(dynamicInfo.getTime())) {
                        continue;
                    }
                    String dynamicKey = dynamicInfo.getDynamicId() != null
                            ? dynamicInfo.getDynamicId()
                            : dynamicInfo.getShareDynamicId();
                    if (dynamicKey == null) {
                        continue;
                    }
                    if (alreadyPushed(pushInfo.getPid(), dynamicKey)) {
                        // 这条已经推过了（同一动态会在「新鲜窗口」内连续几轮都命中），跳过
                        continue;
                    }
                    if (isColdStartBacklog(pushInfo.getPid(), dynamicInfo.getTime())) {
                        // 冷启动时的存量动态：库里没有去重记录，无法判断推没推过，
                        // 与其重推一遍（用户投诉的正是这个），不如静默记为已推、只等新动态。
                        log.debug("冷启动跳过存量动态：uid={}, dynamicId={}, time={}",
                                uid, dynamicKey, dynamicInfo.getTime());
                        markPushed(pushInfo.getPid(), dynamicKey);
                        continue;
                    }
                    log.info("推送动态：uid={}, dynamicId={}, time={}, 目标={}",
                            uid, dynamicKey, dynamicInfo.getTime(),
                            pushInfo.getGroupId() != null ? "群" + pushInfo.getGroupId()
                                    : "私聊" + pushInfo.getQqUid());
                    sendMsg(username, dynamic, dynamicInfo, bot, pushInfo);
                    markPushed(pushInfo.getPid(), dynamicKey);
                    break;
                }
            } catch (Throwable e) {
                // ★ 兜 Throwable 而不是 Exception：见 renderDynamicImage 的注释 ——
                //   无字体环境下的字体管理器初始化失败抛的是 java.lang.InternalError（Error），
                //   用 catch(Exception) 会让它在「发消息之前」穿出整轮推送，用户什么都收不到。
                //   一条订阅出问题不该带走整轮。
                log.error("处理动态推送信息时发生异常，房间ID: " + pushInfo.getRoomId(), e);
            }
        }

        // 关注流模式下提醒"哪些被订阅的 UP 不在关注流里"——用户需要去关注它们，否则永远推不到
        if (preferFollowFeed && followByUid != null && !followByUid.isEmpty()) {
            warnUidsMissingFromFollowFeed(subscribedUids, followByUid.keySet());
        }

        // 把一个轮次里产生的去重记录一次性写回库里 —— 重启后靠它避免重推（见 loadPushedIds）
        persistPushedIdsIfDirty();

        if (anyFetchFailure) {
            // ★ P0-1：失败即探测一次（事件驱动，常态零开销），把"笼统的失败"拆成两种根因：
            //   - 服务端不认这枚 Cookie（-101 未登录）⇒ 该重新登录；
            //   - 其它（超时 / 出口 412 / 路径封禁）⇒ 该按原逻辑退避。
            //   这两件事的处置完全相反，混在一起就是"排障时只能靠猜"。
            CredentialGuard.Status status = credentialGuard.probe(CredentialGuard.Reason.PUSH_FAILURE);
            if (status.isInvalid()) {
                // 🔴 凭据失效时**不进风控冷却**：冷却治的是"请求打得太密"，而这里的问题
                //    是"手里的凭据已经无效" —— 冷却既修不好它，还会白拖最多 8 分钟，
                //    并把真正的修法（重新登录）藏在一句"风控冷却中"后面。
                if (status.isStateChanged()) {
                    log.error("动态推送失败，且服务端判定 B 站 Cookie 已失效（{}）—— 本轮不进入风控冷却。"
                                    + "修法：私聊发「登录」扫码，或发「设置cookie SESSDATA:...」。"
                                    + "（注意：这与 -412 路径/出口被封不是一回事，换源与换代理都无效）",
                            status.getSummary());
                } else {
                    // 持续性失效不重复刷屏 —— 状态变化的告警已由 CredentialGuard 去重承担，
                    // 这里只需留一行可追溯的痕迹。
                    log.debug("动态推送失败，Cookie 仍处于失效状态（{}），本轮不进入风控冷却",
                            status.getSummary());
                }
            } else {
                enterRiskCooldown();
            }
        } else {
            // 整轮都拿到了列表 → 说明数据源是通的，把递增计数清零
            failuresLogged.set(0);
            // getAndSet：一次完成"取旧值 + 清零"，日志里打的就是<b>清零前</b>那个轮数
            //（原先先 if 读一次、再 log 读一次、再赋值，同一字段被访问三次）
            int recoveredAfter = consecutiveFailures.getAndSet(0);
            if (recoveredAfter != 0) {
                log.info("动态推送恢复正常（此前连续失败 {} 轮）", recoveredAfter);
            }
        }
    }

    /**
     * 应用配置表里的「数据源偏好」（{@link LoadDSConfig#KEY_BILI_DYNAMIC_SOURCE}）。
     *
     * <p>只在配置值<b>变化时</b>应用一次，这样 {@code auto} 模式下运行期自动切到关注流后不会被
     * 每轮重新拽回空间动态（那会变成"每轮白撞一次 feed/space"的抖动）。
     *
     * <ul>
     *   <li>{@code follow} → 直接用关注流（重启后不再白撞 feed/space）；</li>
     *   <li>{@code space} → 强制按 uid 拉空间动态（未被封的环境用）；</li>
     *   <li>{@code auto}/未配置 → 保持自动判断。</li>
     * </ul>
     */
    private void applyConfiguredSource() {
        if (loadDSConfig == null) {
            return;
        }
        String mode = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_DYNAMIC_SOURCE);
        String normalized = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase();
        if (normalized.equals(appliedSourceConfig)) {
            return;
        }
        appliedSourceConfig = normalized;
        switch (normalized) {
            case "follow" -> {
                preferFollowFeed = true;
                // 用户显式要求关注流 → 不再回探（免得把配置"改回去"了）
                nextSpaceFeedProbeAt = Long.MAX_VALUE;
                log.info("按配置 {}={} 使用「关注流」数据源（一轮 1 次请求覆盖所有已关注 UP）",
                        LoadDSConfig.KEY_BILI_DYNAMIC_SOURCE, normalized);
            }
            case "space" -> {
                preferFollowFeed = false;
                nextSpaceFeedProbeAt = 0L;
                log.info("按配置 {}={} 使用「空间动态」数据源（按 uid 逐个拉取）",
                        LoadDSConfig.KEY_BILI_DYNAMIC_SOURCE, normalized);
            }
            default -> log.info("数据源按自动判断（先试空间动态，被判风控则改用关注流）");
        }
    }

    /**
     * 把关注流的动态按发布者 uid 归组，便于按订阅取用。
     *
     * @param feed 关注流结果
     * @return uid → 该 UP 的动态（保持原顺序）
     */
    private static Map<Long, List<Dynamic.DynamicInfo>> groupByUid(List<Dynamic.DynamicInfo> feed) {
        Map<Long, List<Dynamic.DynamicInfo>> result = new LinkedHashMap<>();
        if (feed == null) {
            return result;
        }
        for (Dynamic.DynamicInfo info : feed) {
            if (info == null || info.getUid() == null) {
                continue;
            }
            try {
                result.computeIfAbsent(Long.parseLong(info.getUid()), k -> new ArrayList<>()).add(info);
            } catch (NumberFormatException e) {
                // uid 不是数字（异常形态）：跳过这条，不影响其它
                log.debug("关注流里出现非数字 uid，已跳过：{}", info.getUid());
            }
        }
        return result;
    }

    /**
     * 异常是否属于"这条路径被 B 站封了"。
     *
     * <p>真机实测的形态是 {@code HTTP 412} + 业务码 {@code -412} 与文案
     * {@code request was banned}（库把它转成带"HTTP 412，命中 B 站风控"的消息）。
     * 这类失败<b>重试、换指纹、换请求头都无用</b>，只能换数据源 —— 所以必须与"超时/网络抖动"区分开。
     *
     * @param e 异常
     * @return 是否为路径级封禁
     */
    private static boolean looksLikeBlocked(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase();
            if (lower.contains("412") || lower.contains("banned") || msg.contains("风控")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切到关注流数据源（本进程内粘性，但会按 {@link #SPACE_FEED_PROBE_INTERVAL_MS} 定期回探
     * {@code feed/space}，通了就切回去 —— 见 {@link #nextSpaceFeedProbeAt}）。
     *
     * <p>什么时候触发：{@code feed/space} 被 -412 封禁时（见 {@link #looksLikeBlocked}）。
     * 关注流的优势是一轮只发 1 次请求就覆盖所有已关注 UP，且实测在 feed/space 被封的机器上可用。
     *
     * @param cause 触发切换的异常
     */
    private void switchToFollowFeed(Exception cause) {
        if (preferFollowFeed) {
            return;
        }
        preferFollowFeed = true;
        // 刚被拒过，先隔一个间隔再回探（免得"每轮敲一次门"反而把封禁喂得更牢）
        nextSpaceFeedProbeAt = System.currentTimeMillis() + SPACE_FEED_PROBE_INTERVAL_MS;
        log.warn("feed/space 被判风控（{}）→ 本轮起改用「关注流」数据源（一轮 1 次请求覆盖所有 UP）。"
                + "注意：关注流只包含该 B 站账号【已关注】的 UP，未关注的订阅推不到；"
                + "之后每 {} 分钟会回探一次 feed/space，通了自动切回。",
                cause.getMessage(), SPACE_FEED_PROBE_INTERVAL_MS / 60000);
    }

    /** 是否到了回探 {@code feed/space} 的时间 */
    private boolean spaceFeedProbeDue() {
        return System.currentTimeMillis() >= nextSpaceFeedProbeAt;
    }

    /** 记下"刚回探过"，把下次回探推到 {@link #SPACE_FEED_PROBE_INTERVAL_MS} 之后 */
    private void markSpaceFeedProbed() {
        nextSpaceFeedProbeAt = System.currentTimeMillis() + SPACE_FEED_PROBE_INTERVAL_MS;
    }

    /**
     * 试拉一次空间动态（回探用）。
     *
     * <p>成功即视为"封禁已解除"，调用方会把数据源切回空间动态并直接复用本次结果。
     * <b>失败不记入风控冷却</b>：这里本来就是"主动敲门"，敲不开是预期内的。
     *
     * @param uid 用哪个 UP 试（取本轮第一个订阅的 uid 即可）
     * @return 拉到的动态列表；不可用时返回 {@code null}
     */
    private List<Dynamic.DynamicInfo> tryFetchSpaceFeed(Long uid) {
        try {
            List<Dynamic.DynamicInfo> list = new Dynamic().getDynamicInfoList(String.valueOf(uid));
            log.info("回探 feed/space 成功 → 切回「空间动态」数据源（uid={}，{} 条）",
                    uid, list == null ? 0 : list.size());
            return list == null ? List.of() : list;
        } catch (Throwable t) {
            log.info("回探 feed/space 仍不可用（{}），继续用关注流，{} 分钟后再试",
                    t.getMessage(), SPACE_FEED_PROBE_INTERVAL_MS / 60000);
            return null;
        }
    }

    /**
     * 提示"被订阅但不在关注流里"的 UP。同一组缺失 uid 最多每 {@value #MISSING_UID_WARN_INTERVAL_MS}ms 提醒一次，
     * 避免每轮刷屏。
     *
     * @param subscribed 本轮涉及的订阅 uid
     * @param present    关注流里实际出现的 uid
     */
    private void warnUidsMissingFromFollowFeed(Set<Long> subscribed, Set<Long> present) {
        List<Long> missing = new ArrayList<>();
        for (Long uid : subscribed) {
            if (!present.contains(uid)) {
                missing.add(uid);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMissingUidWarnAt < MISSING_UID_WARN_INTERVAL_MS) {
            return;
        }
        lastMissingUidWarnAt = now;
        log.warn("关注流里没有这些被订阅的 UP：{} —— 关注流只包含「该 B 站账号已关注」的 UP，"
                + "若其中有没关注的，请用该账号去关注（否则这些订阅推不到）；"
                + "已关注的 UP 只是最近没发动态时也会不出现，属正常。", missing);
    }

    /** 取第一个非空白字符串 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    /**
     * 记录一次拉取失败：<b>只在第 1 次与每 10 次打完整堆栈</b>，其余打一行摘要。
     *
     * <p>原因见 {@link #failuresLogged}：风控持续期间（真机上实测可以连续几小时 100% 412）
     * 每轮 20 行堆栈会把日志淹掉，反而看不到"出站身份""冷却中"这些真正能定位问题的行。
     * 而"这一轮又失败了"本身的信息量，一行就够了。
     *
     * @param uid 失败的 UP uid
     * @param e   异常
     */
    private void logFetchFailure(Long uid, Exception e) {
        // 自增与"取来用"必须是同一个值：原来写的是先 ++ 再读两次字段，
        // 读与读之间理论上可能被改写，日志里的"第 N 次"也可能对不上。
        int times = failuresLogged.incrementAndGet();
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (times == 1 || times % 10 == 0) {
            log.error("获取动态列表失败（第 {} 次），本轮跳过 uid={}（该 uid 的其它订阅一并跳过）",
                    times, uid, e);
        } else {
            log.warn("获取动态列表失败（第 {} 次），本轮跳过 uid={}：{}", times, uid, reason);
        }
    }

    /**
     * 进入风控冷却：连续失败时按 1 → 2 → 4 → 8 分钟递增（上限见 {@link #RISK_COOLDOWN_MAX_MS}）。
     *
     * <p>目的见 {@link #riskCooldownUntil} 的注释：一味按轮询间隔重试只会把 B 站的惩罚窗口
     * 一轮一轮续期，越打越死；退避才能让窗口过期。
     *
     * <p>上限必须<b>小于</b> {@link #RECENT_MINUTES}：否则冷却期间发出的动态会"超龄"，
     * 被 {@link #isFresh(String)} 判成不新鲜而永久漏推（这一点有注释约束，改参数时别只改一处）。
     */
    private void enterRiskCooldown() {
        // updateAndGet：自增（封顶 16）与取回是同一个原子操作；delay 与日志用<b>同一个</b>轮数，
        // 不再"读完再读一次"（原写法里 delay 用的是字段的第二次读取，理论上可能与日志里的不一致）
        int rounds = consecutiveFailures.updateAndGet(v -> Math.min(v + 1, 16));
        long delay = Math.min(RISK_COOLDOWN_BASE_MS << (rounds - 1), RISK_COOLDOWN_MAX_MS);
        riskCooldownUntil = System.currentTimeMillis() + delay;
        log.warn("动态推送连续失败 {} 轮，冷却 {} 秒后再试（让 B 站惩罚窗口自然过期；"
                        + "上限 {} 分钟，短于新鲜窗口 {} 分钟，所以不会因此漏推）",
                rounds, delay / 1000, RISK_COOLDOWN_MAX_MS / 60000, RECENT_MINUTES);
    }

    /**
     * 这条动态对该订阅是否已经推过。
     *
     * @param pid         订阅 ID
     * @param dynamicKey  动态 ID（转发动态用原动态 ID）
     * @return true 表示已推过，应跳过
     */
    private boolean alreadyPushed(Long pid, String dynamicKey) {
        Set<String> pushed = pushedDynamicIds.get(pid);
        if (pushed == null) {
            return false;
        }
        // ⚠️ 这条读路径原先<b>没有任何同步</b>：LinkedHashSet 不是线程安全的，
        //    而 markPushed 会在锁内 add/remove ⇒ 并发 contains 可能撞上正在改的链表。
        //    与 markPushed / serializePushedIds 共用同一把锁（见 #pushedIdsLock）。
        synchronized (pushedIdsLock) {
            return pushed.contains(dynamicKey);
        }
    }

    /**
     * 记下"已推送"，供后续轮次去重。超出 {@link #PUSHED_HISTORY_PER_SUB} 时淘汰最旧的一条。
     *
     * @param pid        订阅 ID
     * @param dynamicKey 动态 ID
     */
    private void markPushed(Long pid, String dynamicKey) {
        Set<String> pushed = pushedDynamicIds.computeIfAbsent(pid, k -> new LinkedHashSet<>());
        // 锁的是 #pushedIdsLock（固定对象），不是 pushed 本身 —— 理由见那个字段的注释：
        // 用 Set 当锁，锁的身份就跟着 map 里的值走，loadPushedIds 的 putAll 一换实例就失效。
        synchronized (pushedIdsLock) {
            if (pushed.size() >= PUSHED_HISTORY_PER_SUB) {
                Iterator<String> it = pushed.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            pushed.add(dynamicKey);
        }
        // 只置脏标记，真正的落库在整轮结束时做一次（见 persistPushedIdsIfDirty）——
        // 一轮里可能推多条（每个订阅一条），没必要每条都写一次库。
        pushedStateDirty = true;
    }

    /**
     * 启动时把去重记录从 {@code config} 表读回内存，并算出 {@link #coldStartPids}。
     *
     * <p>依赖 {@link LoadDSConfig} 已经完成自己的 {@code @PostConstruct}（读表进内存）——
     * 这一点由 Spring 的依赖顺序保证：本类注入了 {@code LoadDSConfig}，
     * 它必须先初始化完才会轮到本类。
     */
    @PostConstruct
    public void loadPushedIds() {
        String raw = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_PUSHED_DYNAMIC_IDS);
        Map<Long, Set<String>> loaded = parsePushedIds(raw);
        // 启动期其实还没有并发访问，仍然持锁 —— 目的是让"碰这些 Set 就先进这把锁"
        // 成为一条<b>没有例外</b>的纪律，而不是"大部分地方记得加锁"。
        synchronized (pushedIdsLock) {
            pushedDynamicIds.putAll(loaded);
        }

        for (PushInfo pushInfo : pushInfoMapper.selectList(null)) {
            Long pid = pushInfo.getPid();
            if (pid != null && !loaded.containsKey(pid)) {
                coldStartPids.add(pid);
            }
        }

        if (!loaded.isEmpty()) {
            log.info("已载入动态推送去重记录：{} 个订阅、共 {} 条动态 ID（重启后不会重推这些）",
                    loaded.size(), loaded.values().stream().mapToInt(Set::size).sum());
        }
        if (!coldStartPids.isEmpty()) {
            log.info("订阅 {} 没有去重记录（本特性首次运行或记录被清空），本次启动只推送启动后新发布的动态，"
                            + "不回补启动前 {} 分钟窗口内的旧动态",
                    coldStartPids, RECENT_MINUTES);
        }
    }

    /**
     * 解析 {@code pid=id,id|pid=id,id} 形式的去重记录。
     *
     * <p>刻意不用 JSON：这条记录是"纯数字对纯数字"，自解析免掉一层序列化依赖，
     * 出错时在库里直接肉眼可读、可手改。
     *
     * @param raw 库里的原始字符串，可为 null / 空白
     * @return pid → 已推动态 ID；无法解析的片段会被跳过（宁可退化也不能因一条脏数据起不来）
     */
    private static Map<Long, Set<String>> parsePushedIds(String raw) {
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split("\\|")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(entry.substring(0, eq).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (String id : entry.substring(eq + 1).split(",")) {
                String value = id.trim();
                if (!value.isEmpty()) {
                    ids.add(value);
                }
            }
            if (!ids.isEmpty()) {
                result.put(pid, ids);
            }
        }
        return result;
    }

    /** 把 {@link #pushedDynamicIds} 序列化成 {@link #parsePushedIds} 能读回来的字符串 */
    private String serializePushedIds() {
        StringBuilder sb = new StringBuilder();
        pushedDynamicIds.forEach((pid, ids) -> {
            // 判空也放进锁内：原来 isEmpty() 在锁外读 LinkedHashSet 的 size（非 volatile），
            // 与 markPushed 的增删构成 data race。既然都要拿锁，就没必要留这条缝。
            synchronized (pushedIdsLock) {
                if (ids.isEmpty()) {
                    return;
                }
                if (!sb.isEmpty()) {
                    sb.append('|');
                }
                sb.append(pid).append('=').append(String.join(",", ids));
            }
        });
        return sb.toString();
    }

    /**
     * 把去重记录写回 {@code config} 表（仅在有变化时）。
     *
     * <p>写失败<b>不影响推送本身</b>——消息已经发出去了，最坏的后果只是重启后可能重推一次，
     * 所以这里吞掉异常并保留脏标记，下一轮再试。
     */
    private void persistPushedIdsIfDirty() {
        if (!pushedStateDirty) {
            return;
        }
        pushedStateDirty = false;
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_PUSHED_DYNAMIC_IDS, serializePushedIds());
        } catch (Throwable e) {
            pushedStateDirty = true;
            log.warn("保存动态推送去重记录失败（不影响本轮推送，重启后可能重复推一次）：{}", e.toString());
        }
    }

    /**
     * 这条动态是不是「本进程启动前就已发布」的积压（只在冷启动订阅上有意义）。
     *
     * <p>仅当 {@link #coldStartPids} 含该订阅时才可能为 true；判断依据是
     * {@link #estimatePublishTimeMillis} 估算出的发布时刻早于 {@link #bootTimeMillis}。
     */
    private boolean isColdStartBacklog(Long pid, String time) {
        return coldStartPids.contains(pid) && estimatePublishTimeMillis(time) < bootTimeMillis;
    }

    /**
     * 从 B 站前端的相对时间文案反推发布时刻（毫秒）。
     *
     * <p>只需要处理 {@link #isFresh} 放行的两种文案（{@code 刚刚} / {@code N分钟前}）——
     * 调用点都在 {@code isFresh} 之后，别的格式到不了这里。
     *
     * <p>「刚刚」取<b>整桶上界</b>（按 1 分钟前算）：它是约 1 分钟的模糊桶，
     * 保守取值只会让冷启动时少推一条旧动态，不会造成重复推送 —— 这正是本方法存在的目的。
     *
     * @return 估算的发布时刻；完全认不出时返回 0（当作很久以前，冷启动时归为积压）
     */
    private static long estimatePublishTimeMillis(String time) {
        if (time == null) {
            return 0L;
        }
        if (time.startsWith("刚刚")) {
            return System.currentTimeMillis() - 60_000L;
        }
        Matcher matcher = MINUTES_AGO.matcher(time);
        if (matcher.find()) {
            try {
                return System.currentTimeMillis() - Long.parseLong(matcher.group(1)) * 60_000L;
            } catch (NumberFormatException ignored) {
                // 落回 0
            }
        }
        return 0L;
    }

    /**
     * 判断一条动态是否「足够新、值得推送」。
     *
     * <p>上游 {@code Dynamic.DynamicInfo.time} 是 B 站前端的相对时间文案（实测形如
     * {@code 刚刚} / {@code 7小时前} / {@code 昨天 11:00 · 投稿了视频}）；「是不是新发的动态」
     * 只能从这段文案里读 —— 这是 bilibili-api 与 XatiiBot 约定的触发依据
     * （见 bilibili-api {@code REFACTOR_PLAN.md} §2.4）。
     *
     * <p>原来的判定写死 {@code startsWith("刚刚")}：「刚刚」窗口只有约 1 分钟，而本任务 60 秒
     * 轮询一次，命中窗口极窄、容易漏推；上游文档也明确承认该文案尚未在真实新动态上验证过。
     * 因此这里放宽为 <b>「刚刚」或「N 分钟前」且 N ≤ {@link #RECENT_MINUTES}</b>。
     *
     * <p><b>放宽不会造成重复推送</b>：{@link #pushedDynamicIds} 按订阅去重，同一个
     * dynamicId 只推一次；放宽只是把「两次轮询之间被时间窗口甩掉的新动态」补回来。
     * 若要恢复严格语义，把方法体改回 {@code time != null && time.startsWith("刚刚")} 即可。
     */
    private static boolean isFresh(String time) {
        if (time == null || time.isEmpty()) {
            return false;
        }
        if (time.startsWith("刚刚")) {
            return true;
        }
        Matcher matcher = MINUTES_AGO.matcher(time);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1)) <= RECENT_MINUTES;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     *  发送动态消息
     * @param username b站用户名
     * @param dynamic 动态对象
     * @param dynamicInfo 动态信息对象
     * @param bot 机器人对象
     * @param pushInfo 推送信息对象
     * @throws InterruptedException 线程中断异常
     * @throws IOException I/O异常
     */
    private void sendMsg(String username,Dynamic dynamic,Dynamic.DynamicInfo dynamicInfo,Bot bot,PushInfo pushInfo) throws InterruptedException, IOException {
        // UP 主昵称是第三方可控文本（他自己起的），而发送参数 autoEscape=false ⇒ 必须转义
        username = BiliBiliContant.escapeCq(username);
        String sendMsg = "";
        BilibiliClient bilibiliClient = new BilibiliClient();
        // 注意：下面三段必须互斥（else if）。原来写成三个独立 if 时是**顺序覆盖**：
        // 视频类动态 bvid 与 dynamicId 同时非空，最后一段会把刚拼好的「投稿了视频」卡片
        // 整段覆盖掉 —— 那段代码等于死代码，封面也永远发不出去。
        // 优先级：视频投稿 > 转发 > 图文/opus。
        if (dynamicInfo.getBvid() != null){
            // 封面**本地下载后转 base64** 再发，不把 URL 丢给 NapCat：
            // B 站图床（i0/i2.hdslb.com）对不带 Referer 的请求常返 403，而 NapCat 那侧的网络
            // 环境不归我们管。下载失败（urlToBase64 返回 null）就降级成纯文字，
            // 不因为一张封面把整条动态推送丢掉。
            String coverBase64 = BiliBiliContant.urlToBase64(bilibiliClient.getVideoCoverUrl(dynamicInfo.getBvid()));
            MsgUtils builder = MsgUtils.builder().text(username + " 投稿了视频\n");
            if (coverBase64 != null) {
                builder = builder.img("base64://" + coverBase64);
            }
            sendMsg = builder
                    .text("av"+bilibiliClient.getVideoAv(dynamicInfo.getBvid())+"\n"+dynamicInfo.getBvid()+
                          "标题："+  BiliBiliContant.escapeCq(bilibiliClient.getVideoTitle(dynamicInfo.getBvid()))+"\n"+
                          "简介："+ BiliBiliContant.escapeCq(bilibiliClient.getVideoDesc(dynamicInfo.getBvid()))+"\n\n"+
                          "https://www.bilibili.com/video/"+dynamicInfo.getBvid()
                    ).build();
        } else if (dynamicInfo.getShareDynamicId()!= null){
            // 转发动态：长图渲染失败时**降级成纯文字**，而不是整条丢掉
            // （原实现是 `return`，等于渲染一出问题这条推送就永远收不到）
            String base64Image = renderDynamicImage(dynamic, dynamicInfo.getShareDynamicId(), "转发动态");
            MsgUtils builder = MsgUtils.builder().text(username + " 转发了动态\n原动态：\n");
            if (base64Image != null) {
                builder = builder.img("base64://" + base64Image);
            }
            sendMsg = builder.build();
        } else if (dynamicInfo.getDynamicId()!=null) {
            String base64Image = renderDynamicImage(dynamic, dynamicInfo.getDynamicId(), "动态");
            MsgUtils builder = MsgUtils.builder().text(username+"发表了动态");
            if (base64Image != null) {
                builder = builder.img("base64://" + base64Image);
            }
            sendMsg = builder
                    .text("https://www.bilibili.com/opus/"+dynamicInfo.getDynamicId())
                    .build();
        }
        if (sendMsg.isEmpty()) {
            log.warn("动态解析不出可推送的正文（bvid/dynamicId/shareDynamicId 全为空），跳过：{}",
                    dynamicInfo);
            return;
        }
        sendMessage(bot, pushInfo, sendMsg);
    }

    /**
     * 渲染动态长图并转成 base64；<b>任何失败都返回 {@code null}</b>，由调用方降级为纯文字。
     *
     * <p><b>为什么这里必须兜 {@link Throwable} 而不是 {@link Exception}</b>（2026-09-14 真机踩到）：
     * 在一台没有任何字体、也没装 fontconfig 的 Debian 上，Java2D 初始化字体管理器时抛的是
     * <b>{@code java.lang.InternalError: ... Fontconfig head is null}</b> —— 它是 {@link Error}
     * 而不是 {@link Exception}。只要有一层是 {@code catch (Exception)}，它就一路穿出去，
     * 整轮动态推送在「发消息」之前就中断了：日志里只有一条 AsyncUncaughtExceptionHandler，
     * <b>用户什么都收不到</b>。
     *
     * @param dynamic   动态门面
     * @param dynamicId 动态 ID
     * @param what      用于日志的动宾短语（如「动态」「转发动态」）
     * @return base64 字符串；渲染不可用时为 {@code null}
     */
    private String renderDynamicImage(Dynamic dynamic, String dynamicId, String what) {
        try {
            BufferedImage image = dynamic.getDynamicImg(dynamicId);
            if (image == null) {
                log.warn("{}长图渲染返回空，改为只发文字：dynamicId={}", what, dynamicId);
                return null;
            }
            return BiliBiliContant.imgToBase64(image);
        } catch (Throwable t) {
            // 不把堆栈打进日志（可能每轮都出现会刷屏），但把「怎么修」写进文案
            log.error("{}长图渲染失败，改为只发文字：dynamicId={}，原因={}。"
                            + "若原因是 \"Fontconfig head is null\"，说明跑机器人的系统缺少字体/fontconfig，"
                            + "在 Linux 上执行 `apt-get install -y fontconfig fonts-dejavu-core` 即可",
                    what, dynamicId, t.toString());
            return null;
        }
    }

    /**
     * 构建推送消息。
     *
     * <p>⚠️ <b>必须传已经取好的 {@link LiveRoom}，不要再从 {@code Live} 门面调 getter</b>：
     * 门面的每个 getter 都是一次真实 HTTP（见 {@link #livePush} 的注释），
     * 用门面写这条消息要打 6 次接口。
     *
     * <p>⚠️ 该发「开播」还是「下播」<b>由调用方判定后显式传入</b>（{@code liveStarted}），
     * 不再由本方法自己从 {@code pushInfo.getLiveStatus()} 反推 —— 库里那个值是服务端原值，
     * 可能是 2（轮播中），而"轮播中的下一条状态"既可能是开播也可能是下播，
     * 靠原值反推会把「轮播 → 直播」判成下播（2026-10-07 修的）。
     *
     * @param bot         机器人
     * @param pushInfo    订阅
     * @param room        已取回的直播间信息（本轮缓存）
     * @param cardInfo    名片门面（自带单槽缓存，复用同一实例可省掉重复请求）
     * @param live        直播门面，仅用于 {@link Live#getLiveUrl(Long)}（纯本地拼接，不发请求）
     * @param liveStarted {@code true} = 发开播消息；{@code false} = 发下播消息
     */
    private String buildMessage(Bot bot, PushInfo pushInfo, LiveRoom room, CardInfo cardInfo, Live live,
                                boolean liveStarted) throws IOException {
        List<Long> atListStr = pushInfo.getAtList();
        Long roomId = pushInfo.getRoomId();
        Long uid = room.getUid();
        String userName = cardInfo.getUserName(uid);
        String liveUrl = live.getLiveUrl(roomId);

        // 下播消息
        if (!liveStarted) {
            return buildLiveEndMessage(pushInfo, userName);
        }

        // 开播消息：先记下本场开播时刻（下播时长、场次去重都靠它）
        processLiveStartTime(pushInfo, room);

        Integer atAll = pushInfo.getAtAll();
        if (atAll != null && atAll.equals(1) && isGroupAdmin(bot, pushInfo.getGroupId())) {
            return buildAtAllLiveMessage(userName, room, liveUrl);
        } else if (atListStr != null && !atListStr.isEmpty() && !atListStr.get(0).equals(0L)) {
            return buildAtUserLiveMessage(atListStr, userName, room, liveUrl);
        } else {
            return buildNormalLiveMessage(userName, room, liveUrl);
        }
    }

    /**
     * 处理直播开始时间（写入 {@code push_info.live_time}）。
     */
    private void processLiveStartTime(PushInfo pushInfo, LiveRoom room) {
        Long startMillis = parseLiveStartMillis(room.getLive_time());
        if (startMillis != null) {
            pushInfo.setLiveTime(startMillis);
        }
    }

    /**
     * 解析服务端给的 {@code live_time}，拿不到有效场次就返回 {@code null}。
     *
     * <p>🔴 <b>{@code "0000-00-00 00:00:00"} 是非空字符串，但它不是时间</b>：
     * 未开播 / 轮播中服务端给的就是它，而 {@code SimpleDateFormat} 默认 lenient，
     * 会把它<b>成功</b>解析成一个公元前后的时间戳 —— 于是下播时长算出
     * "1xxxxxxxx时"这种离谱值，场次去重也会被这个假值污染。
     * 所以"零值日期"、解析失败、以及解析出非正数的一律当"没有场次"。
     *
     * @param liveTimeText 服务端 {@code live_time} 原文
     * @return 开播时刻（epoch 毫秒）；{@code null} = 没有有效场次
     */
    private static Long parseLiveStartMillis(String liveTimeText) {
        if (liveTimeText == null) {
            return null;
        }
        String text = liveTimeText.trim();
        if (text.isEmpty() || text.startsWith("0000")) {
            return null;
        }
        try {
            Date liveTime = SAFE_DATE_FORMAT.get().parse(text);
            if (liveTime == null) {
                return null;
            }
            long millis = liveTime.getTime();
            return millis > 0 ? millis : null;
        } catch (ParseException e) {
            log.error("解析开播时间失败: {}", liveTimeText, e);
            return null;
        }
    }

    /**
     * @param userName 用户名
     * @param room 已取回的直播间信息
     * @param liveUrl 直播间地址（本地拼接）
     * 构建@全体成员的开播消息
     */
    private String buildAtAllLiveMessage(String userName, LiveRoom room, String liveUrl) throws IOException {
        // 这条消息带 atAll()：主播昵称/直播标题不转义 = 把「@全体成员」的能力交出去
        userName = BiliBiliContant.escapeCq(userName);
        return MsgUtils.builder().atAll()
                .text(" " + userName + " 开播了" +
                        "\n标题：" + BiliBiliContant.escapeCq(room.getTitle()) + "\n" +
                        "分区：" + room.getArea_name() + "\n" +
                        "地址：" + liveUrl + "\n")
                .img(room.getUser_cover())
                .build();
    }

    /**
     * 构建@特定用户的开播消息
     */
    private String buildAtUserLiveMessage(List<Long> atListStr, String userName, LiveRoom room, String liveUrl) throws IOException {
        userName = BiliBiliContant.escapeCq(userName);
        StringBuilder msgBuilder = new StringBuilder();
        for (Long aLong : atListStr) {
            msgBuilder.append(MsgUtils.builder().at(aLong).build());
        }

        return msgBuilder + MsgUtils.builder().text(" " +
                        userName + " 开播了" +
                        "\n标题：" + BiliBiliContant.escapeCq(room.getTitle()) + "\n" +
                        "分区：" + room.getArea_name() + "\n" +
                        "地址：" + liveUrl + "\n" +
                        "[CQ:image,file=" + room.getUser_cover() + "]")
                .build();
    }

    /**
     * 构建普通开播消息
     */
    private String buildNormalLiveMessage(String userName, LiveRoom room, String liveUrl) throws IOException {
        userName = BiliBiliContant.escapeCq(userName);
        return MsgUtils.builder()
                .text(" " + userName + " 开播了" +
                        "\n标题：" + BiliBiliContant.escapeCq(room.getTitle()) + "\n" +
                        "分区：" + room.getArea_name() + "\n" +
                        "地址：" + liveUrl +"?live_from="+(int)(Math.random()*10000)+"&spm_id_from=333.1007.top_right_bar_window_dynamic.content.click"+ "\n" +
                        "[CQ:image,file=" + room.getUser_cover() + "]")
                .build();
    }

    /**
     * 构建下播消息
     */
    private String buildLiveEndMessage(PushInfo pushInfo, String userName) {
        userName = BiliBiliContant.escapeCq(userName);
        long now = System.currentTimeMillis();
        long between = now - pushInfo.getLiveTime();
        long hour = (between / (60 * 60 * 1000));
        long minute = ((between / (60 * 1000)) % 60);
        long second = ((between / 1000) % 60);
        String time = formatLiveTime(hour, minute, second);

        return MsgUtils.builder()
                .text(userName + " 下播了" +
                        "\n直播时长：" + time)
                .build();
    }

    /**
     * 格式化直播时长
     */
    private String formatLiveTime(long hour, long minute, long second) {
        return hour == 0 ?
                minute==0?  second + "秒" : minute + "分钟":
                hour + "时" + minute + "分";
    }

    /**
     * 把该订阅的直播状态写回库里，并同步内存对象。
     *
     * <p><b>两处改动，都是为了收窄影响面</b>：
     * <ol>
     *   <li><b>只更新 {@code live_status} + {@code live_time} 两列</b>，不用 {@code updateById}
     *       整行覆盖。整行覆盖等于把"轮首读到的那份快照"整个写回去，
     *       这一轮里对同一条记录的其它改动会被悄悄压掉。</li>
     *   <li><b>直接写入服务端的真实状态</b>，不做原来的「0↔1 翻转」。
     *       翻转只在状态严格二值时才等价；一旦服务端给出第三种值（轮播），
     *       翻转得到的值就不再是"服务端说的那个状态"，下一轮又要再进一次分支。</li>
     * </ol>
     *
     * <p>⚠️ {@code live_time} <b>必须一起写</b>：开播时 {@link #processLiveStartTime}
     * 刚把它更新成本次开播时刻，下播时长（{@link #buildLiveEndMessage}）就是靠它算的。
     * 只写 live_status 会让时长永远按上一次开播的时刻算。
     */
    private void updatePushInfoStatus(PushInfo pushInfo, Integer newStatus) {
        pushInfo.setLiveStatus(newStatus);
        Long liveTime = pushInfo.getLiveTime() == null ? 0L : pushInfo.getLiveTime();
        pushInfoMapper.update(null, new LambdaUpdateWrapper<PushInfo>()
                .eq(PushInfo::getPid, pushInfo.getPid())
                .set(PushInfo::getLiveStatus, newStatus)
                .set(PushInfo::getLiveTime, liveTime));
    }

    /**
     * 发送消息
     */
    private void sendMessage(Bot bot, PushInfo pushInfo, String message) {
        if (Objects.isNull(pushInfo.getGroupId())) {
            bot.sendPrivateMsg(pushInfo.getQqUid(), message, false);
        } else {
            bot.sendGroupMsg(pushInfo.getGroupId(), message, false);
        }
    }

    private Boolean hasPush(Long roomId, Long qqUid, Long groupId) {
        LambdaQueryWrapper<PushInfo> queryWrapper = getWrapper(roomId, qqUid, groupId);
        return this.getOne(queryWrapper) != null;
    }

    private LambdaQueryWrapper<PushInfo> getWrapper(Long roomId, Long qqUid, Long groupId) {
        LambdaQueryWrapper<PushInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PushInfo::getRoomId, roomId);
        setWrapper(qqUid, groupId, queryWrapper);
        return queryWrapper;
    }

    private void setWrapper(Long qqUid, Long groupId, LambdaQueryWrapper<PushInfo> queryWrapper) {
        if (!Objects.isNull(qqUid)) {
            queryWrapper.eq(PushInfo::getQqUid, qqUid);
        }else {
            queryWrapper.isNull(PushInfo::getQqUid);
        }
        if (!Objects.isNull(groupId)) {
            queryWrapper.eq(PushInfo::getGroupId, groupId);
        }else {
            queryWrapper.isNull(PushInfo::getGroupId);
        }
    }

    private Long getUid(Long roomId) {
        return new Live().getUid(roomId);
    }

    private String getUserName(Long uid) {
        try {
            return new CardInfo().getUserName(uid);
        } catch (Exception e) {
            log.error("获取用户名时发生异常", e);
            return null;
        }
    }

    private int[] parsePushSettings(Integer livePush, Integer dynamicPush) {
        int livePushStatus = Objects.isNull(livePush) ? 0 : livePush;
        int dynamicPushStatus = Objects.isNull(dynamicPush) ? 0 : dynamicPush;
        return new int[]{livePushStatus, dynamicPushStatus};
    }

    private PushInfo createPushInfo(Long roomId, Long qqUid, Long groupId, int livePushStatus, int dynamicPushStatus) {
        PushInfo pushInfo = new PushInfo();
        pushInfo.setRoomId(roomId);
        pushInfo.setQqUid(qqUid);
        pushInfo.setGroupId(groupId);
        pushInfo.setLivePush(livePushStatus);
        pushInfo.setDynamicPush(dynamicPushStatus);
        pushInfo.setCreateTime(System.currentTimeMillis());
        pushInfo.setUpdateTime(System.currentTimeMillis());
        return pushInfo;
    }

    /**
     * 鉴权：规则本体在 {@link BotAdminChecker}（与 Cookie 配置命令共用一份，避免两处逻辑漂移）。
     */
    private boolean isNotAdmin(AnyMessageEvent event){
        return !botAdminChecker.isAdmin(event);
    }

    private boolean isGroupAdmin(Bot bot,Long groupId){
        if (Objects.isNull(groupId)) return true;
        ActionData<GroupMemberInfoResp> memberInfo = bot.getGroupMemberInfo(groupId, bot.getSelfId(), false);
        String role = memberInfo.getData().getRole();

        return !StringUtils.isEmpty(role)&&(role.equals("admin")|| role.equals("owner"));

    }

}


