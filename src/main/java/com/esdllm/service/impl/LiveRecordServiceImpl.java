package com.esdllm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.esdllm.bilibiliApi.bilibiliApi.CardInfo;
import com.esdllm.bilibiliApi.bilibiliApi.Live;
import com.esdllm.bilibiliApi.bilibiliApi.LiveExtra;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.mapper.LiveRecordFileMapper;
import com.esdllm.mapper.LiveRecordSubMapper;
import com.esdllm.model.LiveRecordFile;
import com.esdllm.model.LiveRecordSub;
import com.esdllm.service.FfmpegProvider;
import com.esdllm.service.LiveRecordService;
import com.esdllm.service.LiveRecorder;
import com.esdllm.service.RecordFileServer;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionRaw;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 录播服务的实现。整体形状：
 *
 * <pre>
 *   tick()      每 30 秒：查订阅里的直播间 → 在播就起一个录制 worker（后台线程）
 *   worker      ffmpeg 录一段 → 退出 → 问一次直播状态 →
 *               还在播就重新取址续录（新分片），下播就收工 → 合并 → 登记入库
 *   maintain()  每次巡检 + 每场录完：超 15GB 踢一次压缩（单线程后台，很慢），超 25GB 删最早的
 * </pre>
 *
 * <h2>几处刻意的设计（都是"错了会很难查"的地方）</h2>
 *
 * <ul>
 *   <li><b>录制 worker 自给自足，不依赖 tick 的下播检测</b>：ffmpeg 每次退出后自己去问一次
 *       直播状态，据此决定"续录"还是"收工"。这样"网络抖动导致流断但人还在播"和
 *       "主播真的下播了"这两个完全同形的现象（都表现为 ffmpeg 退出）就能被区分开 ——
 *       而如果不区分，前者会把一场直播录成十个碎片、后者会白等一个轮询周期。</li>
 *   <li><b>停止条件是"流断 + 状态翻转"，不是墙钟</b>：单场时长上限只是兜底，
 *       避免下播检测失效时把盘写满。</li>
 *   <li><b>录制用 flv，取用时才转 mp4</b>：flv 流式可写、随时断电都能播；
 *       mp4 的索引在文件尾，录到一半被杀就是打不开的坏文件。而转 mp4 是
 *       {@code -c copy} 重封装，秒级、无损，放在用户真要拿的时候做最划算。</li>
 *   <li><b>容量统计以数据库为准 + 巡检校正</b>：{@code size_bytes} 是真相源（加减都在它上面算），
 *       但每轮巡检会按磁盘实况修正一次 —— 手工删过文件也不会让统计永远虚高。</li>
 *   <li><b>压缩是单线程后台串行任务</b>：H.265 重编码在 4 核机上很慢，
 *       多开只会互相拖死；而它一旦跑起来就要跑很久，所以绝不能放在调度线程里同步做。</li>
 * </ul>
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Service
public class LiveRecordServiceImpl implements LiveRecordService {

    private static final long MB = 1024L * 1024L;

    /** 状态取值，与 {@code schema/sqlite.sql} 里的注释一一对应 */
    private static final String ST_RECORDING = "RECORDING";
    private static final String ST_DONE = "DONE";
    private static final String ST_COMPRESSED = "COMPRESSED";
    private static final String ST_INTERRUPTED = "INTERRUPTED";
    private static final String ST_FAILED = "FAILED";

    /** 一次分片录制至少要有这么多字节才算"录到了东西"（低于它按空片处理） */
    private static final long MIN_PART_BYTES = 64 * 1024L;

    /** 分片之间重新取址前的等待（毫秒）：给对端一点喘息，也避免死循环打接口 */
    private static final long RELINK_DELAY_MS = 3000L;

    /** 连续几次"一点数据都没录到"就放弃这一场（防止死循环刷接口） */
    private static final int MAX_EMPTY_PARTS = 3;

    /** 连续几次取流失败就放弃这一场 */
    private static final int MAX_FETCH_FAILURES = 5;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    @Resource
    private LiveRecordSubMapper subMapper;
    @Resource
    private LiveRecordFileMapper fileMapper;
    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private FfmpegProvider ffmpegProvider;
    @Resource
    private LiveRecorder recorder;
    @Resource
    private RecordFileServer fileServer;

    /** 正在录制的场次：roomId → 会话。<b>它同时是并发闸</b>（见 startRecording） */
    private final ConcurrentHashMap<Long, Session> active = new ConcurrentHashMap<>();

    private final ExecutorService recordPool = Executors.newCachedThreadPool(r -> daemon(r, "live-record"));
    private final ExecutorService compressPool = Executors.newSingleThreadExecutor(r -> daemon(r, "live-record-compress"));

    /** 巡检重入闸：一轮没跑完就跳过下一轮 */
    private final AtomicBoolean maintaining = new AtomicBoolean(false);
    /** 压缩任务重入闸：同一时间只允许一个压缩在跑（哪怕巡检每几分钟踢一次） */
    private final AtomicBoolean compressing = new AtomicBoolean(false);

    /** 上次 tick 的墙钟，用来做"可配置间隔"的节流（调度本身的频率是固定的 10 秒） */
    private volatile long lastTickAt = 0L;

    /** ffmpeg 未就绪的告警只打一次，别每 30 秒刷屏 */
    private volatile boolean warnedFfmpegMissing = false;

    /** "全部被标记保留、删不动"的告警时间戳（节流用） */
    private volatile long lastKeepAllWarnAt = 0L;

    /**
     * "把 flv 就地转成 mp4"这把锁 —— <b>全局一把，不是每场一把</b>。
     *
     * <p>为什么必须有：转 mp4 是<b>就地替换</b>（转完删源、改库里的 path），
     * 同一场被两个请求同时转，会变成"两个 ffmpeg 抢同一个输出文件 + 后完成的那个
     * 把已经改好的记录又改回去"，甚至删掉对方正在读的源。
     *
     * <p>为什么可以全局一把：转 mp4 是 {@code -c copy}（不重编码，纯 IO），
     * 且<b>每场只会转一次</b> —— 转完磁盘上就是 mp4，后续请求在锁外直接返回。
     * 所以真正进锁的只有"某场的第一次播放"，争用窗口小到不值得为它引入
     * 按 fid 分锁（那还要处理锁对象的淘汰，得不偿失）。
     */
    private final Object remuxLock = new Object();

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ================================================================== 生命周期

    @PostConstruct
    @Override
    public void onStartup() {
        Path root = recordRoot();
        try {
            Files.createDirectories(root);
            log.info("录播目录：{}（{}）", root, enabled() ? "总闸已开" : "总闸关闭中");
        } catch (IOException e) {
            log.error("录播目录 {} 创建失败，录制将不可用：{}", root, e.toString());
            return;
        }
        // 上次进程结束时的 RECORDING 行现在没有任何 worker 在跟了。
        // 放到后台线程里收尾：如果留下多个分片，要跑一次 concat 合并，
        // 那可能要几十秒，不该拖住启动。
        Thread salvage = daemon(this::salvageInterrupted, "live-record-salvage");
        salvage.start();
    }

    @PreDestroy
    @Override
    public void shutdown() {
        for (Session session : active.values()) {
            session.cancelled = true;
            Process p = session.process;
            if (p != null && p.isAlive()) {
                log.info("关停：强杀录制进程 roomId={}（pid={}）", session.row.getRoomId(), p.pid());
                p.destroyForcibly();
            }
        }
        recordPool.shutdownNow();
        compressPool.shutdownNow();
    }

    /**
     * 收尾上次遗留的 RECORDING 场次。
     *
     * <p>做了三件事：能合并的分片就合并（免得几个 {@code part*.flv} 变成取不出来的碎片）、
     * 登记最终文件、状态改 {@code INTERRUPTED}（<b>刻意不标 DONE</b> ——
     * 这一场没有被正常收尾这件事必须留在数据里，列表上要看得出来）。
     */
    private void salvageInterrupted() {
        List<LiveRecordFile> rows;
        try {
            rows = fileMapper.selectList(new LambdaQueryWrapper<LiveRecordFile>()
                    .eq(LiveRecordFile::getStatus, ST_RECORDING));
        } catch (Throwable t) {
            log.warn("读取上次遗留的录制记录失败：{}", t.toString());
            return;
        }
        for (LiveRecordFile row : rows) {
            try {
                Path dir = sessionDir(row);
                List<Path> parts = listParts(dir);
                Path main = null;
                if (parts.size() > 1 && ffmpegPath() != null) {
                    main = mergeParts(dir, parts, "启动收尾");
                } else if (parts.size() == 1) {
                    main = parts.get(0);
                }
                long size = dirSize(dir);
                LambdaUpdateWrapper<LiveRecordFile> update = new LambdaUpdateWrapper<LiveRecordFile>()
                        .eq(LiveRecordFile::getFid, row.getFid())
                        .set(LiveRecordFile::getStatus, ST_INTERRUPTED)
                        .set(LiveRecordFile::getEndTime,
                                row.getEndTime() == null || row.getEndTime() == 0
                                        ? System.currentTimeMillis() : row.getEndTime())
                        .set(LiveRecordFile::getSizeBytes, size)
                        .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis());
                if (main != null) {
                    update.set(LiveRecordFile::getPath, relative(main));
                }
                fileMapper.update(null, update);
                log.warn("上次进程结束时有录制未收尾：录播 {}（房间 {}），已标记为「中断」；"
                                + "目录 {} 保留着（分片 {} 个，共 {} MB）",
                        row.getFid(), row.getRoomId(), dir, parts.size(), size / MB);
            } catch (Throwable t) {
                log.warn("收尾录播 {} 时出错：{}", row.getFid(), t.toString());
            }
        }
    }

    // ================================================================== 订阅

    @Override
    public List<LiveRecordSub> listSubs() {
        return subMapper.selectList(new LambdaQueryWrapper<LiveRecordSub>()
                .orderByAsc(LiveRecordSub::getCreateTime));
    }

    @Override
    public List<LiveRecordSub> listSubs(Long groupId) {
        LambdaQueryWrapper<LiveRecordSub> wrapper = new LambdaQueryWrapper<LiveRecordSub>()
                .orderByAsc(LiveRecordSub::getCreateTime);
        if (groupId == null) {
            // ⚠️ 不能用 eq(groupId, null) —— MyBatis-Plus 会拼成 `group_id = null`，
            //    在 SQL 里恒不成立（三值逻辑），结果永远是空列表。
            wrapper.isNull(LiveRecordSub::getGroupId);
        } else {
            wrapper.eq(LiveRecordSub::getGroupId, groupId);
        }
        return subMapper.selectList(wrapper);
    }

    @Override
    public LiveRecordSub findSub(Long roomId, Long groupId) {
        if (roomId == null) {
            return null;
        }
        LambdaQueryWrapper<LiveRecordSub> wrapper = new LambdaQueryWrapper<LiveRecordSub>()
                .eq(LiveRecordSub::getRoomId, roomId);
        if (groupId == null) {
            wrapper.isNull(LiveRecordSub::getGroupId);
        } else {
            wrapper.eq(LiveRecordSub::getGroupId, groupId);
        }
        // LIMIT 1：同一个 (roomId, groupId) 理论上只会有一行，但历史数据/并发插入都可能
        // 留下重复，而 selectOne 一旦取到多行会抛异常 —— 这里宁可取一行也不要整个命令炸掉
        return subMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    @Override
    public LiveRecordSub addSub(Long roomId, Long groupId) throws IOException {
        if (roomId == null || roomId <= 0) {
            throw new IOException("房间号必须是正整数");
        }
        LiveRecordSub exists = findSub(roomId, groupId);
        if (exists != null) {
            return exists;
        }
        // 顺手取一次 uid/昵称：失败不该阻断"加订阅"这件事（网络问题不该变成功能不可用），
        // 昵称空着而已 —— 列表里会显示房间号
        Long uid = null;
        String uname = null;
        try {
            LiveRoom room = new Live().getLiveRoom(roomId);
            if (room != null) {
                uid = room.getUid();
            }
        } catch (Throwable t) {
            log.info("加录播订阅时取直播间信息失败（不影响订阅本身）：{}", t.toString());
        }
        if (uid != null) {
            try {
                uname = new CardInfo().getUserName(uid);
            } catch (Throwable t) {
                log.info("取主播昵称失败（不影响订阅本身）：{}", t.toString());
            }
        }

        LiveRecordSub sub = new LiveRecordSub()
                .setRoomId(roomId)
                .setGroupId(groupId)
                .setUid(uid)
                .setUname(uname)
                .setAutoRecord(1)
                .setQuality(loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_QUALITY,
                        LoadDSConfig.DEFAULT_RECORD_QUALITY))
                .setCreateTime(System.currentTimeMillis())
                .setUpdateTime(System.currentTimeMillis());
        subMapper.insert(sub);
        log.info("已添加录播订阅：房间 {}（uid={}，昵称={}，归属={}）",
                roomId, uid, uname == null ? "未知" : uname,
                groupId == null ? "全局（私聊加的）" : "群 " + groupId);
        return sub;
    }

    @Override
    public boolean setSubAutoRecord(Long roomId, Long groupId, boolean on) {
        LiveRecordSub sub = findSub(roomId, groupId);
        if (sub == null) {
            return false;
        }
        subMapper.update(null, new LambdaUpdateWrapper<LiveRecordSub>()
                .eq(LiveRecordSub::getSid, sub.getSid())
                .set(LiveRecordSub::getAutoRecord, on ? 1 : 0)
                .set(LiveRecordSub::getUpdateTime, System.currentTimeMillis()));
        return true;
    }

    @Override
    public boolean removeSub(Long roomId, Long groupId) {
        LiveRecordSub sub = findSub(roomId, groupId);
        if (sub == null) {
            return false;
        }
        subMapper.deleteById(sub.getSid());
        // ⚠️ 停录制这一步要看"这个房间还有没有人要"，而不是"我刚删的这条"：
        //    别的群还订阅着同一房间时，把它正在录的那一路掐掉是灾难性的 ——
        //    那一场会以"中断"收尾，而对方压根不知道自己做错了什么。
        boolean stillWanted = subMapper.selectCount(new LambdaQueryWrapper<LiveRecordSub>()
                .eq(LiveRecordSub::getRoomId, roomId)) > 0;
        if (stillWanted) {
            log.info("已取消录播订阅：房间 {}（群 {}）—— 仍有其它订阅指向该房间，正在进行的录制不受影响",
                    roomId, groupId == null ? "全局" : groupId);
            return true;
        }
        Session session = active.get(roomId);
        if (session != null) {
            session.cancelled = true;
            Process p = session.process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        log.info("已取消录播订阅：房间 {}（群 {}，已无其它订阅，正在进行的录制已停止；已录好的文件保留）",
                roomId, groupId == null ? "全局" : groupId);
        return true;
    }

    @Override
    public List<Long> roomsOfGroup(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        try {
            List<Long> rooms = subMapper.selectEverySubscribedRoomIds(groupId);
            return rooms == null ? List.of() : rooms;
        } catch (Throwable t) {
            // 可见性查询失败 ⇒ 回空集（fail-closed）：宁可让网页上一时看不到东西，
            // 也不能因为查库出错就把**别的群**的房间号漏出去
            log.warn("查群 {} 的订阅房间失败，本次按「没有任何房间」处理：{}", groupId, t.toString());
            return List.of();
        }
    }

    @Override
    public boolean isSubscribed(Long roomId) {
        if (roomId == null) {
            return false;
        }
        return subMapper.selectCount(new LambdaQueryWrapper<LiveRecordSub>()
                .eq(LiveRecordSub::getRoomId, roomId)) > 0;
    }

    // ================================================================== 调度

    @Override
    public void tick() {
        if (!enabled()) {
            return;
        }
        int intervalSeconds = Math.max(5, loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_CHECK_SECONDS,
                LoadDSConfig.DEFAULT_RECORD_CHECK_SECONDS));
        long now = System.currentTimeMillis();
        if (now - lastTickAt < intervalSeconds * 1000L) {
            return;
        }
        lastTickAt = now;

        String ffmpeg = ffmpegPath();
        if (ffmpeg == null) {
            if (!warnedFfmpegMissing) {
                warnedFfmpegMissing = true;
                log.warn("录播已开启但 ffmpeg 未就绪 ⇒ 本轮起不会自动录制（其它功能不受影响）。"
                        + "装好 ffmpeg 或配置 {} 后重启即可", LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH);
            }
            return;
        }
        warnedFfmpegMissing = false;

        List<LiveRecordSub> subs = subMapper.selectList(new LambdaQueryWrapper<LiveRecordSub>()
                .eq(LiveRecordSub::getAutoRecord, 1));
        if (subs.isEmpty()) {
            return;
        }
        // ★ 按房间去重：订阅是"每群一行"的，所以同一个房间可能有好几行
        //   （群 A、群 B 都订了 1024）。而**录制只该有一路** ——
        //   不去重的话，同一场直播会跑两个 ffmpeg、占两份磁盘，还被算成两场。
        //   代表取最早的那一行（LinkedHashMap 保持 selectList 的 create_time 顺序）。
        Map<Long, LiveRecordSub> byRoom = new LinkedHashMap<>();
        for (LiveRecordSub sub : subs) {
            if (sub.getRoomId() != null) {
                byRoom.putIfAbsent(sub.getRoomId(), sub);
            }
        }
        Live live = new Live();
        for (LiveRecordSub sub : byRoom.values()) {
            Long roomId = sub.getRoomId();
            if (active.containsKey(roomId)) {
                continue;
            }
            try {
                LiveRoom room = live.getLiveRoom(roomId);
                if (room == null || !Integer.valueOf(1).equals(room.getLive_status())) {
                    continue;
                }
                startRecording(sub, room);
            } catch (Throwable t) {
                // 单个房间失败不该影响其它订阅（与 livePush 同一口径）
                log.warn("开播检测失败，房间 {}：{}", roomId, t.toString());
            }
        }
    }

    /**
     * 起一场录制。这里做的是<b>准入</b>：并发够不够、盘够不够、录一行 RECORDING。
     * 真正的录制在 {@link #runSession} 里跑。
     */
    private void startRecording(LiveRecordSub sub, LiveRoom room) {
        int maxConcurrent = Math.max(1, loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_MAX_CONCURRENT,
                LoadDSConfig.DEFAULT_RECORD_MAX_CONCURRENT));
        if (active.size() >= maxConcurrent) {
            log.info("并发录制已达上限 {}，房间 {} 本轮不录（下轮再试）", maxConcurrent, sub.getRoomId());
            return;
        }
        // 磁盘水位：这是**开始写之前**的最后一道闸。
        // 容量巡检算的是"已登记文件"，而这一路还没登记，只靠巡检拦不住"边录边写满"。
        long minFreeMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_DISK_MIN_FREE_MB,
                LoadDSConfig.DEFAULT_RECORD_DISK_MIN_FREE_MB);
        long freeMb = freeSpaceMb(recordRoot());
        if (freeMb >= 0 && freeMb < minFreeMb) {
            log.warn("磁盘剩余 {} MB 低于水位 {} MB，拒绝开始录制（房间 {}）—— 请清理录播或调整 {}",
                    freeMb, minFreeMb, sub.getRoomId(), LoadDSConfig.KEY_BILI_RECORD_DISK_MIN_FREE_MB);
            return;
        }

        // 单场上限在这里先算一次，写进备注用
        int maxSeconds = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_MAX_SECONDS,
                LoadDSConfig.DEFAULT_RECORD_MAX_SECONDS);

        long now = System.currentTimeMillis();
        LiveRecordFile row = new LiveRecordFile()
                .setSid(sub.getSid())
                .setRoomId(sub.getRoomId())
                .setUid(room.getUid() != null ? room.getUid() : sub.getUid())
                .setUname(sub.getUname())
                .setTitle(room.getTitle())
                .setStartTime(now)
                .setEndTime(0L)
                .setStatus(ST_RECORDING)
                .setKeep(0)
                .setCompressed(0)
                .setSizeBytes(0L)
                .setDurationMs(0L)
                .setQuality(0)
                .setCreateTime(now)
                .setUpdateTime(now);
        fileMapper.insert(row);

        Path dir = sessionDir(row);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建录制目录失败 {}：{}", dir, e.toString());
            markFailed(row, "创建目录失败：" + e.getMessage());
            return;
        }

        Session session = new Session(row, sub, dir);
        active.put(sub.getRoomId(), session);
        try {
            recordPool.submit(() -> runSession(session));
        } catch (RejectedExecutionException e) {
            active.remove(sub.getRoomId());
            markFailed(row, "录制线程池已关闭");
            return;
        }
        log.info("开始录制：房间 {}（{}）→ {}，单场上限 {} 分钟，清晰度请求 qn={}",
                sub.getRoomId(), room.getTitle(), dir, maxSeconds / 60,
                sub.getQuality() == null ? "默认原画" : sub.getQuality());
    }

    // ================================================================== 录制 worker

    /**
     * 一场录制的完整生命周期：循环取址-录制-判断，最后合并、登记。
     *
     * <p>循环的退出条件有五个，各自对应一类真实情况（写在这里是为了下次改的时候知道
     * 哪个分支是干嘛的）：主播下播、到达单场上限、被取消（订阅被删/关停）、
     * 连续取流失败、连续录到空片。
     */
    private void runSession(Session session) {
        LiveRecordFile row = session.row;
        Long roomId = row.getRoomId();
        int maxSeconds = Math.max(60, loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_MAX_SECONDS,
                LoadDSConfig.DEFAULT_RECORD_MAX_SECONDS));
        long deadline = session.startMs + maxSeconds * 1000L;
        LiveExtra liveExtra = new LiveExtra();
        Live live = new Live();
        String ffmpeg = ffmpegPath();
        try {
            int partIndex = 0;
            int emptyParts = 0;
            int fetchFailures = 0;
            while (true) {
                if (session.cancelled) {
                    session.note = "被取消";
                    break;
                }
                long remainingSeconds = (deadline - System.currentTimeMillis()) / 1000L;
                if (remainingSeconds <= 5) {
                    session.note = "达到单场时长上限";
                    break;
                }

                LiveStream stream;
                try {
                    stream = liveExtra.getLiveStream(roomId, session.sub.getQuality());
                } catch (Throwable t) {
                    fetchFailures++;
                    if (fetchFailures >= MAX_FETCH_FAILURES) {
                        session.note = "取流连续失败 " + fetchFailures + " 次";
                        break;
                    }
                    log.info("取直播流失败（第 {} 次），{} 秒后重试：{}", fetchFailures,
                            RELINK_DELAY_MS / 1000, t.toString());
                    sleepQuietly(RELINK_DELAY_MS);
                    continue;
                }
                fetchFailures = 0;

                if (stream.getCurrent_qn() != null && session.quality == null) {
                    session.quality = stream.getCurrent_qn();
                }
                String url = stream.getDurl().get(0).getUrl();

                Path part = session.dir.resolve(String.format("part%03d.flv", ++partIndex));
                session.currentPart = part;
                LiveRecorder.Result result = recorder.run(
                        recorder.recordCommand(ffmpeg, url, part, remainingSeconds),
                        session.dir.resolve("ffmpeg.log"), part, remainingSeconds, p -> session.process = p);
                session.process = null;

                long bytes = sizeOf(part);
                if (bytes >= MIN_PART_BYTES) {
                    session.parts.add(part);
                    emptyParts = 0;
                } else {
                    // 空片/半截片直接删掉：留着只会让后面的合并报错，还白占体积统计
                    deleteQuietly(part);
                    emptyParts++;
                    log.info("第 {} 段没录到有效数据（{} 字节，ffmpeg exit={}），已丢弃", partIndex, bytes, result.exitCode());
                }
                if (session.cancelled) {
                    session.note = "被取消";
                    break;
                }

                // ★ 关键判定：ffmpeg 退出这件事，和"主播下播"是同形的（都是流断）。
                //   所以必须再问一次直播状态 —— 这是"断流重连续录"与"正常收工"的分水岭。
                boolean stillLive;
                try {
                    LiveRoom room = live.getLiveRoom(roomId);
                    stillLive = room != null && Integer.valueOf(1).equals(room.getLive_status());
                } catch (Throwable t) {
                    // 查不到状态：保守当"还在播"，靠上限与空片计数收敛（宁多录一段，不漏录后半场）
                    stillLive = true;
                    log.info("收工判定时查直播状态失败，保守按「仍在播」处理：{}", t.toString());
                }
                if (!stillLive) {
                    session.note = "主播已下播";
                    break;
                }
                if (result.timedOut()) {
                    session.note = "达到单场时长上限";
                    break;
                }
                if (emptyParts >= MAX_EMPTY_PARTS) {
                    session.note = "连续 " + emptyParts + " 次没录到数据";
                    break;
                }
                sleepQuietly(RELINK_DELAY_MS);
            }
            finish(session);
        } catch (Throwable t) {
            log.error("录制任务异常，房间 {}：{}", roomId, t, t);
            markFailed(row, "录制异常：" + t.getMessage());
        } finally {
            active.remove(roomId);
            // 一场录完立刻按新水位巡检一次 —— 让"超了就压/删"尽快发生，而不是干等下一次巡检
            try {
                maintain();
            } catch (Throwable t) {
                log.warn("录制结束后的容量巡检失败：{}", t.toString());
            }
        }
    }

    /** 收尾：合并分片、读时长、登记入库。 */
    private void finish(Session session) {
        LiveRecordFile row = session.row;
        Path main = null;
        boolean merged = true;
        if (session.parts.size() == 1) {
            Path only = session.parts.get(0);
            Path target = session.dir.resolve("out.flv");
            try {
                if (!only.equals(target)) {
                    Files.move(only, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                main = target;
            } catch (IOException e) {
                main = only;
            }
        } else if (session.parts.size() > 1) {
            main = mergeParts(session.dir, session.parts, "录制收尾");
            merged = main != null;
            if (!merged) {
                // 合并失败也不能把记录丢掉：拿第一片当主文件（列表里状态会是"中断"，提示不完整）
                main = session.parts.get(0);
            }
        }

        if (main == null) {
            markFailed(row, session.note.isBlank() ? "没有录到任何数据" : session.note);
            deleteQuietly(session.dir.resolve("parts.txt"));
            return;
        }

        long durationMs = readDuration(main);
        if (durationMs <= 0) {
            // ffprobe 不在或读不出来 → 回落墙钟。刻意在日志里说清楚用了哪个，
            // 免得以后有人拿"时长"去反推码率时被墙钟值带偏
            durationMs = Math.max(0, System.currentTimeMillis() - session.startMs);
            log.info("读不到容器时长（ffprobe 不可用？），时长按墙钟估算：{} 分钟", durationMs / 60000);
        }
        long size = dirSize(session.dir);
        String status = merged ? ST_DONE : ST_INTERRUPTED;
        Integer quality = session.quality == null ? row.getQuality() : session.quality;

        fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getFid, row.getFid())
                .set(LiveRecordFile::getPath, relative(main))
                .set(LiveRecordFile::getSizeBytes, size)
                .set(LiveRecordFile::getDurationMs, durationMs)
                .set(LiveRecordFile::getQuality, quality == null ? 0 : quality)
                .set(LiveRecordFile::getEndTime, System.currentTimeMillis())
                .set(LiveRecordFile::getStatus, status)
                .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
        log.info("录制结束：房间 {}（{}）—— {}，时长 {} 分钟，{} MB，分片 {} 个，状态 {}",
                row.getRoomId(), row.getUname(), session.note.isBlank() ? "正常" : session.note,
                durationMs / 60000, size / MB, session.parts.size(), status);
    }

    private void markFailed(LiveRecordFile row, String why) {
        fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getFid, row.getFid())
                .set(LiveRecordFile::getStatus, ST_FAILED)
                .set(LiveRecordFile::getEndTime, System.currentTimeMillis())
                .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
        log.warn("录制未产出文件：房间 {}，原因：{}", row.getRoomId(), why);
    }

    /**
     * 合并分片。成功则删掉分片只留 {@code out.flv}，失败则<b>原样保留分片</b>并返回 {@code null}。
     *
     * <p>失败时刻意不"尽力保留一部分"：分片本身是完整的独立 flv，
     * 原样留着至少是可播的，比一个半截的合并结果有用。
     */
    private Path mergeParts(Path dir, List<Path> parts, String what) {
        String ffmpeg = ffmpegPath();
        if (ffmpeg == null) {
            return null;
        }
        Path listFile = dir.resolve("parts.txt");
        Path out = dir.resolve("out.flv");
        try {
            StringBuilder sb = new StringBuilder();
            for (Path part : parts) {
                // concat 清单的语法就是 file '<路径>'；我们的路径由自己生成、不含单引号
                // ⚠️ 必须把反斜杠换成正斜杠：清单里的单引号串认 \ 为转义字符，
                //    Windows 的 E:\a\b\c.flv 会被解析成 E:abc.flv ⇒ 合并命令报"找不到文件"，
                //    而报错点离真正的起因（就这么一个字符）很远。
                //    ffmpeg 两个平台都认正斜杠，所以统一用 / 是零代价的。
                sb.append("file '").append(slash(part.toAbsolutePath().toString())).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LiveRecorder.Result result = recorder.run(recorder.mergeCommand(ffmpeg, listFile, out),
                    dir.resolve("ffmpeg.log"), out, 1800, null);
            if (result.ok() && result.bytes() > 0) {
                for (Path part : parts) {
                    deleteQuietly(part);
                }
                deleteQuietly(listFile);
                log.info("{}：已合并 {} 个分片为 1 个文件（{} MB）", what, parts.size(), result.bytes() / MB);
                return out;
            }
            log.warn("{}：分片合并失败（ffmpeg exit={}，产物 {} 字节），"
                            + "保留 {} 个分片原样（它们各自可播），该场记录会标为「中断」",
                    what, result.exitCode(), result.bytes(), parts.size());
            deleteQuietly(out);
            return null;
        } catch (IOException e) {
            log.warn("{}：写 concat 清单失败（{}），保留分片原样", what, e.toString());
            return null;
        }
    }

    // ================================================================== 容量巡检

    @Override
    public void maintain() {
        if (!maintaining.compareAndSet(false, true)) {
            return;
        }
        try {
            refreshSizes();
            long used = usedBytes();
            long hardMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_HARD_TOTAL_MB,
                    LoadDSConfig.DEFAULT_RECORD_HARD_TOTAL_MB);
            long compressMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_TOTAL_MB,
                    LoadDSConfig.DEFAULT_RECORD_COMPRESS_TOTAL_MB);

            // ① 先删（超硬水位说明已经很急，删除是秒级见效的那个）
            if (hardMb > 0 && used > hardMb * MB) {
                used = evictTo(used, hardMb * MB);
            }
            // ② 再看要不要压缩（慢，所以只"踢一脚"，真正的活在后台单线程里干）
            if (compressMb > 0 && used > compressMb * MB) {
                kickCompress();
            }
        } catch (Throwable t) {
            log.warn("录播容量巡检失败：{}", t, t);
        } finally {
            maintaining.set(false);
        }
    }

    /**
     * 从最早的开始删，直到降到水位以下。
     *
     * @return 删完后的占用
     */
    private long evictTo(long used, long limitBytes) {
        log.info("录播总占用 {} MB 超过硬水位 {} MB，开始按时间顺序淘汰（保留标记的除外）",
                used / MB, limitBytes / MB);
        int guard = 0;
        while (used > limitBytes && guard++ < 500) {
            LiveRecordFile victim = oldestDeletable();
            if (victim == null) {
                long now = System.currentTimeMillis();
                // 节流：这种情况每次巡检都会成立，不节流会刷屏
                if (now - lastKeepAllWarnAt > 30 * 60_000L) {
                    lastKeepAllWarnAt = now;
                    log.warn("录播已超硬水位，但**没有可淘汰的场次**（其余都被标记为保留了）⇒ 停止淘汰。"
                            + "请人工处理：取消部分保留标记，或调大 {}",
                            LoadDSConfig.KEY_BILI_RECORD_HARD_TOTAL_MB);
                }
                break;
            }
            long before = used;
            if (!deleteRecord(victim.getFid())) {
                log.warn("淘汰录播 {} 失败，停止本轮淘汰（避免死循环）", victim.getFid());
                break;
            }
            used = usedBytes();
            if (used >= before) {
                // 删了但总量没降（size_bytes 与实际不符之类）⇒ 停手，别把记录一路删空
                log.warn("淘汰录播 {} 后总占用未下降（{} → {} MB），停止本轮淘汰",
                        victim.getFid(), before / MB, used / MB);
                break;
            }
        }
        log.info("淘汰收工，当前占用 {} MB", used / MB);
        return used;
    }

    /** 最老的、可淘汰的一场：跳过正在录的、跳过保留标记的。 */
    private LiveRecordFile oldestDeletable() {
        List<LiveRecordFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<LiveRecordFile>()
                .ne(LiveRecordFile::getStatus, ST_RECORDING)
                .eq(LiveRecordFile::getKeep, 0)
                .orderByAsc(LiveRecordFile::getStartTime)
                .last("LIMIT 20"));
        for (LiveRecordFile row : candidates) {
            if (row.getPath() != null && Files.isRegularFile(resolveRelative(row.getPath()))) {
                return row;
            }
            // 文件已经不在（手工删过）：留着这行只会一直挡在前面，删掉记录
            fileMapper.deleteById(row.getFid());
        }
        return null;
    }

    /** 踢一次后台压缩（已有任务在跑就直接返回）。 */
    private void kickCompress() {
        if (!compressing.compareAndSet(false, true)) {
            return;
        }
        try {
            compressPool.submit(() -> {
                try {
                    compressLoop();
                } catch (Throwable t) {
                    log.warn("压缩任务异常：{}", t, t);
                } finally {
                    compressing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            compressing.set(false);
        }
    }

    /**
     * 压缩循环：一直压到水位以下、或没有可压的为止。
     *
     * <p>⚠️ 它跑在单线程里、且一条命令可能要几小时 —— 这是本项目唯一会长时间占用 CPU 的地方，
     * 所以刻意不并行、也不放在调度线程里。
     */
    private void compressLoop() {
        long compressMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_TOTAL_MB,
                LoadDSConfig.DEFAULT_RECORD_COMPRESS_TOTAL_MB);
        if (compressMb <= 0) {
            return;
        }
        while (true) {
            long used = usedBytes();
            if (used <= compressMb * MB) {
                return;
            }
            LiveRecordFile candidate = oldestCompressible();
            if (candidate == null) {
                log.info("录播超压缩水位（{} MB 已用 / {} MB 水位），但没有可压缩的场次"
                        + "（都压过了、被标记保留、或不是正常录完的）", used / MB, compressMb);
                return;
            }
            if (!compressOne(candidate)) {
                log.warn("压缩录播 {} 失败，停止本轮压缩（下一轮巡检会再试）", candidate.getFid());
                return;
            }
        }
    }

    /** 最老的、还没压过的正常录完的场次。 */
    private LiveRecordFile oldestCompressible() {
        List<LiveRecordFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getStatus, ST_DONE)
                .eq(LiveRecordFile::getCompressed, 0)
                .eq(LiveRecordFile::getKeep, 0)
                .orderByAsc(LiveRecordFile::getStartTime)
                .last("LIMIT 20"));
        for (LiveRecordFile row : candidates) {
            Path file = resolveRelative(row.getPath());
            if (file != null && Files.isRegularFile(file)) {
                return row;
            }
        }
        return null;
    }

    /**
     * 压缩一场：降到目标分辨率 + H.265，成功后<b>删掉源 flv</b>（否则等于没省空间）。
     *
     * @return 是否成功
     */
    private boolean compressOne(LiveRecordFile row) {
        String ffmpeg = ffmpegPath();
        Path src = resolveRelative(row.getPath());
        if (ffmpeg == null || src == null || !Files.isRegularFile(src)) {
            return false;
        }
        int height = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_HEIGHT,
                LoadDSConfig.DEFAULT_RECORD_COMPRESS_HEIGHT);
        int crf = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_CRF,
                LoadDSConfig.DEFAULT_RECORD_COMPRESS_CRF);
        String preset = loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_PRESET,
                LoadDSConfig.DEFAULT_RECORD_COMPRESS_PRESET);

        Path dst = src.resolveSibling("compressed.mp4");
        long srcBytes = sizeOf(src);
        // 压缩期间 src 和 dst 同时存在 ⇒ 峰值要 src + dst（约 0.5×src）。
        // 先确认盘上真的放得下，否则"压到一半 ENOSPC"会留下一个半截文件 + 一份完好的源，最糟。
        long freeBytes = freeSpaceBytes(src.getParent());
        if (freeBytes >= 0 && freeBytes < srcBytes * 3 / 4 + 512 * MB) {
            log.warn("磁盘剩余 {} MB，不足以安全压缩 {} MB 的录播 {}（压缩期间要同时存源与产物）⇒ 跳过压缩",
                    freeBytes / MB, srcBytes / MB, row.getFid());
            return false;
        }

        long durationSeconds = (row.getDurationMs() == null ? 0 : row.getDurationMs()) / 1000;
        // 超时给得极宽（4 倍实时 + 1 小时）：这条命令本来就慢，
        // 太紧会把"正在正常压缩"误判成卡死；太松又怕真卡住。4 倍实时是 x265 在 4 核上的宽松上界。
        long timeoutSeconds = Math.max(3600, durationSeconds * 4 + 3600);

        log.info("开始压缩录播 {}（房间 {}）：{} MB，目标高度 {}，CRF {}，preset {}，"
                        + "预计很慢（纯 CPU，4 核机上可能数小时），期间不影响录制与推送",
                row.getFid(), row.getRoomId(), srcBytes / MB,
                height <= 0 ? "保持原分辨率" : String.valueOf(height), crf, preset);
        long started = System.currentTimeMillis();
        LiveRecorder.Result result = recorder.run(
                recorder.compressCommand(ffmpeg, src, dst, height, crf, preset),
                src.getParent().resolve("compress.log"), dst, timeoutSeconds, null);

        long dstBytes = sizeOf(dst);
        if (!result.ok() || dstBytes <= 0) {
            log.warn("压缩失败（ffmpeg exit={}，产物 {} 字节），源文件原样保留", result.exitCode(), dstBytes);
            deleteQuietly(dst);
            return false;
        }
        if (dstBytes >= srcBytes) {
            // 压完反而更大：说明源本来就很省（或参数选得不对）。留源、删产物，
            // 并把 compressed 置 1，免得之后每一轮巡检都拿它重压一遍。
            log.warn("压缩后体积没有下降（{} MB → {} MB），保留原文件并标记为已压缩以免反复重压。"
                            + "可考虑调大 {} / 调整 preset",
                    srcBytes / MB, dstBytes / MB, LoadDSConfig.KEY_BILI_RECORD_COMPRESS_CRF);
            deleteQuietly(dst);
            fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                    .eq(LiveRecordFile::getFid, row.getFid())
                    .set(LiveRecordFile::getCompressed, 1)
                    .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
            return true;
        }

        deleteQuietly(src);
        long size = dirSize(sessionDir(row));
        fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getFid, row.getFid())
                .set(LiveRecordFile::getPath, relative(dst))
                .set(LiveRecordFile::getSizeBytes, size)
                .set(LiveRecordFile::getStatus, ST_COMPRESSED)
                .set(LiveRecordFile::getCompressed, 1)
                .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
        log.info("压缩完成：录播 {} —— {} MB → {} MB（省 {}%），耗时 {} 分钟",
                row.getFid(), srcBytes / MB, size / MB,
                srcBytes > 0 ? (100 - size * 100 / srcBytes) : 0,
                (System.currentTimeMillis() - started) / 60000);
        return true;
    }

    /**
     * 按磁盘实况修正 {@code size_bytes}。
     *
     * <p>为什么需要它：{@code size_bytes} 是容量加减的真相源，但它只在"录制结束/压缩完成"时写一次。
     * 用户手工删了文件、或者把某个目录搬走了，统计就会一直虚高，而虚高的后果是
     * <b>白白删掉本来不用删的录播</b> —— 这个方向的错误代价太高，所以每轮巡检都校正一次。
     *
     * <p>⚠️ 刻意<b>不</b>因为"目录不存在"就把记录标删：那等于凭一次文件系统抖动丢掉整条历史。
     * 这里只把体积改成 0 并告警，删不删由人来定（或由上面那条真正按水位走的淘汰来做）。
     */
    private void refreshSizes() {
        List<LiveRecordFile> rows = fileMapper.selectList(new LambdaQueryWrapper<LiveRecordFile>()
                .ne(LiveRecordFile::getStatus, ST_FAILED));
        for (LiveRecordFile row : rows) {
            try {
                Path dir = sessionDir(row);
                boolean exists = dir != null && Files.isDirectory(dir);
                long actual = exists ? dirSize(dir) : 0L;
                long recorded = row.getSizeBytes() == null ? 0L : row.getSizeBytes();
                if (actual != recorded) {
                    if (!exists && recorded > 0) {
                        log.warn("录播 {}（房间 {}）的目录 {} 不存在了（被手工删过？录播目录配置改过？）"
                                        + "—— 体积按 0 计，记录保留",
                                row.getFid(), row.getRoomId(), dir);
                    }
                    fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                            .eq(LiveRecordFile::getFid, row.getFid())
                            .set(LiveRecordFile::getSizeBytes, actual)
                            .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
                }
            } catch (Throwable t) {
                log.debug("校正录播 {} 体积失败：{}", row.getFid(), t.toString());
            }
        }
    }

    // ================================================================== 查询

    @Override
    public List<LiveRecordFile> listFiles(int page, int size, Long roomIdFilter) {
        int limit = Math.min(Math.max(1, size), 50);
        int offset = Math.max(0, page - 1) * limit;
        LambdaQueryWrapper<LiveRecordFile> wrapper = new LambdaQueryWrapper<LiveRecordFile>()
                .orderByDesc(LiveRecordFile::getStartTime)
                .last("LIMIT " + limit + " OFFSET " + offset);
        if (roomIdFilter != null) {
            wrapper.eq(LiveRecordFile::getRoomId, roomIdFilter);
        }
        return fileMapper.selectList(wrapper);
    }

    @Override
    public long countFiles(Long roomIdFilter) {
        LambdaQueryWrapper<LiveRecordFile> wrapper = new LambdaQueryWrapper<>();
        if (roomIdFilter != null) {
            wrapper.eq(LiveRecordFile::getRoomId, roomIdFilter);
        }
        return fileMapper.selectCount(wrapper);
    }

    @Override
    public List<LiveRecordFile> listFilesOfRooms(int page, int size, Collection<Long> roomIds) {
        if (roomIds != null && roomIds.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(Math.max(1, size), 50);
        int offset = Math.max(0, page - 1) * limit;
        LambdaQueryWrapper<LiveRecordFile> wrapper = new LambdaQueryWrapper<LiveRecordFile>()
                .orderByDesc(LiveRecordFile::getStartTime)
                .last("LIMIT " + limit + " OFFSET " + offset);
        if (roomIds != null) {
            wrapper.in(LiveRecordFile::getRoomId, roomIds);
        }
        return fileMapper.selectList(wrapper);
    }

    @Override
    public long countFilesOfRooms(Collection<Long> roomIds) {
        if (roomIds != null && roomIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<LiveRecordFile> wrapper = new LambdaQueryWrapper<>();
        if (roomIds != null) {
            wrapper.in(LiveRecordFile::getRoomId, roomIds);
        }
        return fileMapper.selectCount(wrapper);
    }

    @Override
    public long usedBytesOfRooms(Collection<Long> roomIds) {
        if (roomIds != null && roomIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<LiveRecordFile> wrapper = new LambdaQueryWrapper<LiveRecordFile>()
                .ne(LiveRecordFile::getStatus, ST_FAILED);
        if (roomIds != null) {
            wrapper.in(LiveRecordFile::getRoomId, roomIds);
        }
        long sum = 0;
        for (LiveRecordFile row : fileMapper.selectList(wrapper)) {
            sum += row.getSizeBytes() == null ? 0 : row.getSizeBytes();
        }
        return sum;
    }

    @Override
    public Path fileOf(LiveRecordFile row) {
        if (row == null) {
            return null;
        }
        Path file = resolveRelative(row.getPath());
        return file != null && Files.isRegularFile(file) ? file : null;
    }

    @Override
    public LiveRecordFile findFile(Long fid) {
        if (fid == null) {
            return null;
        }
        return fileMapper.selectById(fid);
    }

    @Override
    public boolean setKeep(Long fid, boolean keep) {
        LiveRecordFile row = findFile(fid);
        if (row == null) {
            return false;
        }
        fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getFid, fid)
                .set(LiveRecordFile::getKeep, keep ? 1 : 0)
                .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
        return true;
    }

    @Override
    public boolean deleteRecord(Long fid) {
        LiveRecordFile row = findFile(fid);
        if (row == null) {
            return false;
        }
        if (isRecording(row.getRoomId())) {
            log.warn("拒绝删除正在录制的场次 {}（房间 {}）", fid, row.getRoomId());
            return false;
        }
        Path dir = sessionDir(row);
        deleteRecursively(dir);
        fileMapper.deleteById(fid);
        return true;
    }

    @Override
    public List<LiveRecordFile> listActive() {
        if (active.isEmpty()) {
            return List.of();
        }
        List<Long> fids = new ArrayList<>();
        for (Session session : active.values()) {
            if (session.row.getFid() != null) {
                fids.add(session.row.getFid());
            }
        }
        if (fids.isEmpty()) {
            return List.of();
        }
        // selectByIds 而不是 selectBatchIds：后者在 MyBatis-Plus 3.5.7 里已标记过时
        return fileMapper.selectByIds(fids);
    }

    // ================================================================== 统计

    @Override
    public long usedBytes() {
        List<LiveRecordFile> rows = fileMapper.selectList(new LambdaQueryWrapper<LiveRecordFile>()
                .ne(LiveRecordFile::getStatus, ST_FAILED));
        long sum = 0;
        for (LiveRecordFile row : rows) {
            sum += row.getSizeBytes() == null ? 0 : row.getSizeBytes();
        }
        return sum;
    }

    @Override
    public String summary() {
        long used = usedBytes();
        long compressMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_TOTAL_MB,
                LoadDSConfig.DEFAULT_RECORD_COMPRESS_TOTAL_MB);
        long hardMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_HARD_TOTAL_MB,
                LoadDSConfig.DEFAULT_RECORD_HARD_TOTAL_MB);
        long count = countFiles(null);
        long keepCount = fileMapper.selectCount(new LambdaQueryWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getKeep, 1));
        long freeMb = freeSpaceMb(recordRoot());
        StringBuilder sb = new StringBuilder();
        sb.append("已用 ").append(humanBytes(used)).append(" / ").append(count).append(" 场")
                .append("（其中保留 ").append(keepCount).append(" 场）\n");
        sb.append("压缩水位 ").append(compressMb <= 0 ? "已关闭" : humanMb(compressMb))
                .append("　删除水位 ").append(hardMb <= 0 ? "已关闭" : humanMb(hardMb)).append("\n");
        sb.append("磁盘剩余 ").append(freeMb < 0 ? "未知" : humanMb(freeMb))
                .append("（下限 ").append(humanMb(loadDSConfig.intOf(
                        LoadDSConfig.KEY_BILI_RECORD_DISK_MIN_FREE_MB,
                        LoadDSConfig.DEFAULT_RECORD_DISK_MIN_FREE_MB))).append("）\n");
        sb.append("正在录制 ").append(active.size()).append(" 路");
        if (compressing.get()) {
            sb.append("，后台压缩进行中");
        }
        return sb.toString();
    }

    @Override
    public boolean enabled() {
        return loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_RECORD_ENABLED);
    }

    @Override
    public String serverInfo() {
        return fileServer.describe();
    }

    @Override
    public boolean isRecording(Long roomId) {
        return roomId != null && active.containsKey(roomId);
    }

    // ================================================================== 交付

    @Override
    public String deliver(Bot bot, Long groupId, Long qqUid, LiveRecordFile row) {
        Path file;
        try {
            file = prepareMp4(row);
        } catch (IOException e) {
            return "❌ 准备文件失败：" + e.getMessage();
        }
        long bytes = sizeOf(file);
        String name = displayName(row, file);

        Delivery delivery = resolveDelivery(file);
        if (delivery.refuse() != null) {
            return "❌ " + delivery.refuse();
        }
        ActionRaw result = upload(bot, groupId, qqUid, delivery.argument(), name);
        String outcome = describeUpload(result);
        if (outcome != null) {
            // 失败时给出**下一步该做什么** —— 这个功能最常见的坑就是"NapCat 看不到这个文件"，
            // 而那句报错本身完全不会提示这一点
            StringBuilder hint = new StringBuilder("❌ 发送失败：").append(outcome).append("\n");
            hint.append("当前交付方式：").append(delivery.mode()).append("（")
                    .append(delivery.argument()).append("）\n");
            if ("local".equals(delivery.mode())) {
                hint.append("最常见的原因：NapCat 与机器人**不共享文件系统**"
                        + "（比如 NapCat 跑在 Docker 容器里）⇒ 它打不开这个本地路径。\n"
                        + "修法：配置 ").append(LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL)
                        .append(fileServer.port() > 0
                                ? "（例如 http://172.17.0.1:" + fileServer.port() + "）"
                                : "")
                        .append("或把 ").append(LoadDSConfig.KEY_BILI_RECORD_DELIVERY_MODE)
                        .append(" 设为 url。");
            } else if ("url".equals(delivery.mode())) {
                hint.append("NapCat 拉不到这个地址 ⇒ 基址选错了。用「录播地址」命令看候选，"
                        + "然后把正确的那条配到 ").append(LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL);
            }
            return hint.toString();
        }
        return "✅ 已发送「" + name + "」（" + humanBytes(bytes) + "，"
                + "交付方式 " + delivery.mode()
                + ("url".equals(delivery.mode()) ? "：" + delivery.argument() : "") + "）";
    }

    /**
     * 取一场录播可以<b>直接播</b>的文件：不是 mp4 就先就地转（见 {@link #prepareMp4}）。
     *
     * <p>给「录播网页」用 —— 录下来的是 flv，浏览器放不了。
     *
     * <p>锁的形状刻意是"全局一把 + 锁内重新读一次记录"：
     * <ul>
     *   <li><b>全局一把</b>：转 mp4 是就地替换（删源、改库），同一场并发转会互相踩；
     *       为什么不为每场分锁见 {@code remuxLock} 的说明；</li>
     *   <li><b>锁内重新读记录</b>：外面传进来的 {@code row} 可能是在排队时抓的旧快照 ——
     *       排在前面的那个请求可能已经把它转成 mp4 了。不重读的话会拿一个已经过时的
     *       {@code path} 去转，轻则白转一次，重则报"文件不存在"。</li>
     * </ul>
     */
    @Override
    public Path playableFile(Long fid) throws IOException {
        LiveRecordFile snapshot = findFile(fid);
        if (snapshot == null) {
            throw new IOException("找不到编号 " + fid + " 的录播（可能已被容量巡检清理）");
        }
        if (ST_FAILED.equalsIgnoreCase(snapshot.getStatus())) {
            throw new IOException("编号 " + fid + " 这一场没录到文件");
        }
        synchronized (remuxLock) {
            LiveRecordFile fresh = findFile(fid);
            if (fresh == null) {
                throw new IOException("编号 " + fid + " 的录播刚刚被删掉了");
            }
            return prepareMp4(fresh);
        }
    }

    /**
     * 准备可交付的文件：<b>不是 mp4 就转成 mp4，并就地替换</b>。
     *
     * <p>为什么就地替换而不是"另存一份 mp4"：那样同一场录播会占两份空间，
     * 直接顶到水位、触发淘汰 —— 而用户要的只是"能播的那个文件"。
     * 转 mp4 是 {@code -c copy}（不重编码），所以丢掉 flv 不损失任何东西。
     */
    private Path prepareMp4(LiveRecordFile row) throws IOException {
        Path current = resolveRelative(row.getPath());
        if (current == null || !Files.isRegularFile(current)) {
            throw new IOException("文件不存在（可能已被容量巡检清理，或录播目录被改过）");
        }
        if (current.getFileName().toString().toLowerCase().endsWith(".mp4")) {
            return current;
        }
        String ffmpeg = ffmpegPath();
        if (ffmpeg == null) {
            throw new IOException("ffmpeg 未就绪，无法转 mp4");
        }
        FileHeader header = sniffHeader(current);
        Path target = current.resolveSibling("out.mp4");

        long budgetSeconds = Math.max(600,
                (row.getDurationMs() == null ? 0 : row.getDurationMs()) / 1000 + 600);
        LiveRecorder.Result result = recorder.run(
                recorder.remuxMp4Command(ffmpeg, current, target),
                current.getParent().resolve("remux.log"), target, budgetSeconds, null);

        long sourceBytes = sizeOf(current);
        long targetBytes = sizeOf(target);
        // 体积合理性检查：-c copy 只是换容器，产物不该比源小太多。
        // 显著偏小意味着"转了一半就断了"，这时宁可留着源也不要一个残缺的 mp4。
        if (!result.ok() || targetBytes <= 0 || (sourceBytes > 0 && targetBytes < sourceBytes / 2)) {
            deleteQuietly(target);
            throw new IOException(String.format(
                    "转 mp4 失败（ffmpeg exit=%d，源 %d 字节 → 产物 %d 字节）",
                    result.exitCode(), sourceBytes, targetBytes));
        }

        deleteQuietly(current);
        long size = dirSize(sessionDir(row));
        fileMapper.update(null, new LambdaUpdateWrapper<LiveRecordFile>()
                .eq(LiveRecordFile::getFid, row.getFid())
                .set(LiveRecordFile::getPath, relative(target))
                .set(LiveRecordFile::getSizeBytes, size)
                .set(LiveRecordFile::getUpdateTime, System.currentTimeMillis()));
        row.setPath(relative(target));
        row.setSizeBytes(size);
        log.info("已把录播 {} 由 {} 转成 mp4（{} MB，仅重封装未重编码）",
                row.getFid(), header.description(), size / MB);
        return target;
    }

    /** 交付方式的解析结果。 */
    private record Delivery(String mode, String argument, String refuse) {
    }

    /** 决定走哪种交付方式，并准备对应的 {@code file} 参数。 */
    private Delivery resolveDelivery(Path file) {
        String mode = loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_DELIVERY_MODE,
                LoadDSConfig.DEFAULT_RECORD_DELIVERY_MODE).toLowerCase();
        if ("auto".equals(mode)) {
            // 能推断出 NapCat 可达的基址就走 URL；推断不出才退回本地路径
            mode = fileServer.available() ? "url" : "local";
        }
        switch (mode) {
            case "local":
                return new Delivery("local", file.toAbsolutePath().toString(), null);
            case "url": {
                String url = fileServer.publish(file, file.getFileName().toString());
                if (url == null) {
                    // 明确要求 url 却发不出去时**不静默降级**：那会变成"NapCat 拿到一个
                    // 它打不开的路径"，报错点离真因很远。宁可直说。
                    return new Delivery("url", "-", "内置录播文件服务不可用（无可用基址或未启动）。"
                            + "请配置 " + LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL
                            + "，或用「录播地址」命令查看候选地址。");
                }
                return new Delivery("url", url, null);
            }
            case "base64": {
                long maxMb = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_BASE64_MAX_MB,
                        LoadDSConfig.DEFAULT_RECORD_BASE64_MAX_MB);
                long bytes = sizeOf(file);
                if (bytes > maxMb * MB) {
                    return new Delivery("base64", "-", String.format(
                            "文件 %s 超过 base64 上限 %d MB（base64 会把文件放大 1/3 并整个读进内存，"
                                    + "大文件会把两端一起搞崩）⇒ 请改用 url 方式",
                            humanBytes(bytes), maxMb));
                }
                try {
                    byte[] data = Files.readAllBytes(file);
                    String encoded = Base64.getEncoder().encodeToString(data);
                    return new Delivery("base64", "base64://" + encoded, null);
                } catch (IOException e) {
                    return new Delivery("base64", "-", "读取文件失败：" + e.getMessage());
                }
            }
            default:
                return new Delivery("local", file.toAbsolutePath().toString(), null);
        }
    }

    /** 真正发出去。群走群文件、私聊走私聊文件。 */
    private ActionRaw upload(Bot bot, Long groupId, Long qqUid, String fileArgument, String name) {
        try {
            if (groupId != null) {
                return bot.uploadGroupFile(groupId, fileArgument, name);
            }
            return bot.uploadPrivateFile(qqUid, fileArgument, name);
        } catch (Throwable t) {
            log.error("上传录播文件失败：{}", t, t);
            return null;
        }
    }

    /** 返回 {@code null} 表示成功，否则返回失败描述。 */
    private static String describeUpload(ActionRaw result) {
        if (result == null) {
            return "接口无响应";
        }
        boolean ok = (result.getRetCode() != null && result.getRetCode() == 0)
                || "ok".equalsIgnoreCase(result.getStatus());
        if (ok) {
            return null;
        }
        return "retcode=" + result.getRetCode() + "，status=" + result.getStatus();
    }

    /** 交付时的文件名：主播-时间-序号.mp4（带上 fid 保证唯一、也方便对照列表编号）。 */
    private String displayName(LiveRecordFile row, Path file) {
        boolean mp4 = file.getFileName().toString().toLowerCase().endsWith(".mp4");
        return suggestedFileName(row, mp4);
    }

    @Override
    public String suggestedFileName(LiveRecordFile row, boolean mp4) {
        String ext = mp4 ? ".mp4" : ".flv";
        String who = row.getUname() == null || row.getUname().isBlank()
                ? "房间" + row.getRoomId()
                : row.getUname();
        String stamp = row.getStartTime() == null ? "unknown"
                : STAMP.format(Instant.ofEpochMilli(row.getStartTime()));
        return sanitizeFileName(who + "-" + stamp + "-" + row.getFid() + ext);
    }

    /** 文件名里的路径分隔符与控制字符必须去掉，否则 NapCat 那边会当成路径处理。 */
    private static String sanitizeFileName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(c == '/' || c == '\\' || c < 32 || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' ? '_' : c);
        }
        return sb.toString();
    }

    /** 文件头几个字节，只为了日志里说清楚"原来是什么格式"。 */
    private record FileHeader(String description) {
    }

    private static FileHeader sniffHeader(Path file) {
        try {
            byte[] head = new byte[4];
            try (var in = Files.newInputStream(file)) {
                int read = in.read(head);
                if (read >= 3 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V') {
                    return new FileHeader("flv");
                }
            }
        } catch (IOException ignored) {
            // 读不到就按 flv 描述即可，这里只是日志文案
        }
        return new FileHeader("flv");
    }

    // ================================================================== 工具

    /** 录播根目录（绝对化、归一化）。 */
    private Path recordRoot() {
        return Paths.get(loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_DIR,
                LoadDSConfig.DEFAULT_RECORD_DIR)).toAbsolutePath().normalize();
    }

    /** 一场录制的目录：{@code <根>/<roomId>/<fid>}。 */
    private Path sessionDir(LiveRecordFile row) {
        return recordRoot().resolve(String.valueOf(row.getRoomId())).resolve(String.valueOf(row.getFid()));
    }

    /** 把库里存的相对路径还原成绝对路径；越界（不是录播根下的）返回 {@code null}。 */
    private Path resolveRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = recordRoot();
        Path resolved = root.resolve(relativePath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    /** 转成相对录播根的路径（入库用）。 */
    private String relative(Path file) {
        return recordRoot().relativize(file.toAbsolutePath().normalize()).toString();
    }

    private static long sizeOf(Path file) {
        if (file == null) {
            return 0;
        }
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static long dirSize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        long sum = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList()) {
                if (Files.isRegularFile(p)) {
                    try {
                        sum += Files.size(p);
                    } catch (IOException ignored) {
                        // 单个文件读不到就跳过，不影响整体统计
                    }
                }
            }
        } catch (IOException e) {
            return sum;
        }
        return sum;
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                deleteQuietly(p);
            }
        } catch (IOException e) {
            log.warn("删除目录 {} 失败：{}", dir, e.toString());
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删除 {} 失败：{}", file, e.toString());
        }
    }

    /** 列出某个场次目录里的分片（按名字排序，保证顺序与录制顺序一致）。 */
    private List<Path> listParts(Path dir) {
        List<Path> parts = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return parts;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("part\\d+\\.flv"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(parts::add);
        } catch (IOException e) {
            log.debug("列出分片失败 {}：{}", dir, e.toString());
        }
        return parts;
    }

    private long readDuration(Path file) {
        String ffmpeg = ffmpegPath();
        if (ffmpeg == null) {
            return 0;
        }
        return recorder.probeDurationMillis(recorder.ffprobeOf(ffmpeg), file);
    }

    /** 磁盘可用字节；取不到返回 -1。 */
    private static long freeSpaceBytes(Path path) {
        try {
            Path probe = path;
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return -1;
            }
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long freeSpaceMb(Path path) {
        long bytes = freeSpaceBytes(path);
        return bytes < 0 ? -1 : bytes / MB;
    }

    /** 当前可用的 ffmpeg 路径；未就绪返回 {@code null}。 */
    private String ffmpegPath() {
        return ffmpegProvider.isReady() ? ffmpegProvider.executable().orElse(null) : null;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String humanMb(long mb) {
        return mb < 1024 ? mb + " MB" : String.format("%.2f GB", mb / 1024.0);
    }

    /**
     * 反斜杠换正斜杠。
     *
     * <p>只用在<b>要给 ffmpeg 看的路径</b>上（concat 清单）：清单里的
     * {@code file '<路径>'} 是单引号串，<b>认 {@code \} 为转义字符</b> ——
     * Windows 的 {@code E:\a\b.flv} 会被啃成 {@code E:ab.flv}，
     * 于是合并命令报"找不到文件"，而报错离真正的原因（就这一个字符）很远。
     * ffmpeg 两个平台都认正斜杠，统一成 {@code /} 是零代价的。
     */
    private static String slash(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < MB) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * MB) {
            return String.format("%.1f MB", bytes / (double) MB);
        }
        return String.format("%.2f GB", bytes / (double) (1024 * MB));
    }

    /**
     * 一场录制的会话状态。可变、只在 {@link #runSession} 那个线程里读写
     * （{@code process} / {@code cancelled} 是 volatile —— 取消可能来自另一个线程）。
     */
    private static final class Session {

        final LiveRecordFile row;
        final LiveRecordSub sub;
        final Path dir;
        final long startMs = System.currentTimeMillis();
        final List<Path> parts = new ArrayList<>();

        volatile Process process;
        volatile boolean cancelled;
        volatile Path currentPart;
        volatile Integer quality;

        /** 结束原因，写进日志用（照抄 {@code PushInfoServiceImpl} 的 {@code reason} 那个思路） */
        volatile String note = "";

        Session(LiveRecordFile row, LiveRecordSub sub, Path dir) {
            this.row = row;
            this.sub = sub;
            this.dir = dir;
        }
    }

    /** 供命令回显：当前生效的关键参数（人排障时一眼看全）。 */
    public String effectiveConfig() {
        return String.format(
                "总闸=%s，目录=%s，检测间隔=%ds，单场上限=%dmin，并发=%d，磁盘下限=%dMB%n"
                        + "压缩水位=%dMB（高度 %d / CRF %d / preset %s），硬水位=%dMB%n"
                        + "交付方式=%s，基址=%s",
                enabled() ? "开" : "关",
                recordRoot(),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_CHECK_SECONDS,
                        LoadDSConfig.DEFAULT_RECORD_CHECK_SECONDS),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_MAX_SECONDS,
                        LoadDSConfig.DEFAULT_RECORD_MAX_SECONDS) / 60,
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_MAX_CONCURRENT,
                        LoadDSConfig.DEFAULT_RECORD_MAX_CONCURRENT),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_DISK_MIN_FREE_MB,
                        LoadDSConfig.DEFAULT_RECORD_DISK_MIN_FREE_MB),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_TOTAL_MB,
                        LoadDSConfig.DEFAULT_RECORD_COMPRESS_TOTAL_MB),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_HEIGHT,
                        LoadDSConfig.DEFAULT_RECORD_COMPRESS_HEIGHT),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_CRF,
                        LoadDSConfig.DEFAULT_RECORD_COMPRESS_CRF),
                loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_COMPRESS_PRESET,
                        LoadDSConfig.DEFAULT_RECORD_COMPRESS_PRESET),
                loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_HARD_TOTAL_MB,
                        LoadDSConfig.DEFAULT_RECORD_HARD_TOTAL_MB),
                loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_DELIVERY_MODE,
                        LoadDSConfig.DEFAULT_RECORD_DELIVERY_MODE),
                Objects.requireNonNullElse(fileServer.baseUrl(), "(推断不出)"));
    }
}
