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
 * <p>⚠️ 轮转状态是<b>进程内内存</b>的（不落库）：重启后从新的一轮开始。
 * 代价只是"重启后可能重复抽到"，换来的是零 DDL、零配置项 ——
 * 与 {@code pushedDynamicIds} 那种"必须落库否则功能坏了"的情形不同，这里不值得。
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

    /**
     * 每个群的轮转状态。
     *
     * <p>键是群号；条目只有在群真的用过这个功能后才会出现（自然按群懒加载）。
     */
    private final Map<Long, GroupRotation> rotations = new ConcurrentHashMap<>();

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

        GroupMemberInfoResp wife = pickWife(groupId, candidates);
        bot.sendMsg(event, buildWifeMsg(qqUid, wife), false);
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
