package com.esdllm.botPlugins;

import com.esdllm.common.BotAdminChecker;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.model.LiveRecordFile;
import com.esdllm.model.LiveRecordSub;
import com.esdllm.service.LiveRecordService;
import com.esdllm.service.RecordWebAuth;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 直播录制（录播）的<b>命令入口与调度</b>。
 *
 * <p>真正的活在 {@code service/LiveRecordServiceImpl} 里，这里只做三件事：
 * 把聊天消息翻译成服务调用、把定时器接上、把结果回给用户。
 *
 * <p><b>支持的命令</b>：
 * <pre>
 * ── 订阅（群内管理员；决定"录谁"，影响磁盘与带宽）
 * 录播订阅 1024                加订阅（自动开录）—— 记到<b>本群</b>
 * 录播订阅列表                 看本群盯了哪些房间（私聊里则是全部）
 * 录播取消订阅 1024            取消订阅（已录好的文件保留）
 * 录播开关 1024 关             暂停/恢复某个订阅的自动录制
 *
 * ── 取用（群内管理员）
 * 录播列表 [页码]              列已录好的场次（编号 = fid）
 * 录播下载 12                  转 mp4 并发到本群 / 私聊
 * 录播保留 12 / 录播取消保留 12  打标记：既不参与压缩也不参与淘汰
 * 删除录播 12                  删这一场（连文件一起）
 * 录播状态                     占用、水位、正在录几路
 * 录播地址                     NapCat 该用哪个地址来取文件（排障用）
 *
 * ── 网页预览（botadmin 及以上）
 * 录播网页                     回本群的网页链接（第一次用会自动生成令牌）
 * 录播网页重置                 重置本群令牌 —— 链接被转到群外了就重置它
 * 录播管理码                   私聊回管理码（网页上改保留 / 删录播要用）
 * 录播管理码重置               只给机器人所有者：一次让所有网页管理权失效
 *
 * ── 运维（仅机器人所有者，动的是全机器人的容量）
 * 录播整理                     立刻跑一次容量整理（压缩 / 淘汰）
 * </pre>
 *
 * <h2>权限为什么分成四档</h2>
 * <p>按"这个操作的影响面"选，不按"谁发起的"选：
 * <ul>
 *   <li><b>{@link Level#GROUP}</b>（群内管理员；私聊则要求所有者）—— 所有"作用在某个群上"的操作：
 *       订阅增删、列表、下载、保留、删除。群里由 {@link BotAdminChecker#isAdmin} 判
 *       （群主/群管理员/白名单），私聊里没有"群"这回事，只能由所有者做全局意义上那件事。
 *       <p>⚠️ 这比录播第一版<b>收紧了</b>：原来取用类走的是 {@code isAdmin}，而它<b>私聊一律放行</b>
 *       ⇒ 任何能私聊到机器人的人都能「删除录播」。功能语义是"本群的录播"，私聊里没有"本群"，
 *       所以改成要所有者。代价是群成员不能私下找机器人要一份录播了 —— 要放开就改这一档。</li>
 *   <li><b>{@link Level#BOT_ADMIN}</b> —— "把本群的东西发到浏览器上"这类<b>对外的口子</b>：
 *       取网页链接、拿管理码。刻意不给群主/群管理员：{@code isAdmin} 里的"群主"是 QQ 平台身份，
 *       而这两个动作会生成<b>长期有效、可转发</b>的凭证。</li>
 *   <li><b>{@link Level#OWNER}</b> —— 影响全局的唯一一份东西：容量整理（动全机器人的磁盘）、
 *       重置管理码（让所有人的网页管理权一起失效）。</li>
 * </ul>
 *
 * <p><b>为什么下载要丢到线程里</b>：{@code deliver} 里先要把 flv 转 mp4、再上传，
 * 一个几 GB 的文件在消息处理的线程里同步做，会把这条连接卡到超时 ——
 * 用户看到的是"机器人不回话"，而实际上它正忙着。所以先回一句"在准备了"，
 * 再用单线程池排队去做（单线程同时兼作串行化：不让几次下载同时抢 IO 和 CPU）。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Component
@Shiro
public class LiveRecordPlugins {

    /** 允许出现在命令前的 CQ 码（@机器人 / 表情等），匹配时忽略 */
    private static final String CQ = "(\\[CQ:[^]]*\\]\\s*)*";

    /** 房间号 / 编号：正整数，长度设上限纯粹是防呆 */
    private static final String ID = "\\d{1,15}";

    /**
     * 各命令的匹配式。
     *
     * <p>⚠️ <b>它们必须两两互斥</b>：shiro 是"每个 handler 各自带 filter"，
     * 一条消息可以同时命中多个 handler，且判定用的是 {@code Matcher#matches()}
     * （<b>整串匹配</b>，不是 {@code find()}）。所以每条都锚 {@code $}，且在命令词之后
     * 用数字 / 空白这种具体形状收尾 —— 例如「录播订阅」要求后面必须直接跟数字，
     * 于是「录播订阅列表」不会顺带触发它。
     *
     * <p>不这么写的代价很实在：「录播订阅列表」会先被当成「录播订阅」解析出
     * "列表"这个非数字参数，回一句格式错误，然后才轮到列表 handler —— 用户收到两条回复。
     *
     * <p>结尾统一留 {@code \\s*}：人打字常带尾随空格，而 {@code matches()} 是全串匹配，
     * 不留这点余量就会因为一个空格静默不响应。
     */

    // ── 订阅（群内管理员；群里记到本群，私聊里是全局订阅）
    private static final String CMD_SUB_ADD = "(?is)^" + CQ + "录播订阅\\s+" + ID + "\\s*$";
    private static final String CMD_SUB_LIST = "(?is)^" + CQ + "录播订阅列表\\s*$";
    private static final String CMD_SUB_DEL = "(?is)^" + CQ + "录播取消订阅\\s+" + ID + "\\s*$";
    private static final String CMD_SUB_SWITCH = "(?is)^" + CQ + "录播开关\\s+" + ID + "\\s+\\S+\\s*$";

    // ── 取用（群内管理员）
    private static final String CMD_FILE_LIST = "(?is)^" + CQ + "录播列表(?:\\s+" + ID + ")?\\s*$";
    private static final String CMD_FILE_GET = "(?is)^" + CQ + "录播下载\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_KEEP = "(?is)^" + CQ + "录播保留\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_UNKEEP = "(?is)^" + CQ + "录播取消保留\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_DEL = "(?is)^" + CQ + "删除录播\\s+" + ID + "\\s*$";
    private static final String CMD_STATUS = "(?is)^" + CQ + "录播状态\\s*$";
    private static final String CMD_ADDR = "(?is)^" + CQ + "录播地址\\s*$";

    // ── 网页预览（botadmin 及以上）
    private static final String CMD_WEB = "(?is)^" + CQ + "录播网页\\s*$";
    private static final String CMD_WEB_RESET = "(?is)^" + CQ + "录播网页重置\\s*$";
    private static final String CMD_WEB_CODE = "(?is)^" + CQ + "录播管理码\\s*$";
    private static final String CMD_WEB_CODE_RESET = "(?is)^" + CQ + "录播管理码重置\\s*$";

    // ── 运维（仅所有者）
    private static final String CMD_TIDY = "(?is)^" + CQ + "录播整理\\s*$";

    /** 一页显示多少场 */
    private static final int PAGE_SIZE = 8;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Resource
    private LiveRecordService liveRecordService;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private RecordWebAuth recordWebAuth;

    /**
     * 交付队列：<b>单线程</b>。
     *
     * <p>单线程是刻意的 —— 转 mp4 与上传都是重 IO，并发跑既抢带宽又抢磁盘，
     * 排队反而是最快的。而且它只在用户主动点「录播下载」时才会被用到，常态空转。
     */
    private final ExecutorService deliverPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-record-deliver");
        t.setDaemon(true);
        return t;
    });

    /** 容量巡检重入闸（与推送插件同一口径：上一轮没完就别叠下一轮） */
    private final AtomicBoolean maintainRunning = new AtomicBoolean(false);

    @PreDestroy
    public void stop() {
        deliverPool.shutdownNow();
    }

    // ================================================================== 命令：订阅

    /** 加录播订阅：`录播订阅 1024` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_ADD, at = AtEnum.BOTH)
    public void subAdd(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long roomId = parseId(argsAfter(body(event.getMessage()), "录播订阅"));
            if (roomId == null) {
                send(bot, event, "格式：录播订阅 房间号（例如 录播订阅 1024）");
                return;
            }
            Long groupId = event.getGroupId();
            LiveRecordSub exists = liveRecordService.findSub(roomId, groupId);
            // 落库之前先问一句"这个房间本来有没有人订" —— 落库之后答案就永远是有，问不出差别了
            boolean firstSubscriber = !liveRecordService.isSubscribed(roomId);
            boolean recordingAlready = liveRecordService.isRecording(roomId);
            LiveRecordSub sub;
            try {
                sub = liveRecordService.addSub(roomId, groupId);
            } catch (Exception e) {
                // addSub 里取 uid/昵称是"失败不阻断"的，能抛到这里的只有入参非法
                send(bot, event, "❌ 加订阅失败：" + e.getMessage());
                return;
            }
            String who = describe(sub);
            if (exists != null) {
                send(bot, event, "房间 " + roomId + "（" + who + "）已经在" + scopeWord(groupId) + "订阅里了");
                return;
            }
            StringBuilder sb = new StringBuilder("✅ 已订阅 " + roomId + "（" + who + "），开播后会开始录");
            if (sub.getUname() == null || sub.getUname().isBlank()) {
                sb.append("\n（没取到主播昵称 —— 可能是网络问题，不影响录制）");
            }
            if (!firstSubscriber || recordingAlready) {
                // 说清楚"不会重复录"：订阅是按群存的，同一房间别人订着的时候
                // 用户很容易以为"那我再加一条是不是会录两份"
                sb.append("\n（这个房间已经有别的订阅在录了 —— 同一场只会有一份文件，这个群看得到）");
            }
            if (groupId != null) {
                sb.append("\n群成员看回放：发「录播网页」拿链接。");
            }
            send(bot, event, sb.toString());
        });
    }

    /** 看订阅列表：`录播订阅列表`（群里只看本群；私聊里是全局订阅） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_LIST, at = AtEnum.BOTH)
    public void subList(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long groupId = event.getGroupId();
            List<LiveRecordSub> subs = liveRecordService.listSubs(groupId);
            if (subs.isEmpty()) {
                send(bot, event, groupId == null
                        ? "还没有全局录播订阅。用「录播订阅 房间号」加一个（私聊里加的订阅归属全局）。"
                        : "本群还没有录播订阅。用「录播订阅 房间号」加一个。");
                return;
            }
            StringBuilder sb = new StringBuilder("录播订阅（" + scopeWord(groupId) + " " + subs.size() + " 个）：\n");
            for (LiveRecordSub sub : subs) {
                boolean on = Integer.valueOf(1).equals(sub.getAutoRecord());
                sb.append(on ? "● " : "○ ")
                        .append(sub.getRoomId()).append("　").append(describe(sub))
                        .append(on ? "" : "　[已暂停]")
                        .append(liveRecordService.isRecording(sub.getRoomId()) ? "　⏺录制中" : "")
                        .append("\n");
            }
            sb.append("● 开录　○ 暂停");
            if (groupId != null) {
                sb.append("\n群成员看回放：发「录播网页」拿链接");
            }
            send(bot, event, sb.toString());
        });
    }

    /** 取消订阅：`录播取消订阅 1024` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_DEL, at = AtEnum.BOTH)
    public void subDel(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long roomId = parseId(argsAfter(body(event.getMessage()), "录播取消订阅"));
            if (roomId == null) {
                send(bot, event, "格式：录播取消订阅 房间号");
                return;
            }
            boolean ok = liveRecordService.removeSub(roomId, event.getGroupId());
            if (!ok) {
                send(bot, event, "没有订阅过房间 " + roomId);
                return;
            }
            send(bot, event, "✅ 已取消" + scopeWord(event.getGroupId()) + "对 " + roomId + " 的订阅。"
                    + "\n已经录好的文件都留着（也还能在这里的「录播列表」里看到），只是不再自动录新的了。");
        });
    }

    /** 暂停 / 恢复：`录播开关 1024 关` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_SWITCH, at = AtEnum.BOTH)
    public void subSwitch(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            String rest = argsAfter(body(event.getMessage()), "录播开关");
            String[] parts = rest.split("\\s+");
            Long roomId = parts.length > 0 ? parseId(parts[0]) : null;
            Boolean on = parts.length > 1 ? parseOn(parts[1]) : null;
            if (roomId == null || on == null) {
                send(bot, event, "格式：录播开关 房间号 开/关（例如 录播开关 1024 关）");
                return;
            }
            boolean ok = liveRecordService.setSubAutoRecord(roomId, event.getGroupId(), on);
            if (!ok) {
                send(bot, event, "没有订阅过房间 " + roomId + "，先用「录播订阅 " + roomId + "」加一个");
                return;
            }
            send(bot, event, (on ? "✅ 已恢复" : "⏸ 已暂停") + "房间 " + roomId + " 的自动录制");
        });
    }

    /** 手动跑一次容量整理：`录播整理` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_TIDY, at = AtEnum.BOTH)
    public void tidy(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            send(bot, event, "开始整理（超压缩水位就转 H.265，超删除水位就删最早的）…");
            submit(() -> {
                try {
                    liveRecordService.maintain();
                    safeSend(bot, event, "整理完成。\n" + liveRecordService.summary());
                } catch (Throwable t) {
                    log.error("手动录播整理失败", t);
                    safeSend(bot, event, "整理失败：" + t.getMessage());
                }
            });
        });
    }

    // ================================================================== 命令：取用

    /** 列出录播：`录播列表 [页码]`（群里只列本群订阅的房间录出来的） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_FILE_LIST, at = AtEnum.BOTH)
    public void fileList(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            String raw = argsAfter(body(event.getMessage()), "录播列表");
            Long pageArg = raw.isEmpty() ? null : parseId(raw);
            int page = pageArg == null ? 1 : (int) Math.max(1, Math.min(pageArg, 9999));

            Collection<Long> scope = scopeFor(event);
            long total = liveRecordService.countFilesOfRooms(scope);
            if (total <= 0) {
                send(bot, event, scope == null
                        ? "还没有录到东西。用「录播订阅 房间号」加一个要录的主播。"
                        : "本群还没有录到东西。用「录播订阅 房间号」加一个要录的主播。");
                return;
            }
            long pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
            if (page > pages) {
                page = (int) pages;
            }
            List<LiveRecordFile> rows = liveRecordService.listFilesOfRooms(page, PAGE_SIZE, scope);

            StringBuilder sb = new StringBuilder();
            sb.append(scope == null ? "录播列表" : "本群录播")
                    .append("　第 ").append(page).append("/").append(pages).append(" 页")
                    .append("（共 ").append(total).append(" 场，占用 ")
                    .append(humanBytes(liveRecordService.usedBytesOfRooms(scope))).append("）\n");
            for (LiveRecordFile row : rows) {
                sb.append("#").append(row.getFid()).append("　")
                        .append(escape(row.getUname() == null || row.getUname().isBlank()
                                ? "房间" + row.getRoomId() : row.getUname()))
                        .append("　").append(fmtTime(row.getStartTime()))
                        .append("　").append(humanBytes(row.getSizeBytes() == null ? 0 : row.getSizeBytes()))
                        .append("　").append(statusText(row))
                        .append("\n");
            }
            if (pages > 1) {
                sb.append("翻页：录播列表 ").append(page < pages ? page + 1 : page - 1).append("\n");
            }
            sb.append("取用：录播下载 <编号>　保留：录播保留 <编号>　删除：删除录播 <编号>");
            if (scope != null) {
                sb.append("\n群里所有人看回放：发「录播网页」拿链接");
            }
            send(bot, event, sb.toString());
        });
    }

    /** 取用一场：`录播下载 12` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_FILE_GET, at = AtEnum.BOTH)
    public void fileGet(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long fid = parseId(argsAfter(body(event.getMessage()), "录播下载"));
            LiveRecordFile row = fid == null ? null : liveRecordService.findFile(fid);
            if (row == null) {
                send(bot, event, "找不到编号 " + fid + " 的录播。用「录播列表」看编号。");
                return;
            }
            if (inNotScope(scopeFor(event), row)) {
                // ★ 编号是自增的、能猜。群里只给"本群订阅的房间"，于是这里必须再判一次归属，
                //   否则一个群里的管理员可以用猜到的编号把别群的录播整个拉走
                send(bot, event, "编号 " + fid + " 不在本群可见范围内。用「录播列表」看本群的编号。");
                return;
            }
            if ("RECORDING".equalsIgnoreCase(row.getStatus())) {
                send(bot, event, "编号 " + fid + " 还在录着（主播没下播），等录完再取。");
                return;
            }

            Long groupId = event.getGroupId();
            Long qqUid = event.getUserId();
            long size = row.getSizeBytes() == null ? 0 : row.getSizeBytes();
            boolean needMp4 = row.getPath() == null
                    || !row.getPath().toLowerCase().endsWith(".mp4");
            send(bot, event, "开始准备 #" + fid + "（" + humanBytes(size) + "）"
                    + (needMp4 ? "，首次取用要先转成 mp4" : "")
                    + "。大文件要等一会，完成后再发给你。");

            submit(() -> {
                String result;
                try {
                    result = liveRecordService.deliver(bot, groupId, qqUid, row);
                } catch (Throwable t) {
                    log.error("交付录播 {} 失败", fid, t);
                    result = "❌ 交付失败：" + t.getMessage();
                }
                safeSend(bot, event, result);
            });
        });
    }

    /** 打保留标记：`录播保留 12` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_FILE_KEEP, at = AtEnum.BOTH)
    public void fileKeep(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long fid = parseId(argsAfter(body(event.getMessage()), "录播保留"));
            if (touchable(bot, event, fid) == null) {
                return;
            }
            boolean ok = liveRecordService.setKeep(fid, true);
            send(bot, event, ok
                    ? "🔒 #" + fid + " 已标记保留 —— 既不参与自动压缩，也不会被自动删除"
                    : "找不到编号 " + fid + " 的录播");
        });
    }

    /** 取消保留：`录播取消保留 12` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_FILE_UNKEEP, at = AtEnum.BOTH)
    public void fileUnkeep(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long fid = parseId(argsAfter(body(event.getMessage()), "录播取消保留"));
            if (touchable(bot, event, fid) == null) {
                return;
            }
            boolean ok = liveRecordService.setKeep(fid, false);
            send(bot, event, ok
                    ? "已取消 #" + fid + " 的保留标记，它会重新参与自动压缩与淘汰"
                    : "找不到编号 " + fid + " 的录播");
        });
    }

    /** 删一场：`删除录播 12` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_FILE_DEL, at = AtEnum.BOTH)
    public void fileDel(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.GROUP, () -> {
            Long fid = parseId(argsAfter(body(event.getMessage()), "删除录播"));
            LiveRecordFile row = touchable(bot, event, fid);
            if (row == null) {
                return;
            }
            if ("RECORDING".equalsIgnoreCase(row.getStatus())) {
                send(bot, event, "#" + fid + " 正在录制中，删不了。要停就用「录播开关 "
                        + row.getRoomId() + " 关」。");
                return;
            }
            boolean ok = liveRecordService.deleteRecord(fid);
            send(bot, event, ok
                    ? "🗑 已删除 #" + fid + "（" + humanBytes(row.getSizeBytes() == null
                            ? 0 : row.getSizeBytes()) + "，文件已一并清掉）"
                    : "删除失败：#" + fid + " 可能正被使用，稍后再试");
        });
    }

    /** 看状态：`录播状态`（全机器人的容量与正在录的场次 —— 运维视角，给白名单里的人） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_STATUS, at = AtEnum.BOTH)
    public void status(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> {
            StringBuilder sb = new StringBuilder(liveRecordService.summary()).append("\n");
            List<LiveRecordFile> active = liveRecordService.listActive();
            if (active.isEmpty()) {
                sb.append("当前没有正在录的房间");
            } else {
                sb.append("正在录制：");
                boolean first = true;
                for (LiveRecordFile row : active) {
                    if (!first) {
                        sb.append("、");
                    }
                    first = false;
                    sb.append("#").append(row.getFid()).append(" ")
                            .append(escape(row.getUname() == null || row.getUname().isBlank()
                                    ? "房间" + row.getRoomId() : row.getUname()))
                            .append("（").append(fmtTime(row.getStartTime())).append(" 起）");
                }
            }
            if (!liveRecordService.enabled()) {
                sb.insert(0, "⚠️ 录播总闸是关的（不会自动开录，但已有的文件照常管理）\n");
            }
            send(bot, event, sb.toString());
        });
    }

    /** 排障：NapCat 该用哪个地址来取文件。`录播地址` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_ADDR, at = AtEnum.BOTH)
    public void addr(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> send(bot, event,
                "【内置文件服务】\n" + liveRecordService.serverInfo()
                        + "\n\n【当前生效参数】\n" + liveRecordService.effectiveConfig()));
    }

    // ================================================================== 调度

    /**
     * 开播检测。
     *
     * <p>调度频率（10 秒）只是"醒来一次"，真正的检测间隔由
     * {@code config} 表的 {@code biliRecordCheckSeconds}（默认 30 秒）在服务内部节流 ——
     * 这样改配置不用等重启，也不会因为调度周期写死而改不动。
     *
     * <p>它<b>只负责"发现开播、起一场录制"</b>，起完立刻返回：真正的取流与 ffmpeg 在后台
     * worker 里跑，不占调度线程（与 {@code BiliBiliPushPlugins.livePush} 同一纪律）。
     */
    @Async
    @Scheduled(fixedRate = 10_000)
    public void recordTick() {
        try {
            liveRecordService.tick();
        } catch (Throwable e) {
            // ★ Throwable 而非 Exception：与其它异步入口同一口径，
            //   Java2D 之类的 Error 不被 catch(Exception) 兜住时会一路冲到异步异常处理器
            log.error("录播开播检测执行异常", e);
        }
    }

    /**
     * 容量巡检。
     *
     * <p>5 分钟一轮：压缩与淘汰都是"分钟级容忍"的事，没必要更勤；
     * 而 {@code maintain()} 自己带重入闸 —— 一轮里真要压缩，那是几十分钟起步的活，
     * 但压缩是在<b>另一个</b>单线程池里跑的，巡检本身仍旧秒回。
     *
     * <p>⚠️ 刻意<b>不</b>看总闸：水位管理管的是"已经躺在盘上的文件"，
     * 关掉录制不代表希望磁盘一直涨。要真的什么都不动，就把两个水位都配成 0。
     */
    @Async
    @Scheduled(fixedRate = 300_000)
    public void recordMaintain() {
        if (!maintainRunning.compareAndSet(false, true)) {
            log.warn("上一轮录播容量巡检尚未结束，跳过本轮");
            return;
        }
        try {
            liveRecordService.maintain();
        } catch (Throwable e) {
            log.error("录播容量巡检执行异常", e);
        } finally {
            maintainRunning.set(false);
        }
    }

    // ================================================================== 外壳

    /**
     * 权限档 —— <b>按"这个操作的影响面"选，不按"谁发起的"选</b>。
     *
     * <p>四档的边界（严格程度递增，每个命令只挑<b>最窄够用</b>的那一档）：
     * <ul>
     *   <li>{@link #GROUP} —— 作用在<b>某个群</b>上的操作（订阅增删、列表、下载、保留、删除）。
     *       群内由 {@link BotAdminChecker#isAdmin} 判（群主 / 群管理员 / 白名单），
     *       而<b>私聊里降级为"要所有者"</b> —— 因为私聊里没有"本群"这个上下文，
     *       私聊发起的订阅是<b>全局</b>订阅，私聊取用的文件也没有群可归属。
     *       <p>⚠️ 这条比录播第一版<b>收紧</b>了：原来取用类用 {@code isAdmin}，而它
     *       <b>私聊一律放行</b>，等于任何能给机器人发私信的人都能「删除录播」。</li>
     *   <li>{@link #BOT_ADMIN} —— {@code admin} 表白名单里的任何人（{@code BotAdminChecker#isBotAdmin}）。
     *       用在"运维视角"（录播状态 / 录播地址）与"给网页发牌子"（录播网页 / 录播管理码）上：
     *       它们要么暴露全机状态、要么生成<b>长期可转发</b>的凭证，不给群主 role。</li>
     *   <li>{@link #OWNER} —— 机器人所有者。用在<b>全机器人只有一份</b>的东西上：
     *       容量整理（动所有人的磁盘）、重置管理码（让所有人的网页管理权一起失效）。</li>
     * </ul>
     */
    private enum Level {
        GROUP,
        BOT_ADMIN,
        OWNER
    }

    /**
     * 命令的统一外壳：<b>权限判定 + 异常兜底</b>。
     *
     * <ul>
     *   <li><b>fail-closed</b>：权限判定本身抛异常（查库失败等）一律按"无权限"处理，绝不放行；</li>
     *   <li><b>异常不穿出 handler</b>：否则消息处理链路直接断掉，用户什么回复都收不到；</li>
     *   <li>只读查询也带权限门 —— 它们暴露的是"谁在用这台机器的磁盘"，
     *       在群里不该人人可见。门是"群内管理员"（{@link Level#GROUP}），不是"所有者"。</li>
     * </ul>
     */
    private void guard(Bot bot, AnyMessageEvent event, Level level, Action action) {
        boolean allowed;
        try {
            allowed = check(event, level);
        } catch (Exception e) {
            // 判定失败 ≠ 放行：宁可让所有者多问一次，也不能让判定漏洞变成权限漏洞
            log.error("录播命令的权限判定异常，已按拒绝处理", e);
            safeSend(bot, event, denyMessage(level));
            return;
        }
        if (!allowed) {
            send(bot, event, denyMessage(level));
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.error("处理录播命令失败", e);
            safeSend(bot, event, "处理失败：" + e.getMessage());
        }
    }

    private boolean check(AnyMessageEvent event, Level level) {
        return switch (level) {
            case OWNER -> botAdminChecker.isBotOwner(event);
            case BOT_ADMIN -> botAdminChecker.isBotAdmin(event);
            default -> event.getGroupId() != null
                    ? botAdminChecker.isAdmin(event)
                    : botAdminChecker.isBotOwner(event);
        };
    }

    private static String denyMessage(Level level) {
        return switch (level) {
            case OWNER -> """
                    你没有权限做这个操作。
                    它动的是整个机器人共用的东西（容量整理会删/压缩文件，重置管理码会让所有人\
                    刚拿到的网页管理权一起失效），只允许机器人所有者执行。
                    （群主 / 群管理员不算，「按群授权」的管理员也不算，私聊也不例外）""";
            case BOT_ADMIN -> """
                    你没有权限做这个操作。
                    它要么暴露整台机器的状态，要么会发一个长期有效、可以转发的网页凭证，\
                    所以只对 admin 白名单开放。
                    （群主 / 群管理员不算；要开就找机器人所有者在 admin 表里加你的 QQ）""";
            default -> """
                    你没有权限取用录播。
                    列表 / 下载 / 保留 / 删除 只对本群的管理员开放 —— 下载会把这个群和机器人\
                    之间的带宽占满，不该人人可用。
                    群里需要群主、群管理员，或在 admin 表里给这个群授权；
                    私聊里没有"本群"这个上下文，只有机器人所有者能用。""";
        };
    }

    // ================================================================== 命令：网页预览

    /** 回本群的网页链接：`录播网页` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_WEB, at = AtEnum.BOTH)
    public void web(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> {
            Long groupId = event.getGroupId();
            if (groupId == null) {
                send(bot, event, "「录播网页」要在<b>群里</b>发 —— 链接是按群绑定的，"
                        + "一个群一条令牌。\n（私聊里没有群，发出来不知道该给哪个群）");
                return;
            }
            if (!recordWebAuth.enabled()) {
                send(bot, event, "录播网页还没开。让机器人所有者把配置 biliRecordWebEnabled 设成 true"
                        + "（改完即时生效，不用重启）。");
                return;
            }
            String token = recordWebAuth.groupToken(groupId);
            String link = token == null ? null : recordWebAuth.link(token);
            if (link == null) {
                // 拼不出链接 = 没有「公网可达」的基址（内网地址按口径不下发，见 RecordWebAuth#baseUrl）。
                // 这里给的是"该配什么"的指引，而不是一条打不开的链接 —— 甚至不该先把链接发出去再补一句。
                String advice = recordWebAuth.baseUrlAdvice();
                send(bot, event, advice != null ? advice
                        : "网页服务在跑，但推断不出「群成员点得开的地址」⇒ 链接没法发。\n"
                        + "让机器人所有者把配置 biliRecordWebBaseUrl 设成公网可达的域名或 IP。");
                return;
            }
            send(bot, event, "【本群录播网页】\n" + link
                    + "\n\n发给群成员即可，他们能<b>浏览 / 在线播放 / 下载</b>本群订阅的录播。\n"
                    + "⚠️ 链接本身就是凭证，等于这个群的共享密码 —— 别转到群外。\n"
                    + "万一泄了：发「录播网页重置」，旧链接立刻失效。");
        });
    }

    /** 重置本群令牌：`录播网页重置` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_WEB_RESET, at = AtEnum.BOTH)
    public void webReset(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> {
            Long groupId = event.getGroupId();
            if (groupId == null) {
                send(bot, event, "「录播网页重置」要在群里发（链接是按群绑定的）。");
                return;
            }
            recordWebAuth.resetGroupToken(groupId);
            String link = recordWebAuth.link(recordWebAuth.groupToken(groupId));
            send(bot, event, "✅ 本群录播网页令牌已重置，之前发出去的链接<b>全部失效</b>。\n"
                    + (link == null ? "（新的链接暂时拼不出来，见「录播网页」的提示）" : "新链接：\n" + link));
        });
    }

    /**
     * 私聊发管理码：`录播管理码`。
     *
     * <p>⚠️ <b>必须私聊投递</b>：管理码能删录播，群里回一句等于贴墙上。
     * 所以在群里发这个命令时，机器人只回一句"已私聊"，真正的值走私聊 ——
     * 私聊发不出去（没加好友等）也<b>不回群里</b>，宁可让人去私聊里再发一次。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_WEB_CODE, at = AtEnum.BOTH)
    public void webCode(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> {
            if (!recordWebAuth.enabled()) {
                send(bot, event, "录播网页还没开，先让机器人所有者把配置 biliRecordWebEnabled 设成 true。");
                return;
            }
            String code = recordWebAuth.adminToken();
            if (event.getGroupId() == null) {
                // 本来就在私聊里，直接回
                send(bot, event, manageCodeText(code));
                return;
            }
            send(bot, event, "带管理权限的码不适合发在群里 —— 已私聊发给你。");
            try {
                bot.sendPrivateMsg(event.getUserId(), manageCodeText(code), false);
            } catch (Exception e) {
                log.warn("私聊发送录播管理码失败：{}", e.toString());
                safeSend(bot, event, "私聊发不出去（可能没加机器人好友，或开启了拒收）。\n"
                        + "请先私聊机器人发一次「录播管理码」，它会在私聊里回给你。");
            }
        });
    }

    /** 重置管理码：`录播管理码重置`（仅所有者 —— 它会让所有网页管理权一起失效） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_WEB_CODE_RESET, at = AtEnum.BOTH)
    public void webCodeReset(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            recordWebAuth.resetAdminToken();
            send(bot, event, """
                    ✅ 管理码已重置，之前拿到它的网页管理权<b>全部失效</b>。
                    （群成员看回放的群令牌不受影响，各群的链接照旧可用）
                    要新码：私聊机器人发「录播管理码」（但别再把码发回群里）。""");
        });
    }

    private static String manageCodeText(String code) {
        return "【录播网页管理码】\n" + code
                + "\n\n用法：打开录播网页 → 点右上角「管理」→ 粘贴这串码。\n"
                + "有了它就能在网页上改保留标记、删录播（能看全部群，不只是某一个群）。\n"
                + "⚠️ 别把它发到任何群里 —— 谁拿到谁就有删除权。泄了就发「录播管理码重置」。";
    }

    // ================================================================== 工具

    /** 带异常的命令体（{@code addSub} 声明了 IOException，Runnable 装不下） */
    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /**
     * 本次命令能看到的房间集合。
     *
     * @return {@code null} = <b>不限</b>（私聊里执行，此时用户已经被判过是所有者，看全部）；
     *         群里的返回"本群订阅过的房间号"，可能是空集（= 本群什么都还没录）
     */
    private Collection<Long> scopeFor(AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        return groupId == null ? null : liveRecordService.roomsOfGroup(groupId);
    }

    /** 一场录播在不在可见范围内（{@code scope == null} = 不限）。 */
    private static boolean inNotScope(Collection<Long> scope, LiveRecordFile row) {
        return scope != null && (row.getRoomId() == null || !scope.contains(row.getRoomId()));
    }

    /**
     * 单场操作的公共前置检查：编号合法、记录存在、且<b>在本群可见范围内</b>。
     *
     * <p>抽成一个方法而不是在每个 handler 里各写一遍：这条检查是<b>安全边界</b>
     * （编号自增、可枚举），复制三份之后必然有一份先被改坏。
     *
     * <p>返回记录本身而不是"过没过"，是为了让调用方不必再查一次 ——
     * 两次查询之间记录可能被删掉，那种竞态下第二次会拿到 {@code null}。
     *
     * @return 通过检查的那条记录；{@code null} = 已经回过消息了，handler 直接返回
     */
    private LiveRecordFile touchable(Bot bot, AnyMessageEvent event, Long fid) {
        LiveRecordFile row = fid == null ? null : liveRecordService.findFile(fid);
        if (row == null) {
            send(bot, event, "找不到编号 " + fid + " 的录播");
            return null;
        }
        if (inNotScope(scopeFor(event), row)) {
            send(bot, event, "编号 " + fid + " 不在本群可见范围内。用「录播列表」看本群的编号。");
            return null;
        }
        return row;
    }

    /** 作用范围的措辞（群里说"本群"，私聊里说"全局"）。 */
    private static String scopeWord(Long groupId) {
        return groupId == null ? "全局" : "本群";
    }

    /** 丢进交付队列。队列满了不该拖住消息线程 —— 直接拒绝并说明。 */
    private void submit(Runnable task) {
        try {
            deliverPool.execute(task);
        } catch (Exception e) {
            log.warn("录播交付队列拒绝任务：{}", e.toString());
        }
    }

    /**
     * 回消息，<b>并且保证"回消息"本身不会再抛出去</b>。
     *
     * <p>用在异步任务 / catch 路径上：那时进程已经处于出过岔子的状态，
     * 要是连发消息也失败（连接断了 / 被禁言），异常就会从那些没有上层兜底的线程里逃逸。
     */
    private void safeSend(Bot bot, AnyMessageEvent event, String message) {
        try {
            bot.sendMsg(event, message, false);
        } catch (Exception e) {
            log.debug("录播命令回消息失败，已忽略：{}", e.toString());
        }
    }

    private static void send(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }

    /**
     * 取命令参数。
     *
     * <p>先把 CQ 码换成空格再 trim：群里通常是「@机器人 录播订阅 1024」，
     * 不剥掉前置的 {@code [CQ:at,qq=...]} 就永远匹配不到命令头。
     */
    private static String body(String message) {
        return message == null ? "" : message.replaceAll("\\[CQ:[^]]*]", " ").trim();
    }

    /** {@code argsAfter("录播订阅 1024", "录播订阅")} → {@code "1024"} */
    private static String argsAfter(String text, String command) {
        return text.startsWith(command) ? text.substring(command.length()).trim() : "";
    }

    /** 取第一个 token 里的数字。返回 {@code null} 表示不是合法正整数。 */
    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.trim().split("\\s+")[0].replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 15) {
            return null;
        }
        try {
            long value = Long.parseLong(digits);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 开关的中文/英文/数字写法。返回 {@code null} 表示认不出来（让命令回格式提示，别猜）。 */
    private static Boolean parseOn(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.matches("开|开启|打开|启用|恢复|是|on|1|true|yes")) {
            return true;
        }
        if (s.matches("关|关闭|暂停|停用|否|off|0|false|no")) {
            return false;
        }
        return null;
    }

    /** 订阅的展示名：昵称优先，取不到就只报房间号（昵称是"加订阅时的快照"，允许过期） */
    private static String describe(LiveRecordSub sub) {
        if (sub.getUname() == null || sub.getUname().isBlank()) {
            return "未取到昵称";
        }
        return escape(sub.getUname());
    }

    /** 一行的状态标记 */
    private static String statusText(LiveRecordFile row) {
        String status = row.getStatus() == null ? "" : row.getStatus().toUpperCase();
        boolean keep = Integer.valueOf(1).equals(row.getKeep());
        boolean compressed = Integer.valueOf(1).equals(row.getCompressed());
        StringBuilder sb = new StringBuilder();
        if (keep) {
            sb.append("🔒保留");
        }
        switch (status) {
            case "RECORDING" -> sb.append(sb.isEmpty() ? "" : " ").append("⏺录制中");
            case "INTERRUPTED" -> sb.append(sb.isEmpty() ? "" : " ").append("⚠中断");
            case "FAILED" -> sb.append(sb.isEmpty() ? "" : " ").append("✖失败");
            default -> sb.append(sb.isEmpty() ? "" : " ")
                    .append(compressed ? "已压缩" : "可下载");
        }
        return sb.toString();
    }

    private static String fmtTime(Long millis) {
        if (millis == null || millis <= 0) {
            return "时间未知";
        }
        return STAMP.format(Instant.ofEpochMilli(millis));
    }

    /** 人可读体积。刻意不复用服务里的私有实现 —— 那是实现细节，不该为了显示去开接口。 */
    private static String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.0fKB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.0fMB", mb);
        }
        return String.format("%.2fGB", mb / 1024.0);
    }

    /** 主播昵称与直播标题都来自服务端 ⇒ 出口统一转义，防止昵称里带 {@code [CQ:} 打穿消息（S7 纪律） */
    private static String escape(String text) {
        return text == null ? "" : BiliBiliContant.escapeCq(text);
    }
}
