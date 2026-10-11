package com.esdllm.botPlugins;

import com.esdllm.common.BotAdminChecker;
import com.esdllm.common.CmdPrefix;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.model.respObj.PushInfoResp;
import com.esdllm.service.CredentialGuard;
import com.esdllm.service.PushInfoService;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@Shiro
public class BiliBiliPushPlugins {

    /**
     * 命令的<b>可选前缀</b>：行首空白 / CQ 码（@机器人 等）/ <b>手打的 @机器人</b>。
     *
     * <p>取 {@link CmdPrefix#PREFIX}，与搜索 / cookie / 录播等命令<b>同一口径</b>：
     * {@code 添加订阅 123}、{@code [CQ:at,qq=213481590] 添加订阅 123}、
     * {@code @Hana 添加订阅 123} 都命中，而 {@code @张三 添加订阅 123} 不命中。
     *
     * <p>⚠️ 前缀只管"命令词之前"；命令词之后照旧是 {@code .*}（房间号 + 推送开关都在后面）。
     */
    private static final String CMD_PREFIX = CmdPrefix.PREFIX;

    /** 房间号：正整数，上限 15 位纯防呆（与 {@code LiveRecordPlugins} 同一写法）。 */
    private static final String ID = "\\d{1,15}";

    /** 半角 / 全角 {@code @} 都认（QQ 输入法常把 @ 打成全角 ＠）。 */
    private static final String AT = "[@＠]";

    /**
     * 「开播 @ 谁」四个命令的匹配式。<b>它们必须两两互斥</b>（理由见 {@code LiveRecordPlugins} 的
     * 同类注释）：shiro 用 {@code Matcher#matches()} 整串匹配，一条消息可以同时命中多个 handler，
     * 所以每条都锚 {@code ^…$}，并靠"取消"前缀 + 命令词（全体 / 我）把彼此分开：
     * <ul>
     *   <li>{@code 开播@全体 1024} 与 {@code 取消开播@全体 1024} 靠首字（开 / 取）互斥；</li>
     *   <li>{@code 开播@全体 1024} 与 {@code 开播@我 1024} 靠 {@code @} 后那一个字（全体 / 我）互斥。</li>
     * </ul>
     * 结尾统一留 {@code \s*}：手打常带尾随空格，而 {@code matches()} 是全串匹配，
     * 不留这点余量会因为一个空格静默不响应。
     *
     * <p><b>房间号是可选的</b>（{@code (?:\s+ID)?}）：本群只订阅了一个房间时，用户希望
     * 直接发「开播@全体」就能设上；订阅多个才要求他补房间号（那时会把房间列出来给他挑，
     * 见 {@link #pickRoomWhenMissing}）。
     *
     * <p>⚠️ 这里的 {@code @全体 / @我} 在<b>命令词中间</b>，与 {@link CmdPrefix#PREFIX}
     * 处理的"行首 @机器人"是两回事，两者不会互相干扰。
     */
    private static final String CMD_AT_ALL_ON = "^" + CMD_PREFIX + "开播\\s*" + AT + "\\s*全体(?:\\s+" + ID + ")?\\s*$";
    private static final String CMD_AT_ALL_OFF = "^" + CMD_PREFIX + "取消开播\\s*" + AT + "\\s*全体(?:\\s+" + ID + ")?\\s*$";
    private static final String CMD_AT_ME_ON = "^" + CMD_PREFIX + "开播\\s*" + AT + "\\s*我(?:\\s+" + ID + ")?\\s*$";
    private static final String CMD_AT_ME_OFF = "^" + CMD_PREFIX + "取消开播\\s*" + AT + "\\s*我(?:\\s+" + ID + ")?\\s*$";

    /** 从命令消息里抽房间号用（第一个数字串，见 {@link #extractRoomId(String)}）。 */
    private static final Pattern ROOM_ID = Pattern.compile("\\d{1,15}");

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private PushInfoService pushInfoService;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private BotContainer botContainer;
    @Resource
    private CredentialGuard credentialGuard;

    /**
     * 添加订阅
     *
     * @param bot 机器人
     * @param event 收到的消息
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "^" + CMD_PREFIX + "添加订阅.*", at = AtEnum.BOTH)
    public void addPush(Bot bot, AnyMessageEvent event) {
        try {
            String message = parseMessage(event.getMessage());
            if (message.isEmpty()) {
                sendErrorMessage(bot, event, BiliBiliContant.Format_Error);
                return;
            }

            String[] split = message.split(" ");
            long roomId;

            try {
                roomId = Long.parseLong(split[0]);
            } catch (NumberFormatException e) {
                sendErrorMessage(bot, event, BiliBiliContant.Format_Error_);
                log.error(BiliBiliContant.Format_Error_, e);
                return;
            }

            int[] pushSettings = parsePushSettings(split, bot, event);
            int livePush = pushSettings[0];
            int dynamicPush = pushSettings[1];

            PushInfoResp pushAdd = pushInfoService.pushAdd(roomId, event, livePush, dynamicPush);
            if (pushAdd.getHas()) {
                sendSuccessMessage(bot, event);
            } else {
                sendMsd(bot, event, pushAdd);
            }
        } catch (Exception e) {
            sendErrorMessage(bot, event, BiliBiliContant.Exception + "\n异常信息\n" + e.getMessage());
            log.error("处理添加订阅时发生异常", e);
        }
    }

    /**
     * 取消订阅
     * @param bot 机器人
     * @param event 信息
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "^" + CMD_PREFIX + "取消订阅.*", at = AtEnum.BOTH)
    public void delPush(Bot bot, AnyMessageEvent event) {
        try {
            String message = parseMessage(event.getMessage());

            if (message.isEmpty()) {
                boolean del = pushInfoService.pushDel(event);
                sendDelResponse(bot, event, del, "取消订阅失败！订阅了多个房间，请加上房间号!");
                return;
            }

            Long roomId = Long.parseLong(message);
            boolean del = pushInfoService.pushDel(event, roomId);
            sendDelResponse(bot, event, del, "取消订阅失败！" + BiliBiliContant.Format_Error_);
        } catch (NumberFormatException e) {
            sendErrorMessage(bot, event, BiliBiliContant.Format_Error_);
            log.error(BiliBiliContant.Format_Error_, e);
        } catch (Exception e) {
            sendErrorMessage(bot, event, BiliBiliContant.Exception + "\n异常信息\n" + e.getMessage());
            log.error("处理取消订阅时发生异常", e);
        }
    }

    // ================================================================== 命令：开播 @ 谁
    //
    // 背景：push_info 早有 at_all / at_list 两列，发送侧（PushInfoServiceImpl#buildMessage）
    // 也一直在按它们决定 @全体 / @某人 / 不 @ —— 但<b>从来没有任何命令能写这两列</b>，
    // 想设置只能连服务器改库。下面四条命令就是补上这个缺口。
    //
    // 权限口径（按"影响面"选，与 BotAdminChecker 的注释同一套逻辑）：
    //   ·「开播@全体」影响<b>整个群</b>的每一个人 ⇒ 要群管理员（群主/群管理 role）或机器人管理员，
    //     即 BotAdminChecker#isAdmin；
    //   ·「开播@我」只改<b>自己那一个 QQ</b> ⇒ 任何群成员都可用，不该设门槛。
    // 两者都<b>只在群里有效</b>：私聊通知本来就只发给订阅者本人，@ 谁没有意义。

    /** 开播 @全体成员：`开播@全体 1024`（群管理员 / 机器人管理员） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_AT_ALL_ON, at = AtEnum.BOTH)
    public void atAllOn(Bot bot, AnyMessageEvent event) {
        handleAtAll(bot, event, true);
    }

    /** 取消开播 @全体成员：`取消开播@全体 1024` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_AT_ALL_OFF, at = AtEnum.BOTH)
    public void atAllOff(Bot bot, AnyMessageEvent event) {
        handleAtAll(bot, event, false);
    }

    /** 开播 @我：`开播@我 1024`（任何群成员，只影响自己） */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_AT_ME_ON, at = AtEnum.BOTH)
    public void atMeOn(Bot bot, AnyMessageEvent event) {
        handleAtMe(bot, event, true);
    }

    /** 取消开播 @我：`取消开播@我 1024` */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_AT_ME_OFF, at = AtEnum.BOTH)
    public void atMeOff(Bot bot, AnyMessageEvent event) {
        handleAtMe(bot, event, false);
    }

    /** 「开播@全体 / 取消开播@全体」的公共体 */
    private void handleAtAll(Bot bot, AnyMessageEvent event, boolean on) {
        String cmd = on ? "开播@全体" : "取消开播@全体";
        try {
            Long groupId = event.getGroupId();
            if (groupId == null) {
                reply(bot, event, "「" + cmd + "」要在群里发 —— @全体只对群里的开播通知有意义，"
                        + "私聊通知本来就只发给你一个人。");
                return;
            }
            if (!botAdminChecker.isAdmin(event)) {
                reply(bot, event, "「开播@全体」只给本群管理员（群主 / 群管理员）或机器人管理员用。\n"
                        + "想让开播时单独 @ 你自己，发「开播@我 房间号」—— 那个人人可用。");
                return;
            }
            Long roomId = pickRoom(bot, event, groupId, cmd, true);
            if (roomId == null) {
                return;                     // 已经回复过了（没有订阅 / 订阅了多个，列出来让他挑）
            }
            if (!pushInfoService.setLivePushAtAll(groupId, roomId, on)) {
                reply(bot, event, "本群还没订阅房间 " + roomId + "，先让管理员发「添加订阅 " + roomId + "」。");
                return;
            }
            if (!on) {
                reply(bot, event, "✅ 已关闭：房间 " + roomId + " 开播时不再 @全体成员。");
                return;
            }
            StringBuilder sb = new StringBuilder("✅ 已开启：房间 " + roomId + " 开播时 @全体成员。");
            if (!botIsGroupAdmin(bot, groupId)) {
                // 说清楚"为什么可能不生效"，省得他等到开播才发现
                sb.append("\n⚠️ 机器人当前不是本群的管理员/群主 —— 「@全体」发不出去，"
                        + "开播通知会退化成普通消息。先去群管理里把机器人设为管理员。");
            }
            sb.append("\n（想关掉：取消开播@全体 ").append(roomId).append("）");
            reply(bot, event, sb.toString());
        } catch (Exception e) {
            sendErrorMessage(bot, event, BiliBiliContant.Exception + "\n异常信息\n" + e.getMessage());
            log.error("处理「{}」命令时发生异常", cmd, e);
        }
    }

    /** 「开播@我 / 取消开播@我」的公共体 */
    private void handleAtMe(Bot bot, AnyMessageEvent event, boolean on) {
        String cmd = on ? "开播@我" : "取消开播@我";
        try {
            Long groupId = event.getGroupId();
            if (groupId == null) {
                reply(bot, event, "「" + cmd + "」要在群里发 —— 私聊的开播通知本来就只发给你自己。");
                return;
            }
            // 房间号可省：本群只订阅了一个房间就自动落到它，多个则列出来让用户挑
            Long roomId = pickRoom(bot, event, groupId, cmd, false);
            if (roomId == null) {
                return;
            }
            // 普通成员即可用：只动自己那一个 QQ，不会影响别人的设置，所以不设权限门槛
            Long userId = event.getUserId();
            if (!pushInfoService.setLivePushAtMe(groupId, roomId, userId, on)) {
                reply(bot, event, "本群还没订阅房间 " + roomId + "，先让管理员发「添加订阅 " + roomId + "」。");
                return;
            }
            reply(bot, event, on
                    ? "✅ 已设置：房间 " + roomId + " 开播时 @ 你。\n（想取消：取消开播@我 " + roomId + "）"
                    : "✅ 已取消：房间 " + roomId + " 开播时不再 @ 你。");
        } catch (Exception e) {
            sendErrorMessage(bot, event, BiliBiliContant.Exception + "\n异常信息\n" + e.getMessage());
            log.error("处理「{}」命令时发生异常", cmd, e);
        }
    }

    /**
     * 决定这次要操作的房间号：<b>命令里带了就用它</b>；没带则
     * <ul>
     *   <li>本群<b>只订阅了一个</b>房间 ⇒ 直接用它，用户不用打房间号；</li>
     *   <li>没有订阅 ⇒ 回一句提示；</li>
     *   <li>订了<b>多个</b> ⇒ 把房间（含主播名与当前 @ 状态）列出来，让他补房间号再发。</li>
     * </ul>
     *
     * @param forAtAll {@code true} = 这轮在设「开播@全体」（列表多标一列"全体是否已开"）；
     *                 {@code false} = 在设「开播@我」（多标"你是否已在名单里"）
     * @return 房间号；返回 {@code null} 表示<b>已经回复过用户了</b>，调用方直接 return 即可
     */
    private Long pickRoom(Bot bot, AnyMessageEvent event, Long groupId, String cmd, boolean forAtAll) {
        Long roomId = extractRoomId(event.getMessage());
        if (roomId != null) {
            return roomId;
        }
        List<PushInfoService.RoomAtOption> rooms = pushInfoService.listRoomOptions(groupId);
        if (rooms.isEmpty()) {
            reply(bot, event, "本群还没订阅任何房间，先让管理员发「添加订阅 房间号」。");
            return null;
        }
        if (rooms.size() == 1) {
            // 只有一个订阅 —— 用户说的"加在其上面"，省掉房间号
            return rooms.get(0).roomId();
        }
        reply(bot, event, renderRoomChoices(cmd, event.getUserId(), forAtAll, rooms));
        return null;
    }

    /**
     * 把本群订阅的房间列成一份"挑一个"的清单：房间号 +（主播名）+ 当前的 @ 状态。
     *
     * <p>主播名来自 {@code push_info.uname}（订阅时存下来的）；老数据没有时
     * {@code listRoomOptions} 会按需补一次，补不到就只显示房间号。
     */
    private String renderRoomChoices(String cmd, Long selfId, boolean forAtAll,
                                     List<PushInfoService.RoomAtOption> rooms) {
        StringBuilder sb = new StringBuilder("本群订阅了 ").append(rooms.size())
                .append(" 个房间，请带上要设置的那一个：\n");
        for (PushInfoService.RoomAtOption r : rooms) {
            sb.append("· ").append(r.roomId());
            if (r.uname() != null && !r.uname().isBlank()) {
                sb.append("（").append(r.uname().strip()).append("）");
            }
            if (forAtAll) {
                if (r.atAll()) {
                    sb.append("　开播@全体已开");
                }
            } else if (r.atMe().contains(selfId)) {
                sb.append("　你已在 @ 名单里");
            }
            sb.append("\n");
        }
        sb.append("格式：").append(cmd).append(" 房间号　例：")
                .append(cmd).append(" ").append(rooms.get(0).roomId());
        return sb.toString();
    }

    /**
     * 从命令消息里取房间号（第一个数字串）。
     *
     * <p>先剥 CQ 码与"手打的 @机器人"（{@link CmdPrefix#stripMention}），再取第一段数字 ——
     * 这几个命令的词（开播 / 取消开播 / @全体 / @我）都不含数字，所以取到的就是房间号。
     * 用 {@code Matcher#find()} 而非 {@code matches()}，免得被中英文空格等形态影响。
     */
    private Long extractRoomId(String message) {
        if (message == null) {
            return null;
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ");
        body = CmdPrefix.stripMention(body);
        Matcher m = ROOM_ID.matcher(body);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 机器人自己在群里是不是管理员 / 群主 —— 决定了「@全体」发不发得出去。
     *
     * <p>发送侧 {@code PushInfoServiceImpl#isGroupAdmin} 用的是同一个判据；这里只是为了在
     * <b>设置的那一刻</b>就把结果告诉用户，而不是让他等到开播才发现 @全体 没生效。
     */
    private boolean botIsGroupAdmin(Bot bot, Long groupId) {
        try {
            ActionData<GroupMemberInfoResp> info = bot.getGroupMemberInfo(groupId, bot.getSelfId(), false);
            String role = info == null || info.getData() == null ? null : info.getData().getRole();
            return "admin".equals(role) || "owner".equals(role);
        } catch (Exception e) {
            log.warn("查询机器人在群 {} 的角色失败", groupId, e);
            return false;
        }
    }

    /**
     * 回一句话并 @ 发命令的人。
     *
     * <p><b>为什么必须带 @</b>：出站熔断 {@code SendGuardAspect} 对"逐字相同"的内容
     * （≥3 条）会把整个目标静默 60 秒 —— 多人轮流发「开播@我 1024」时，如果回复是同一串
     * 纯文本，从第 3 个人起就收不到了。带上 @ 段（含各自 QQ）后每条都唯一，天然避开这个坑。
     */
    private void reply(Bot bot, AnyMessageEvent event, String text) {
        sendMessage(bot, event, MsgUtils.builder()
                .at(event.getUserId())
                .text(BiliBiliContant.escapeCq(text))
                .build());
    }

    /**
     * 直播推送重入保护：上一轮还没跑完就跳过本轮。
     *
     * <p><b>为什么必须有</b>（与 {@link #dynamicPushRunning} 同一个理由，但直播这边漏掉了）：
     * {@code @Async} + {@code @Scheduled} 的组合下，调度线程是"把方法丢给线程池就返回"，
     * 它并不知道异步任务何时结束。而一轮直播推送要对每个订阅打若干次 HTTP，
     * 耗时超过 10 秒的调度间隔是常态 —— 没有这道闸，两轮会<b>并发</b>执行，
     * 各自读到"库里还是未开播"、各自判定"状态变了"，于是同一个开播事件被<b>同时推好几条</b>。
     * 这也是 2026-10-02 用户报的"直播推送有时候会同时发好多次"的根因之一。
     */
    private final AtomicBoolean livePushRunning = new AtomicBoolean(false);

    /**
     * 直播推送
     */
    @Async
    @Scheduled(cron = "0/10 * * * * *")
    public void livePush() {
        if (!livePushRunning.compareAndSet(false, true)) {
            log.warn("上一轮直播推送尚未结束，跳过本轮");
            return;
        }
        try {
            Bot bot = getBotFromConfig();
            if (bot == null) {
                return;
            }
            pushInfoService.livePush(bot);
        } catch (Throwable e) {
            // ★ Throwable 而非 Exception：与 dynamicPush 同一口径 ——
            //   Java2D 在无字体环境下抛的是 java.lang.InternalError（Error），catch(Exception) 兜不住。
            log.error("直播推送执行异常", e);
        } finally {
            livePushRunning.set(false);
        }
    }

    /**
     * 动态推送重入保护：上一轮还没跑完就跳过本轮。
     *
     * <p>{@code @Async} + {@code @Scheduled} 的组合下，调度线程是「把方法丢给线程池就返回」，
     * 它并不知道异步任务何时结束；而一轮动态推送要发 HTTP 拉 feed、下载图片、Java2D 渲染，
     * 耗时完全可能超过调度间隔。没有这道闸，慢的一轮会和下一轮叠成并发请求 ——
     * 既浪费请求配额，也正是把 B 站风控（-352）招来的原因。
     */
    private final AtomicBoolean dynamicPushRunning = new AtomicBoolean(false);

    /**
     * 动态推送（每 60 秒一轮）
     *
     * <p>与 {@link #livePush()} 同源：从 config 表读 botQQ 找 Bot 实例，
     * 真正的判断/发送逻辑在 {@code PushInfoServiceImpl.dynamicPush}。
     *
     * <p><b>为什么是 60 秒而不是更快</b>：动态推送是本项目对 {@code api.bilibili.com}
     * 唯一的周期性出站流量，而 B 站的 412 是"请求密度"敏感型风控（实测 1 秒内 3 个请求就触发）。
     * 60 秒 × 每 uid 1 次请求已经足够及时（配合 15 分钟的新鲜窗口与末次去重，
     * 真正的新动态最迟 60 秒内必推，且不会重复），却是对出口 IP 最友好的节奏。
     */
    @Async
    @Scheduled(fixedRate = 60000)
    public void dynamicPush() {
        if (!dynamicPushRunning.compareAndSet(false, true)) {
            log.warn("上一轮动态推送尚未结束，跳过本轮");
            return;
        }
        try {
            Bot bot = getBotFromConfig();
            if (bot == null) {
                return;
            }
            long startTime = System.currentTimeMillis();
            pushInfoService.dynamicPush(bot);
            long cost = System.currentTimeMillis() - startTime;
            if (cost > 60000) {
                log.warn("动态推送耗时过长，耗时：{}ms", cost);
            } else {
                log.info("动态推送完成，耗时：{}ms", cost);
            }
        } catch (Throwable e) {
            // ★ Throwable 而非 Exception：无字体环境下 Java2D 抛的是 java.lang.InternalError，
            //   它是 Error，catch(Exception) 兜不住，会一路冲到 AsyncUncaughtExceptionHandler，
            //   整轮推送就此中断（2026-09-14 真机踩到）。
            log.error("动态推送执行异常", e);
        } finally {
            dynamicPushRunning.set(false);
        }
    }

    /**
     * B 站凭据<b>兜底探测</b>（低频）。
     *
     * <p>为什么需要它：P0-1 的主形态是<b>事件驱动</b> —— 动态推送一失败就顺手问一次服务端
     * （见 {@code PushInfoServiceImpl#dynamicPush} 的失败分支），常态零额外请求。
     * 可它有个盲区：<b>一整天没有动态可推 ⇒ 推送从不失败 ⇒ 永远不探</b>，
     * 于是"凭据昨天就废了"要等到下一条动态出现才暴露 —— 这正是本任务兜的洞。
     *
     * <p>本任务只负责"到点了敲一下"：真正的间隔判定在
     * {@link CredentialGuard#maybeProbeFallback()}（默认 6 小时一次，
     * 可用 config 表 {@code biliCredentialCheckHours} 调整，0 = 关闭兜底）；
     * 启动后第一次触发即会校验一次，顺带实现"长驻进程启动时验一次凭据"。
     *
     * <p>调度频率取 10 分钟（远细于兜底间隔）只是为了让"改了配置"较快生效，
     * <b>绝大多数轮次读完一个时间戳就返回，一个请求都不发</b>。
     */
    @Async
    @Scheduled(fixedRate = 600_000)
    public void credentialCheck() {
        try {
            credentialGuard.maybeProbeFallback();
        } catch (Throwable e) {
            // ★ Throwable 而非 Exception：与其它异步入口同一口径
            //   （Java2D 在无字体环境抛的是 java.lang.InternalError，catch(Exception) 兜不住）
            log.error("B 站凭据兜底探测执行异常", e);
        }
    }

    /**
     * 根据配置获取 Bot 实例
     * @return 返回 Bot 实例或 null
     */
    private Bot getBotFromConfig() {
        String botQQStr = loadDSConfig.getConfigMap().get("botQQ");
        if (botQQStr == null) {
            log.error("机器人QQ未在配置文件中设置");
            return null;
        }

        Long qq;
        try {
            qq = Long.valueOf(botQQStr);
        } catch (NumberFormatException e) {
            log.error("从配置获取机器人QQ失败: {}", botQQStr, e);
            return null;
        }

        Bot bot = botContainer.robots.get(qq);
        if (bot == null) {
            log.error("未找到机器人实例，QQ: {}", qq);
        }

        return bot;
    }
    private void sendMsd(Bot bot, AnyMessageEvent event, PushInfoResp pushAdd) {
        String sendMsg = MsgUtils.builder().at(event.getUserId())
                .text("添加订阅 " + BiliBiliContant.escapeCq(pushAdd.getName()) + " 成功!\n" +
                        (!pushAdd.getLivePush() ? "直播推送" : "") +
                        (!pushAdd.getDynamicPush() ? (pushAdd.getLivePush() ? "和动态推送" : "动态推送") + "关闭" : ""))
                .build();
        sendMessage(bot, event, sendMsg);
    }

    /**
     * 取命令参数：剥掉 CQ 码（真 @ / 图片等）、<b>手打的 {@code @机器人}</b>、以及命令词本身。
     *
     * <p>⚠️ 手打的 {@code @Hana} 只是<b>普通文本</b>，不是 CQ 段，光剥 CQ 码剥不掉 ——
     * 不剥的话 {@code @Hana 添加订阅 123} 会先变成 {@code @Hana 123}，
     * {@code split(" ")[0]} 拿到 {@code @Hana}，直接回一句"格式错误"。
     * 剥法见 {@link CmdPrefix#stripMention}（先按已知称呼精确剥，认不出再整段剥）。
     */
    private String parseMessage(String message) {
        if (message == null) {
            return "";
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ");
        body = CmdPrefix.stripMention(body);
        // replaceFirst 而不是 replace：命令词只该吃掉"最前面那一个"，
        // 用 replace 会把正文里出现的同名字样也一并抹掉（提示词里带"添加订阅"就中招）
        body = body.replaceFirst("(?isU)(?:添加订阅|取消订阅)", " ");
        return body.strip();
    }

    private void sendErrorMessage(Bot bot, AnyMessageEvent event, String errorMessage) {
        // errorMessage 里会带 e.getMessage()（服务端原文）⇒ 转义后再拼
        sendMessage(bot, event, MsgUtils.builder().at(event.getUserId())
                .text(BiliBiliContant.escapeCq(errorMessage)).build());
    }

    private void sendSuccessMessage(Bot bot, AnyMessageEvent event) {
        sendMessage(bot, event, MsgUtils.builder().at(event.getUserId()).text(BiliBiliContant.Added_Live).build());
    }

    private int[] parsePushSettings(String[] split, Bot bot, AnyMessageEvent event) {
        int livePush = 0;
        int dynamicPush = 0;

        if (split.length >= 2) {
            try {
                livePush = Integer.parseInt(split[1]);
            } catch (NumberFormatException e) {
                sendErrorMessage(bot, event, BiliBiliContant.Format_Error);
                return new int[]{0, 0};
            }
        }

        if (split.length >= 3) {
            try {
                dynamicPush = Integer.parseInt(split[2]);
            } catch (NumberFormatException e) {
                sendErrorMessage(bot, event, BiliBiliContant.Format_Error);
                return new int[]{0, 0};
            }
        }

        return new int[]{livePush, dynamicPush};
    }

    private void sendDelResponse(Bot bot, AnyMessageEvent event, boolean success, String errorMessage) {
        String sendMsg = success ? "取消订阅成功！" : errorMessage;
        sendMessage(bot, event, sendMsg);
    }

    private void sendMessage(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }
}
