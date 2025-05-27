package com.esdllm.botPlugins;

import com.esdllm.config.LoadDSConfig;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.model.respObj.PushInfoResp;
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
     * 直播推送
     */
    @Async
    @Scheduled(cron = "0/10 * * * * *")
    public void livePush() {
        Bot bot = getBotFromConfig();

        pushInfoService.livePush(bot);
    }

    @Async
    @Scheduled(fixedRate = 30000)
    public void dynamicPush() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        Bot bot = getBotFromConfig();

        pushInfoService.dynamicPush(bot);
        long endTime = System.currentTimeMillis();
        if (endTime - startTime > 30000) {
            log.warn("动态推送耗时过长，耗时：{}ms", endTime - startTime);
        }else {
            Thread.sleep(20000 - (endTime - startTime));
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
                .text("添加订阅 " + pushAdd.getName() + " 成功!\n" +
                        (!pushAdd.getLivePush() ? "直播推送" : "") +
                        (!pushAdd.getDynamicPush() ? (pushAdd.getLivePush() ? "和动态推送" : "动态推送") + "关闭" : ""))
                .build();
        sendMessage(bot, event, sendMsg);
    }

    private String parseMessage(String message) {
        return message.trim().replaceAll("\\[CQ:[^]]*]", "").trim().replace("添加订阅", "").trim().replace("取消订阅", "").trim();
    }

    private void sendErrorMessage(Bot bot, AnyMessageEvent event, String errorMessage) {
        sendMessage(bot, event, MsgUtils.builder().at(event.getUserId()).text(errorMessage).build());
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
