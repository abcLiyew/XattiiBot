package com.esdllm.botPlugins;

import com.esdllm.common.BotAdminChecker;
import com.esdllm.common.CmdPrefix;
import com.esdllm.config.LoadDSConfig;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
 * 录播订阅列表                 看本群盯了哪些房间（⚠️ 私聊里<b>只</b>列全局订阅，不是全部）
 * 录播取消订阅 1024            取消订阅（已录好的文件保留）
 * 录播开关 1024 关             暂停/恢复某个订阅的自动录制
 *
 * ── 跨群订阅（仅所有者；"这台机器在替哪些群盯谁"）
 * 录播订阅列表 全部            按群分组的全量总览（含全局订阅、暂停、录制中）
 * 录播取消订阅 <群号> <房间号>  只删指定群那一行（群号写 0 = 全局订阅）
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
 * 录播 开 / 录播 关            总闸：关着的时候一个 B 站请求都不发（已在录的会录完）
 * 录播配置 [项] [值]           看 / 改录播参数（水位、并发、目录、交付方式…）
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

    /**
     * 命令的<b>可选前缀</b>：行首空白 / CQ 码（@机器人 / 表情等）/ <b>手打的 @机器人</b>。
     *
     * <p>直接取 {@link CmdPrefix#PREFIX}，与搜索 / 反刷屏 / cookie 等命令<b>同一口径</b>：
     * {@code 录播订阅 1024}、{@code [CQ:at,qq=213481590] 录播订阅 1024}、
     * {@code @Hana 录播订阅 1024} 都命中，而 {@code @张三 录播订阅 1024} 不命中
     * （详见 {@link CmdPrefix}）。
     */
    private static final String CMD_PREFIX = CmdPrefix.PREFIX;

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
    private static final String CMD_SUB_ADD = "^" + CMD_PREFIX + "录播订阅\\s+" + ID + "\\s*$";
    private static final String CMD_SUB_LIST = "^" + CMD_PREFIX + "录播订阅列表\\s*$";
    private static final String CMD_SUB_DEL = "^" + CMD_PREFIX + "录播取消订阅\\s+" + ID + "\\s*$";
    private static final String CMD_SUB_SWITCH = "^" + CMD_PREFIX + "录播开关\\s+" + ID + "\\s+\\S+\\s*$";

    /**
     * 订阅<b>总览</b>（跨群）：`录播订阅列表 全部`。
     *
     * <p>为什么需要它：{@link #CMD_SUB_LIST} 在私聊里只能看到"全局订阅"
     * （{@code listSubs(null)} 查的是 {@code group_id IS NULL}），
     * 于是"每个群各订了什么"在聊天里根本没有出口 —— 只能进每个群挨个发一次，或者去改库。
     *
     * <p>与 {@link #CMD_SUB_LIST} 靠"末尾是否带参数"互斥：那条要求直接到行尾。
     */
    private static final String CMD_SUB_LIST_ALL =
            "^" + CMD_PREFIX + "录播订阅列表\\s+(?:全部|所有|all)\\s*$";

    /**
     * <b>跨群</b>取消订阅：`录播取消订阅 <群号> <房间号>`（群号写 {@code 0} = 全局订阅那一行）。
     *
     * <p>为什么需要它：{@link #CMD_SUB_DEL} 按<b>当前会话的群</b>定位，私聊里
     * {@code event.getGroupId()} 是 {@code null} ⇒ 它只能删全局订阅，删不掉某个群的。
     *
     * <p>与 {@link #CMD_SUB_DEL} 靠"房间号后面还有没有第二个数字"互斥（那条 {@code \s*$} 接不住）。
     * 语义是<b>只删指定的那一行</b>，不是"把这个房间全删了" ——
     * 同一房间被多个群订阅时，删一行不影响其他群，也不影响正在录的那一路。
     */
    private static final String CMD_SUB_DEL_SCOPED =
            "^" + CMD_PREFIX + "录播取消订阅\\s+" + ID + "\\s+" + ID + "\\s*$";

    // ── 取用（群内管理员）
    private static final String CMD_FILE_LIST = "^" + CMD_PREFIX + "录播列表(?:\\s+" + ID + ")?\\s*$";
    private static final String CMD_FILE_GET = "^" + CMD_PREFIX + "录播下载\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_KEEP = "^" + CMD_PREFIX + "录播保留\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_UNKEEP = "^" + CMD_PREFIX + "录播取消保留\\s+" + ID + "\\s*$";
    private static final String CMD_FILE_DEL = "^" + CMD_PREFIX + "删除录播\\s+" + ID + "\\s*$";
    private static final String CMD_STATUS = "^" + CMD_PREFIX + "录播状态\\s*$";
    private static final String CMD_ADDR = "^" + CMD_PREFIX + "录播地址\\s*$";

    // ── 网页预览（botadmin 及以上）
    private static final String CMD_WEB = "^" + CMD_PREFIX + "录播网页\\s*$";
    private static final String CMD_WEB_RESET = "^" + CMD_PREFIX + "录播网页重置\\s*$";
    private static final String CMD_WEB_CODE = "^" + CMD_PREFIX + "录播管理码\\s*$";
    private static final String CMD_WEB_CODE_RESET = "^" + CMD_PREFIX + "录播管理码重置\\s*$";

    // ── 运维（仅所有者）
    private static final String CMD_TIDY = "^" + CMD_PREFIX + "录播整理\\s*$";

    /**
     * 总闸：`录播 开` / `录播 关`（也接受「开启录播」「关闭录播」）。
     *
     * <p>⚠️ 与 {@link #CMD_SUB_SWITCH}（{@code 录播开关 1024 关}）必须互斥：
     * 那条命令词是「录播<b>开</b>关」+ 空格 + 参数，而这里要求「录播」之后
     * <b>直接</b>是 开/关 且到行尾。`录播开关 1024 关` 里 "开" 之后还剩 "关 1024 关"，
     * {@code \s*$} 接不住 ⇒ 不会误命中。两者都靠 {@code matches()} 整串匹配，缺了行尾锚就会打架。
     */
    private static final String CMD_MASTER =
            "^" + CMD_PREFIX + "(?:录播\\s*(?:开|关)|(?:开启|关闭)录播)\\s*$";

    /**
     * 配置面板：`录播配置`（全部）/ `录播配置 硬水位`（单项详情）。
     *
     * <p>参数<b>可有可无、最多一个</b>：{@code (?:\s+\S+)?} 之后立刻行尾。
     * 于是它和 {@link #CMD_CONF_SET}（至少两个参数）天然互斥 —— 一条消息只会命中一个 handler。
     */
    private static final String CMD_CONF_PANEL =
            "^" + CMD_PREFIX + "录播(?:配置|设置)(?:\\s+\\S+)?\\s*$";

    /** 改一项：`录播配置 硬水位 20480`（值里允许再带空格，如基址后面跟注释的情形，一并吃掉） */
    private static final String CMD_CONF_SET =
            "^" + CMD_PREFIX + "录播(?:配置|设置)\\s+\\S+\\s+\\S.*$";

    /** 一页显示多少场 */
    private static final int PAGE_SIZE = 8;

    /**
     * 「订阅总览」的字符上限（超了就截断并说明）。
     *
     * <p>订阅数没有分页 —— 它是"运维一眼看全"的面板，翻页反而难对账。
     * 但 QQ 单条消息装不下几百行，拼一条发不出去的消息比截断更糟。
     * 3000 字大约 60 行订阅，够覆盖实际规模（当前个位数群）。
     */
    private static final int MAX_LIST_CHARS = 3000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Resource
    private LiveRecordService liveRecordService;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private RecordWebAuth recordWebAuth;
    /** 总闸与参数面板要读写 config 表（取值都是现读 configMap ⇒ 改完即时生效） */
    @Resource
    private LoadDSConfig loadDSConfig;

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

    /**
     * 订阅<b>总览</b>（跨群）：`录播订阅列表 全部`（仅所有者）。
     *
     * <p>它回答的是运维视角的那一问：<b>这台机器此刻在为哪些群、盯哪些房间</b>。
     * 按群分组展示（全局订阅排最前），每行带"开/暂停"与"是否在录"，
     * 末尾给出跨群取消的写法 —— 否则看到问题也改不动，等于只报不看。
     *
     * <p><b>为什么是 OWNER 档</b>：这份列表把"所有群在盯谁"一次摊开，
     * 信息面本身就是全局的；而且它天然是"要去动别人的订阅"的前置步骤，
     * 与 {@link #subDelScoped} 同一档才自洽。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_LIST_ALL, at = AtEnum.BOTH)
    public void subListAll(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            List<LiveRecordSub> subs = new ArrayList<>(liveRecordService.listSubs());
            if (subs.isEmpty()) {
                send(bot, event, "还没有任何录播订阅（所有群都没有）。");
                return;
            }
            send(bot, event, renderSubOverview(subs));
        });
    }

    /**
     * 订阅总览的渲染。
     *
     * <p>抽成独立方法（不直接 in handler）是为了它能被单独验：分组边界、排序键、
     * 统计口径、超长截断这几件事全是纯字符串逻辑，用假数据跑一遍就能看见，
     * 而"塞在 handler 里"就只能靠人肉读代码 + 群里真发一条。
     */
    private String renderSubOverview(List<LiveRecordSub> subs) {
        // 排序：全局订阅（groupId=null）排最前，其余按群号升序，群内按房间号升序。
        // 用"先比 null 后比群号"两段比较而不是把 null 当 0 —— 群号理论上可能是 0 位数字，
        // 真混进来会跟全局订阅挤在一起。
        subs.sort(Comparator
                .comparingInt((LiveRecordSub s) -> s.getGroupId() == null ? 0 : 1)
                .thenComparingLong(s -> s.getGroupId() == null ? 0L : s.getGroupId())
                .thenComparing(LiveRecordSub::getRoomId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        int groups = 0;
        int paused = 0;
        // ⚠️ 按<b>房间</b>去重，不是按行数：同一房间被 3 个群订阅就是 3 行，
        // 但录制只有 1 路（tick 按 room_id 去重）。按行数统计会报出"录制中 3 路"这种假话，
        // 而这恰好是扩容/排障时最容易被信以为真的数字。
        Set<Long> recordingRooms = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        Long currentGroup = null;
        boolean firstLine = true;
        for (LiveRecordSub sub : subs) {
            if (firstLine || !Objects.equals(currentGroup, sub.getGroupId())) {
                if (!firstLine) {
                    sb.append("\n");
                }
                sb.append(sub.getGroupId() == null
                        ? "全局订阅（私聊里加的）" : "群 " + sub.getGroupId()).append("：\n");
                currentGroup = sub.getGroupId();
                firstLine = false;
                groups++;
            }
            boolean on = Integer.valueOf(1).equals(sub.getAutoRecord());
            boolean rec = liveRecordService.isRecording(sub.getRoomId());
            if (!on) {
                paused++;
            }
            if (rec) {
                recordingRooms.add(sub.getRoomId());
            }
            sb.append(on ? "  ● " : "  ○ ").append(sub.getRoomId())
                    .append("　").append(describe(sub))
                    .append(on ? "" : "　[已暂停]")
                    .append(rec ? "　⏺录制中" : "")
                    .append("\n");
            // QQ 单条消息装不下几百行；宁可截断并说明，也不要拼一条发不出去的消息
            if (sb.length() > MAX_LIST_CHARS) {
                sb.append("…（太长已截断，共 ").append(subs.size()).append(" 条）\n");
                break;
            }
        }

        String head = "录播订阅总览（共 " + subs.size() + " 条 / " + groups + " 个归属 / 暂停 "
                + paused + " / 录制中 " + recordingRooms.size() + " 路）\n\n";
        String tail = """

                ● 开录　○ 暂停
                只停某一个群：录播取消订阅 <群号> <房间号>
                停全局订阅：　录播取消订阅 0 <房间号>""";
        return head + sb + tail;
    }

    /**
     * <b>跨群</b>取消订阅：`录播取消订阅 <群号> <房间号>`（群号 {@code 0} = 全局订阅，仅所有者）。
     *
     * <p>与「录播取消订阅 房间号」的区别只有定位方式：那条定位的是<b>当前群</b>，
     * 这条明确指定归属群 —— 私聊里才有意义（私聊没有"当前群"这个上下文）。
     *
     * <p>⚠️ 会如实说明两件事，因为它们都不是"无感"的：
     * <ul>
     *   <li>房间还有别的群订阅 ⇒ 录制与文件都不受影响，只是这个群以后看不到了；</li>
     *   <li>这是最后一条订阅 ⇒ {@code removeSub} 会<b>掐断</b>正在录的那一路（这一场以中断收尾）。
     *       所以回显必须把那句话打出来，不能只说"已取消"。</li>
     * </ul>
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_DEL_SCOPED, at = AtEnum.BOTH)
    public void subDelScoped(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            String raw = argsAfter(body(event.getMessage()), "录播取消订阅");
            String[] parts = raw.trim().split("\\s+");
            // 群号要能是 0（= 全局订阅），所以这里不能用 parseId —— 它把 0 判成非法
            Long rawGroup = parts.length > 0 ? parseGroupToken(parts[0]) : null;
            Long roomId = parts.length > 1 ? parseId(parts[1]) : null;
            if (rawGroup == null || roomId == null) {
                send(bot, event, """
                        格式：录播取消订阅 <群号> <房间号>
                        全局订阅的群号写 0（例：录播取消订阅 0 1024）
                        先发「录播订阅列表 全部」核对群号。""");
                return;
            }
            // 0 = 全局订阅那一行（group_id IS NULL）。用 0 而不是"全部/全局"两个字：
            // 这条命令的主体是 <群号> <房间号> 两个数字，混进一个中文词会让人以为还有别的写法
            Long groupId = rawGroup == 0L ? null : rawGroup;

            // 落库之前先记下"现在是不是在录"，落库之后答案会变（最后一条订阅会顺手停录）
            boolean wasRecording = liveRecordService.isRecording(roomId);
            boolean ok = liveRecordService.removeSub(roomId, groupId);
            if (!ok) {
                send(bot, event, (groupId == null ? "全局订阅" : "群 " + groupId)
                        + " 没有订阅房间 " + roomId + "。\n发「录播订阅列表 全部」核对群号与房间号。");
                return;
            }
            boolean stillWanted = liveRecordService.isSubscribed(roomId);
            StringBuilder sb = new StringBuilder("✅ 已取消 ")
                    .append(groupId == null ? "全局订阅" : "群 " + groupId)
                    .append(" 对房间 ").append(roomId).append(" 的订阅");
            if (stillWanted) {
                sb.append("\n这个房间还有别的订阅 ⇒ 录制照旧，已录/在录的文件仍对订阅它的群可见。");
            } else if (wasRecording) {
                sb.append("\n⚠️ 已没有任何群订阅它，正在录的这一路已停下 —— 这一场会按「中断」收尾。");
            } else {
                sb.append("\n已没有任何群订阅它，后续不再自动录；已录好的文件都还在（「录播列表」可查）。");
            }
            send(bot, event, sb.toString());
            log.info("跨群取消录播订阅：roomId={}, groupId={}（操作者 {}）",
                    roomId, groupId, event.getUserId());
        });
    }

    /** 取消订阅：`录播取消订阅 1024` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SUB_DEL, at = AtEnum.BOTH)
    public void subDel(Bot bot, AnyMessageEvent event) {        guard(bot, event, Level.GROUP, () -> {
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

    /**
     * 录播总闸：`录播 开` / `录播 关`（也接受「开启录播」「关闭录播」）。
     *
     * <p><b>为什么是 OWNER 档而不是"管理员"</b>：总闸决定<b>整台机器</b>要不要持续写盘、
     * 要不要按订阅数持续请求 B 站 —— 影响所有群、所有订阅、整块磁盘。
     * 这与「开关 / 设置cookie / 设置代理」是同一性质，项目里这几条一律只认所有者
     * （理由见 {@link BotAdminChecker#isBotOwner}：{@code admin} 表里存在「按群授权」的记录，
     * 拿它当全局闸会把群内身份放大成全局权限）。
     *
     * <p>群管理员要做的事在下一条命令上：他有权「录播订阅 / 录播取消订阅」（{@link Level#GROUP}），
     * 那才是"本群要不要录"的正确粒度 —— 不需要他碰总闸。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_MASTER, at = AtEnum.BOTH)
    public void master(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            String text = body(event.getMessage());
            // 「开启录播」「录播 开」都算开；「关闭录播」「录播关」都算关
            boolean on = text.endsWith("开") || text.startsWith("开启");
            setMaster(bot, event, on);
        });
    }

    /**
     * 配置面板：`录播配置`（全部项）/ `录播配置 硬水位`（单项详情）。
     *
     * <p>读配置给 {@link Level#BOT_ADMIN}、改配置给 {@link Level#OWNER} —— 两件事分开是因为
     * "看"只暴露参数值，"改"会把盘写满或把带宽打满。中间那档人不该有写的权力，
     * 但让他看得懂当前水位是有运维价值的（不然他报障时说不出数）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_CONF_PANEL, at = AtEnum.BOTH)
    public void confPanel(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.BOT_ADMIN, () -> {
            List<String> tokens = confTokens(event.getMessage());
            if (tokens.isEmpty()) {
                send(bot, event, confPanelText());
                return;
            }
            ConfItem item = findConf(tokens.get(0));
            if (item == null) {
                send(bot, event, "没有「" + escape(tokens.get(0)) + "」这一项\n\n" + confPanelText());
                return;
            }
            send(bot, event, confDescribe(item));
        });
    }

    /** 改一项：`录播配置 硬水位 20480`（仅所有者）。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_CONF_SET, at = AtEnum.BOTH)
    public void confSet(Bot bot, AnyMessageEvent event) {
        guard(bot, event, Level.OWNER, () -> {
            List<String> tokens = confTokens(event.getMessage());
            if (tokens.size() < 2) {
                send(bot, event, "格式：录播配置 <项> <值>（例如 录播配置 硬水位 20480）\n\n" + confPanelText());
                return;
            }
            ConfItem item = findConf(tokens.get(0));
            if (item == null) {
                send(bot, event, "没有「" + escape(tokens.get(0)) + "」这一项\n\n" + confPanelText());
                return;
            }
            // 值里理论上不会有空格，但 URL 后面顺手带个注释的情形很常见 —— 拼起来交给校验判对错
            String rawValue = String.join("", tokens.subList(1, tokens.size())).trim();
            applyConf(bot, event, item, rawValue);
        });
    }

    /** 写总闸并回显；`录播 开` 与 `录播配置 总闸 开` 共用这一份逻辑。 */
    private void setMaster(Bot bot, AnyMessageEvent event, boolean on) {
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_BILI_RECORD_ENABLED, String.valueOf(on));
        } catch (Exception e) {
            log.error("保存录播总闸失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        if (on) {
            int subs = liveRecordService.listSubs().size();
            int interval = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_CHECK_SECONDS,
                    LoadDSConfig.DEFAULT_RECORD_CHECK_SECONDS);
            StringBuilder sb = new StringBuilder("✅ 录播总闸已开（biliRecordEnabled=true，即时生效）\n");
            sb.append("当前参数：\n").append(liveRecordService.effectiveConfig()).append("\n");
            if (subs == 0) {
                // 只开总闸没用 —— 一定要说清"还差订阅这一步"，否则用户会以为开了就该录
                sb.append("⚠️ 但还没有任何订阅 ⇒ 现在一个都不会录。\n");
                if (event.getGroupId() != null) {
                    sb.append("在本群发「录播订阅 房间号」加一个。");
                } else {
                    sb.append("在群里发「录播订阅 房间号」（记到那个群），或私聊发（记成全局订阅）。");
                }
            } else {
                sb.append("在盯 ").append(subs).append(" 条订阅，")
                        .append(interval).append(" 秒内开播的会自动开录。");
            }
            send(bot, event, sb.toString());
            return;
        }
        int active = liveRecordService.listActive().size();
        send(bot, event, "⏹ 录播总闸已关（biliRecordEnabled=false，即时生效）\n"
                + "从现在起：开播检测直接返回，一个 B 站请求都不发。\n"
                + (active > 0
                ? "正在录的 " + active + " 路会正常录完并登记，不会被掐断。"
                : "当前没有在录的场次。")
                + "\n已录好的文件照常能取（录播列表 / 录播下载 / 录播网页 都不受影响）。");
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

    /**
     * 管理码的回复文案。
     *
     * <p>⚠️ 这段消息本身就是<b>凭据</b>（码能删任何群的录播、链接打开即全群视图），
     * 所以调用方一律走私聊 —— 群里发只会回一句"已私聊发给你"。
     *
     * <p>为什么要把<b>管理台链接</b>一起给：以前只给一串码，用户得先随便打开一个<b>群</b>链接、
     * 点右上角「管理」、粘码，而那样看到的仍然<b>只有本群</b>的场次（列表按 URL 里的群令牌过滤）。
     * 想让"管理码"真正等于"管理所有群"，就必须用一个<b>以码为令牌</b>的 URL 打开页面
     * —— 那种页面走的是 admin 视图，服务端按 {@code scope = null} 返回全部群。
     */
    private String manageCodeText(String code) {
        String link = recordWebAuth.link(code);
        StringBuilder sb = new StringBuilder("【录播网页管理码】\n").append(code).append("\n\n");
        if (link != null) {
            sb.append("▶ 管理台（打开就是【全部群】的录播，能直接删 / 保留 / 实时预览）：\n")
                    .append(link).append("\n\n");
        } else {
            sb.append("⚠️ 拼不出管理台链接（推断不出公网可达的基址）：让所有者配 ")
                    .append(LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL).append("。\n")
                    .append("眼下只能：打开任一录播网页 → 右上角「管理」→ 粘贴上面这串码。\n\n");
        }
        sb.append("也可以：打开任一录播网页 → 点右上角「管理」→ 粘贴这串码（粘一次即可，存在浏览器本机）。\n")
                .append("有了它就能在网页上改保留、删录播 —— 能看全部群，不只是某一个群。\n")
                .append("⚠️ 这条链接和这串码等于<b>删除权</b>：别发到任何群里，也别存进共享文档。")
                .append("泄了就发「录播管理码重置」，旧的一次全失效。");
        return sb.toString();
    }

    // ================================================================== 命令：配置项表

    /**
     * 配置项的值类型。
     *
     * <p>校验按类型分派（见 {@link #normalizeConf}）：布尔只认明确的词、整数必须落在区间内、
     * 枚举必须命中白名单、文本要过正则 —— <b>校验不过就不落库</b>。
     * 为什么这么严：{@code LoadDSConfig#isEnabled} 是 fail-closed 的，
     * 一个拼错的 {@code ture} 会静默按"关"处理，用户会以为"明明开了却不生效"，
     * 那比当场报一句格式错难查得多。
     */
    private enum Kind { BOOL, INT, ENUM, TEXT }

    /**
     * 文本项的"清空"记号。
     *
     * <p>落空串 ⇒ {@code stringOf} 把空白当"没配" ⇒ 回到该项默认行为。
     * 但没法让用户打一个空字符串，得给几个词（「默认」「自动」是最顺口的两个）。
     */
    private static final Set<String> CLEAR_WORDS =
            Set.of("默认", "自动", "清空", "重置", "-", "default", "auto", "none");

    /** 基址的合法形状（与 {@code BiliConfigPlugins#HTTP_URL} 同一口径：漏 scheme、只有端口都拒掉） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)^https?://(\\[[^]]+]|[^\\s/:]+)(:\\d{1,5})?(/\\S*)?$");

    /** 目录：相对路径，禁空白与 Windows 非法字符 */
    private static final Pattern DIR_PATTERN = Pattern.compile("^[^\\s*?\"<>|]{1,120}$");

    /**
     * 一项可配置参数。
     *
     * <p>{@code scale} 是"用户输入的单位"与"库里存的原值"之间的换算：
     * 只有「单场上限」用它（用户按<b>分钟</b>输入，配置键存的是<b>秒</b>）——
     * 让人去算 6 小时 = 21600 秒属于纯添堵，而这个换算只在一个地方做，不会分叉。
     */
    private record ConfItem(String label, Kind kind, String key, String unit, int scale,
                            int min, int max, List<String> values, Pattern pattern,
                            String fallback, String desc, List<String> aliases) {

        static ConfItem bool(String label, String key, String fallback, String desc, String... aliases) {
            return new ConfItem(label, Kind.BOOL, key, "", 1, 0, 0, List.of(), null, fallback, desc, List.of(aliases));
        }

        static ConfItem num(String label, String key, String unit, int min, int max,
                            String fallback, String desc, String... aliases) {
            return new ConfItem(label, Kind.INT, key, unit, 1, min, max, List.of(), null, fallback, desc, List.of(aliases));
        }

        /** 用户按 {@code unit} 输入，库里存 {@code ×scale} 的原值（分钟 → 秒） */
        static ConfItem scaled(String label, String key, String unit, int scale, int min, int max,
                               String fallback, String desc, String... aliases) {
            return new ConfItem(label, Kind.INT, key, unit, scale, min, max, List.of(), null, fallback, desc, List.of(aliases));
        }

        static ConfItem options(String label, String key, List<String> values, String fallback,
                                String desc, String... aliases) {
            return new ConfItem(label, Kind.ENUM, key, "", 1, 0, 0, values, null, fallback, desc, List.of(aliases));
        }

        static ConfItem text(String label, String key, Pattern pattern, String fallback,
                             String desc, String... aliases) {
            return new ConfItem(label, Kind.TEXT, key, "", 1, 0, 0, List.of(), pattern, fallback, desc, List.of(aliases));
        }

        boolean matches(String name) {
            if (name == null || name.isBlank()) {
                return false;
            }
            String v = name.trim().toLowerCase(Locale.ROOT);
            if (label.toLowerCase(Locale.ROOT).equals(v)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.toLowerCase(Locale.ROOT).equals(v)) {
                    return true;
                }
            }
            return false;
        }

        /** 这个值该长什么样（报错与详情里共用一句，免得两处口径不一致） */
        String hint() {
            return switch (kind) {
                case BOOL -> "开 / 关（也认 on/off、true/false、1/0）";
                case INT -> (min + "~" + max) + (unit.isEmpty() ? "" : "（" + unit + "）");
                case ENUM -> String.join(" / ", values);
                case TEXT -> pattern == DIR_PATTERN
                        ? "相对路径（不含空白，如 record），或发「默认」清空"
                        : "http/https 地址（如 https://rec.example.com），或发「自动」清空";
            };
        }
    }

    /** 总闸单列一份引用：`录播配置 总闸 开` 要走总闸专用的回显（订阅数 / 在录路数） */
    private static final ConfItem CONF_MASTER = ConfItem.bool("总闸", LoadDSConfig.KEY_BILI_RECORD_ENABLED,
            "关",
            "是否自动录制。关着的时候开播检测直接返回、一个 B 站请求都不发（已在录的会录完）",
            "开关", "总开关", "enabled", "master");

    /** 目录单列一份引用：改它有一条"不会搬迁已有文件"的专门提醒 */
    private static final ConfItem CONF_DIR = ConfItem.text("目录", LoadDSConfig.KEY_BILI_RECORD_DIR,
            DIR_PATTERN, "record",
            "录播文件放哪（相对进程工作目录）。⚠️ 改它不会搬迁已有文件",
            "路径", "dir", "path", "folder");

    /**
     * 全部可配置项。
     *
     * <p>顺序就是面板上的顺序：<b>先"会不会录"、再"录多少"、最后"怎么给出去"</b> ——
     * 按排障时的思考顺序排，而不是按配置键的字母序。
     *
     * <p>⚠️ 「网页」与「网页基址」同时也在 {@code BiliConfigPlugins} 的「开关」命令里
     * （那边叫「录播网页」）—— 两处都能改是<b>有意</b>的：录播面板要能一站看全，
     * 而"网页"这一项的语义更像功能开关。两边都走 {@code updateConfig}，改哪边都一样生效。
     *
     * <p>⚠️ 刻意<b>不含</b> {@code biliRecordServePort} / {@code biliRecordServeBind}：
     * 那两项只在启动时读一次（{@code RecordFileServer} 绑端口），
     * 放进"即时生效"的面板里会骗人 —— 面板末尾用一句话指路。
     */
    private static final List<ConfItem> CONF_ITEMS = List.of(
            CONF_MASTER,
            ConfItem.num("检测间隔", LoadDSConfig.KEY_BILI_RECORD_CHECK_SECONDS, "秒", 5, 3600,
                    "30",
                    "开播检测间隔 = 开播到开始录制的最大延迟。调小不会录得更全，只会让 B 站请求按比例变多",
                    "间隔", "检测", "check", "interval"),
            ConfItem.scaled("单场上限", LoadDSConfig.KEY_BILI_RECORD_MAX_SECONDS, "分钟", 60, 10, 1440,
                    "360",
                    "一场最多录多久（兜底，常规停止是主播下播）。到点按正常收工处理，不算失败",
                    "时长", "上限", "maxseconds", "duration"),
            ConfItem.num("并发", LoadDSConfig.KEY_BILI_RECORD_MAX_CONCURRENT, "路", 1, 8,
                    "2",
                    "同时录几路。每路 = 一个 ffmpeg + 一份带宽；录制是 -c copy 不转码，瓶颈在带宽与磁盘",
                    "并发数", "concurrent", "maxconcurrent"),
            ConfItem.num("磁盘下限", LoadDSConfig.KEY_BILI_RECORD_DISK_MIN_FREE_MB, "MB", 0, 1048576,
                    "2048",
                    "剩余空间低于它就拒绝开始新的录制（已在录的不动）。录前最后一道闸",
                    "磁盘", "disk", "minfree"),
            ConfItem.num("压缩水位", LoadDSConfig.KEY_BILI_RECORD_COMPRESS_TOTAL_MB, "MB", 0, 1048576,
                    "15360",
                    "总占用超过它就开始把最旧的转 H.265。0 = 关闭压缩",
                    "压缩", "compress", "compresswater"),
            ConfItem.num("硬水位", LoadDSConfig.KEY_BILI_RECORD_HARD_TOTAL_MB, "MB", 0, 1048576,
                    "25600",
                    "总占用超过它就开始删最早的（保留标记的除外）。0 = 不自动删。"
                            + "⚠️ 与压缩水位的差额就是留给压缩的缓冲，贴太近会\"还没压完就到删除线\"",
                    "硬上限", "删除水位", "hard", "hardwater"),
            ConfItem.num("压缩高度", LoadDSConfig.KEY_BILI_RECORD_COMPRESS_HEIGHT, "px", 240, 2160,
                    "720",
                    "压缩时缩到多高（720 = 720p）。只缩不放：源比它矮就保持原样",
                    "高度", "height", "resolution"),
            ConfItem.num("压缩质量", LoadDSConfig.KEY_BILI_RECORD_COMPRESS_CRF, "CRF", 18, 40,
                    "28",
                    "H.265 的 CRF：数字越小越清晰、文件越大。18~40 之间，28 是\"能看且省一半盘\"的折中",
                    "crf", "质量", "quality"),
            ConfItem.options("压缩预设", LoadDSConfig.KEY_BILI_RECORD_COMPRESS_PRESET,
                    List.of("ultrafast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"),
                    "fast",
                    "x265 的 preset。⚠️ 在 4 核机上 fast 与 medium 能差一倍多耗时 —— 压缩是纯 CPU 后台活，越慢越久占核",
                    "预设", "preset", "speed"),
            ConfItem.num("画质", LoadDSConfig.KEY_BILI_RECORD_QUALITY, "qn", 6, 30000,
                    "10000",
                    "录制用哪个清晰度（B 站 qn）：10000=原画、400=蓝光、250=超清、150=高清、80=流畅。"
                            + "降它最省带宽与磁盘",
                    "清晰度", "qn", "qualityqn"),
            CONF_DIR,
            ConfItem.options("交付方式", LoadDSConfig.KEY_BILI_RECORD_DELIVERY_MODE,
                    List.of("auto", "local", "url", "base64"), "auto",
                    "录播怎么送到 QQ：auto=能推断出可达基址就走 url、否则本地路径；"
                            + "local=交本地路径（要求 NapCat 与机器人在同一台机/同一挂载）；"
                            + "url=走内置文件服务；base64=内联（只适合小文件）",
                    "交付", "delivery", "mode"),
            ConfItem.num("链接有效期", LoadDSConfig.KEY_BILI_RECORD_LINK_TTL_MINUTES, "分钟", 1, 1440,
                    "15",
                    "下载链接多久失效（只在走 url 交付时有意义）",
                    "有效期", "ttl", "linkttl"),
            ConfItem.text("交付基址", LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL, URL_PATTERN,
                    "自动推断",
                    "NapCat 从哪个地址来取文件（录播下载的链接就用它开头）。不配则自动推断："
                            + "先 docker 网桥地址、再取流出口网卡、最后才考虑 127.0.0.1。"
                            + "⚠️ 配成公网域名会让 NapCat 绕一圈外网，除非它不在本机 —— 别拿它和「网页基址」搞混",
                    "取文件基址", "文件基址", "publicbase", "filebase", "napcatbase"),
            ConfItem.num("链接次数", LoadDSConfig.KEY_BILI_RECORD_LINK_MAX_USES, "次", 1, 100,
                    "5",
                    "一条下载链接能用几次（防转发扩散）",
                    "次数", "uses", "linkuses"),
            ConfItem.bool("网页", LoadDSConfig.KEY_BILI_RECORD_WEB_ENABLED,
                    "关",
                    "把本群订阅的录播做成网页。⚠️ 开=磁盘上的文件暴露给浏览器，链接本身就是凭证"
                            + "（也可以发「开关 录播网页 开」）",
                    "录播网页", "web", "webpage"),
            ConfItem.text("网页基址", LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL, URL_PATTERN,
                    "自动推断",
                    "「录播网页」发出去的链接用哪个地址开头。不配就按网卡推断；推断不出公网地址时不发链接。"
                            + "机器人跑在容器/内网时这是必配项",
                    "基址", "baseurl", "weburl", "webpagebase"));

    /** 按中文名或别名找一项；找不到返回 {@code null}（调用方回"没有这一项"）。 */
    private static ConfItem findConf(String name) {
        for (ConfItem item : CONF_ITEMS) {
            if (item.matches(name)) {
                return item;
            }
        }
        return null;
    }

    /** 拆出「录播配置」之后的参数：项名 + 值。 */
    private static List<String> confTokens(String message) {
        if (message == null) {
            return List.of();
        }
        String body = CmdPrefix.stripMention(
                message.replaceAll("\\[CQ:[^]]*]", " ").replace('\u3000', ' ')).strip();
        body = body.replaceFirst("(?is)^\\s*录播(?:配置|设置)", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : body.split("\\s+")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** 当前值（人话）。未配置时如实写出"默认是多少"，因为默认值也决定行为。 */
    private String currentText(ConfItem item) {
        String raw = loadDSConfig.getConfigMap().get(item.key());
        if (raw == null || raw.isBlank()) {
            // ⚠️ 这里不带单位：单位由调用方统一追加（面板与详情两处），
            // 在这里再带一次就会出现「未配置（默认 30秒）秒」这种重复
            return "未配置（默认 " + item.fallback() + "）";
        }
        if (item.kind() == Kind.BOOL) {
            if (loadDSConfig.isEnabled(item.key())) {
                return "开";
            }
            // fail-closed 的坑：拼错的值会静默按"关"，这里必须显式打出来
            return parseOn(raw) == null ? "关（值无效：" + raw + "）" : "关";
        }
        if (item.kind() == Kind.INT) {
            try {
                return String.valueOf(Long.parseLong(raw.trim()) / item.scale());
            } catch (NumberFormatException e) {
                return raw + "（不是整数）";
            }
        }
        return raw;
    }

    private String confPanelText() {
        StringBuilder sb = new StringBuilder("录播配置（除端口/监听地址外均即时生效）\n");
        sb.append("当前生效：\n").append(liveRecordService.effectiveConfig()).append("\n\n可配置项：\n");
        for (ConfItem item : CONF_ITEMS) {
            sb.append("· ").append(pad(item.label(), 10))
                    .append(" = ").append(currentText(item))
                    .append(item.unit().isEmpty() ? "" : item.unit())
                    .append("\n");
        }
        sb.append("""
                
                改一项：录播配置 硬水位 20480　｜　录播配置 总闸 开　｜　录播配置 交付方式 url
                看一项：录播配置 硬水位（含配置键与影响面）
                清空文本项：录播配置 目录 默认""");
        sb.append("\n⚠️ 端口与监听地址（biliRecordServePort / biliRecordServeBind）改完要重启，"
                + "因此不在这里 —— 直接改 config 表再重启。");
        sb.append("\n⚠️ 总闸也能用「录播 开 / 录播 关」直接切。");
        return sb.toString();
    }

    private String confDescribe(ConfItem item) {
        String unit = item.unit().isEmpty() ? "" : item.unit();
        return item.label() + " = " + currentText(item) + unit + "\n"
                + "配置键：" + item.key() + "\n"
                + "含义：" + item.desc() + "\n"
                + "可选：" + item.hint() + "\n"
                + "改：录播配置 " + item.label() + " " + exampleOf(item);
    }

    /** 示例值（照着改就行）：整数项用默认值，枚举项用第一个，文本项给个能直接用的样例 */
    private static String exampleOf(ConfItem item) {
        return switch (item.kind()) {
            case BOOL -> "开";
            case INT -> item.fallback();
            case ENUM -> item.values().contains(item.fallback())
                    ? item.fallback()          // 默认值本身就是合法值，拿它当示例最稳妥（如 fast / auto）
                    : item.values().get(0);
            case TEXT -> item.pattern() == DIR_PATTERN ? "record" : "https://rec.example.com";
        };
    }

    /** 校验并落库。校验不过就<b>不写</b>（{@link #normalizeConf} 已经回过原因）。 */
    private void applyConf(Bot bot, AnyMessageEvent event, ConfItem item, String rawValue) {
        String value = normalizeConf(bot, event, item, rawValue);
        if (value == null) {
            return;
        }
        if (item == CONF_MASTER) {
            // 总闸有专门的回显（订阅数 / 在录几路），别在这里重复一份逻辑
            setMaster(bot, event, Boolean.parseBoolean(value));
            log.info("录播总闸已通过「录播配置」改为 {}", value);
            return;
        }
        try {
            loadDSConfig.updateConfig(item.key(), value);
        } catch (Exception e) {
            log.error("保存录播配置失败：{}={}", item.key(), value, e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }

        String shown;
        if (item.kind() == Kind.INT) {
            shown = (Long.parseLong(value) / item.scale()) + item.unit();
        } else if (item.kind() == Kind.BOOL) {
            shown = "true".equals(value) ? "开" : "关";
        } else {
            shown = value.isEmpty() ? "(已清空，回到默认 " + item.fallback() + ")" : value;
        }

        StringBuilder reply = new StringBuilder("✅ ").append(item.label()).append(" → ").append(shown)
                .append("（").append(item.key()).append('=').append(value).append("，即时生效）");
        if (item == CONF_DIR) {
            // 这一条不提醒必然踩：库里存的是相对路径，换了目录之后旧文件还在原地
            reply.append("\n⚠️ 已有文件不会跟着搬 —— 它们还在旧目录里，"
                    + "「录播列表」照样能看到（路径按新目录解析会找不到），要搬请手工处理。");
        }
        reply.append("\n当前：\n").append(liveRecordService.effectiveConfig());
        send(bot, event, reply.toString());
        log.info("录播配置已对话设置：{}={}", item.key(), value);
    }

    /**
     * 校验 + 归一化，返回<b>要落库的字符串</b>；{@code null} = 校验不过（此时已经回过消息了）。
     *
     * <p>⚠️ 用 switch <b>表达式</b>（每支 {@code yield}）而不是语句：语句形式少写一支也编译得过，
     * 新加的 {@link Kind} 会静默走进"没校验直接落库"；表达式强制穷尽。
     */
    private String normalizeConf(Bot bot, AnyMessageEvent event, ConfItem item, String rawValue) {
        return switch (item.kind()) {
            case BOOL -> {
                Boolean on = parseOn(rawValue);
                if (on == null) {
                    send(bot, event, "「" + item.label() + "」只认 " + item.hint() + "。收到：" + rawValue);
                    yield null;
                }
                yield on.toString();
            }
            case INT -> {
                int n;
                try {
                    n = Integer.parseInt(rawValue.trim());
                } catch (NumberFormatException e) {
                    send(bot, event, "「" + item.label() + "」要一个整数：" + item.hint() + "。收到：" + rawValue);
                    yield null;
                }
                if (n < item.min() || n > item.max()) {
                    send(bot, event, "「" + item.label() + "」超出范围：" + item.hint() + "。收到：" + rawValue);
                    yield null;
                }
                // 乘法在这里做一次：用户输入的单位 × scale = 库里存的原值（分钟 → 秒）
                yield String.valueOf((long) n * item.scale());
            }
            case ENUM -> {
                String v = rawValue.trim().toLowerCase(Locale.ROOT);
                if (!item.values().contains(v)) {
                    send(bot, event, "「" + item.label() + "」只能填：" + String.join(" / ", item.values())
                            + "。收到：" + rawValue);
                    yield null;
                }
                yield v;
            }
            case TEXT -> {
                String v = rawValue.trim();
                if (CLEAR_WORDS.contains(v.toLowerCase(Locale.ROOT))) {
                    // 空串 = stringOf 眼里的"没配" = 回到该项默认行为（目录 record / 基址自动推断）
                    yield "";
                }
                if (item.pattern() != null && !item.pattern().matcher(v).matches()) {
                    send(bot, event, "「" + item.label() + "」要 " + item.hint() + "。收到：" + rawValue);
                    yield null;
                }
                yield v;
            }
        };
    }

    /**
     * 中文对齐用的补空格（中文按 2 列宽算）。
     *
     * <p>只是让面板好看一点；QQ 端是比例字体，对齐只能是近似的，所以宽度取保守值。
     */
    private static String pad(String text, int width) {
        int visual = 0;
        for (int i = 0; i < text.length(); i++) {
            visual += text.charAt(i) > 0x2E7F ? 2 : 1;
        }
        StringBuilder sb = new StringBuilder(text);
        while (visual < width) {
            sb.append(' ');
            visual++;
        }
        return sb.toString();
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
     * <p>先把 CQ 码换成空格再 strip：群里通常是「@机器人 录播订阅 1024」，
     * 不剥掉前置的 {@code [CQ:at,qq=...]} 就永远匹配不到命令头。
     *
     * <p>⚠️ 还要剥<b>手打的 {@code @机器人}</b>：那种写法只是普通文本，不是 CQ 段，
     * 光剥 CQ 码剥不掉，会变成 {@code @Hana 录播订阅 1024} 里的 {@code @Hana} 顶在
     * 最前面 ⇒ {@link #argsAfter} 的 {@code startsWith} 直接不成立，参数取成空串。
     * 先按<b>已知称呼</b>精确剥（{@code @Hana录播订阅 1024} 这种中间没空格的也只剥 {@code @Hana}）；
     * 认不出就整段"@ + 一串非空白"剥掉 —— 能走到这里的消息，前置 @ 已经被
     * {@code CmdAddressedAspect} 判过是"在跟机器人说"了。
     */
    private static String body(String message) {
        if (message == null) {
            return "";
        }
        String text = message.replaceAll("\\[CQ:[^]]*]", " ");
        text = CmdPrefix.stripMention(text);
        return text.strip();
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

    /**
     * 解析"群号令牌"：与 {@link #parseId} 只差一条 —— <b>0 是合法值</b>，表示"全局订阅那一行"
     * （{@code group_id IS NULL}）。
     *
     * <p>为什么不干脆放宽 {@code parseId}：房间号、fid 那边 0 是非法值，
     * 放宽了会让「录播下载 0」「录播开关 0 关」从"当场报格式错"变成
     * "去查一条不存在的记录"，错误信息从"你打错了"退化成"没有这个东西"。
     */
    private static Long parseGroupToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.trim().split("\\s+")[0].replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 15) {
            return null;
        }
        try {
            return Long.parseLong(digits);
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
