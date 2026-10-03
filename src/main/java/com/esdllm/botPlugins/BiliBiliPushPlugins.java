package com.esdllm.botPlugins;

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
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Shiro
public class BiliBiliPushPlugins {
    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private PushInfoService pushInfoService;
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
    @MessageHandlerFilter(cmd = "^添加订阅.*", at = AtEnum.BOTH)
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
    @MessageHandlerFilter(cmd = "^取消订阅.*", at = AtEnum.BOTH)
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

    private String parseMessage(String message) {
        return message.trim().replaceAll("\\[CQ:[^]]*]", "").trim().replace("添加订阅", "").trim().replace("取消订阅", "").trim();
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
