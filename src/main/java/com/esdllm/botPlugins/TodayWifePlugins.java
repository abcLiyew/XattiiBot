package com.esdllm.botPlugins;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 今日老婆插件
 * <p>
 * 群友发送关键词「今日老婆」，随机抽取群内另一位成员作为其今日老婆，
 * 并以 base64 图片的形式发送该成员头像。
 */
@Slf4j
@Shiro
@Component
public class TodayWifePlugins {

    /**
     * QQ 头像接口，s 为头像尺寸
     */
    private static final String QQ_AVATAR_URL = "https://q1.qlogo.cn/g?b=qq&nk=%d&s=640";

    /**
     * 头像下载超时时间，单位毫秒
     */
    private static final int TIMEOUT_MILLIS = 5000;

    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = ".*今日老婆.*", at = AtEnum.BOTH)
    public void todayWife(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();

        // 该功能仅在群聊中可用
        if (Objects.isNull(groupId)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 该功能仅限群聊使用哦~").build(), false);
            return;
        }

        List<GroupMemberInfoResp> candidates = getCandidates(bot, groupId, qqUid, event.getSelfId());
        if (Objects.isNull(candidates)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 获取群成员失败，稍后再试试吧~").build(), false);
            return;
        }
        if (candidates.isEmpty()) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 群里暂时没有别人，找不到老婆呢~").build(), false);
            return;
        }

        GroupMemberInfoResp wife = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        bot.sendMsg(event, buildWifeMsg(qqUid, wife), false);
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
