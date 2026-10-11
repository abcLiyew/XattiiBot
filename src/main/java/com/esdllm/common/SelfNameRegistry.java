package com.esdllm.common;

import com.esdllm.config.LoadDSConfig;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupInfoResp;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.action.response.LoginInfoResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>运行时</b>去问 OneBot「机器人自己叫什么」，把结果喂给 {@link CmdPrefix}。
 *
 * <p>为什么要有这个类：命令头要能认「{@code @机器人 搜视频 xxx}」这种写法，
 * 可是「机器人叫什么」<b>不能写死在代码里</b> —— 换号、改昵称、改群名片都会变，
 * 写死的白名单换个号就作废（这正是 2026-10-11 用户提的要求：
 * 「你这里写死，那换个号就作废了，所以不要在代码里写死」）。
 * 所以改成每次都由 OneBot 现拿：
 * <ol>
 *   <li>{@code get_login_info().nickname} —— 账号昵称（全局有效）；</li>
 *   <li>{@code get_group_list()} → 逐群 {@code get_group_member_info(group_id, self_id)}
 *       的 {@code card} —— <b>群名片</b>。群名片可以一个群一个样，所以按群分开存
 *       （{@link CmdPrefix#setOneBotNames(Collection, Map)}），
 *       免得 A 群里给机器人起的名字在 B 群里也能触发出命令；</li>
 *   <li>自身 QQ 号 —— 有人直接 {@code @213481590 搜视频} 也认。</li>
 * </ol>
 *
 * <p><b>刷新节奏</b>：{@value #REFRESH_MILLIS}ms 一次（昵称/名片大体是静态的，没必要更勤）；
 * 但<b>还没拿到称呼时每 {@value #RETRY_SECONDS} 秒重试</b> —— 反向 WS 是 NapCat 连上来才建 Bot，
 * 服务先起、OneBot 后连是常态，不能"启动时没连上就一直空着"。
 * {@link CmdPrefix#isAddressedToSelf} 在称呼为空时会降级放行并告警，所以这段空窗期是"宽松"
 * 而不是"没反应"，等第一次拿到后自动转严格。
 *
 * <p><b>任何一次调用失败都不影响其它</b>：单个群的名片取不到就跳过那个群（该群退化成
 * "只认账号昵称"），全部失败就<b>保留上一次的结果</b>（不把已经拿到的称呼清空）。
 *
 * <p><b>为什么不用 {@code noCache=true}</b>：{@code false} 让 NapCat 用自己那份缓存，省掉一次
 * 上游请求；昵称/名片变更是低频事件，缓存那点延迟无所谓。
 */
@Slf4j
@Component
public class SelfNameRegistry {

    /** 拿到称呼后的常规刷新间隔。 */
    private static final long REFRESH_MILLIS = 5 * 60_000L;

    /** 还没拿到称呼时的重试间隔（等 NapCat 连上来）。 */
    private static final int RETRY_SECONDS = 30;

    @Resource
    private BotContainer botContainer;

    @Resource
    private LoadDSConfig loadDSConfig;

    private volatile long lastSuccessAt;

    /** 上次写进 {@link CmdPrefix} 的内容，用于"变了才打日志"。 */
    private volatile Set<String> lastKnown = Set.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 心跳：拿到过称呼就 5 分钟一次；没有就 30 秒重试一次。
     *
     * <p>{@code fixedDelay} 串行执行，不会叠加请求；判断放在方法里是为了让两个间隔共用一个触发器。
     */
    @Scheduled(initialDelay = 10_000L, fixedDelay = RETRY_SECONDS * 1000L)
    public void tick() {
        long now = System.currentTimeMillis();
        if (!CmdPrefix.oneBotNames().isEmpty() && now - lastSuccessAt < REFRESH_MILLIS) {
            return;
        }
        refresh();
    }

    /** 立刻去 OneBot 拉一遍昵称 + 各群群名片，写进 {@link CmdPrefix}。 */
    public void refresh() {
        try {
            applyAliases();

            Set<String> global = new LinkedHashSet<>();
            Map<Long, Collection<String>> perGroup = new LinkedHashMap<>();

            for (Bot bot : botContainer.robots.values()) {
                if (bot == null) {
                    continue;
                }
                long selfId = bot.getSelfId();
                global.add(String.valueOf(selfId));

                String nickname = loginNickname(bot);
                if (nickname != null && !nickname.isBlank()) {
                    global.add(nickname);
                }
                collectGroupCards(bot, selfId, perGroup);
            }

            if (global.isEmpty() && perGroup.isEmpty()) {
                // OneBot 还没连上来（robots 为空）。保留上一次结果，等下一轮重试。
                return;
            }
            CmdPrefix.setOneBotNames(global, perGroup);
            lastSuccessAt = System.currentTimeMillis();

            Set<String> known = CmdPrefix.oneBotNames();
            if (!known.equals(lastKnown)) {
                lastKnown = known;
                log.info("机器人称呼已从 OneBot 刷新：{}（含 {} 个群的群名片）", known, perGroup.size());
            }
        } catch (Exception e) {
            log.warn("刷新机器人称呼失败（沿用上一次的结果）", e);
        }
    }

    /** 账号昵称（全局）；取不到返回 {@code null}。 */
    private String loginNickname(Bot bot) {
        try {
            ActionData<LoginInfoResp> resp = bot.getLoginInfo();
            return resp == null || resp.getData() == null ? null : resp.getData().getNickname();
        } catch (Exception e) {
            log.warn("get_login_info 失败（selfId={}）：{}", bot.getSelfId(), e.getMessage());
            return null;
        }
    }

    /** 逐群取机器人在该群的群名片。取不到的群直接跳过（该群退化为"只认昵称/QQ号"）。 */
    private void collectGroupCards(Bot bot, long selfId, Map<Long, Collection<String>> out) {
        ActionList<GroupInfoResp> list;
        try {
            list = bot.getGroupList();
        } catch (Exception e) {
            log.warn("get_group_list 失败（selfId={}）：{}", selfId, e.getMessage());
            return;
        }
        if (list == null || list.getData() == null) {
            return;
        }
        for (GroupInfoResp group : list.getData()) {
            if (group == null || group.getGroupId() == null) {
                continue;
            }
            try {
                ActionData<GroupMemberInfoResp> me = bot.getGroupMemberInfo(group.getGroupId(), selfId, false);
                String card = me == null || me.getData() == null ? null : me.getData().getCard();
                if (card != null && !card.isBlank()) {
                    out.computeIfAbsent(group.getGroupId(), k -> new LinkedHashSet<>()).add(card);
                }
            } catch (Exception e) {
                log.debug("get_group_member_info 失败（group={}）：{}", group.getGroupId(), e.getMessage());
            }
        }
    }

    /**
     * 把配置项 {@code botNameAliases}（与账号无关的通用叫法，如「机器人」「bot」）喂给
     * {@link CmdPrefix}。
     *
     * <p>默认<b>空</b> —— 代码里一个具体名字都不写死，这才是"换号不作废"的关键。
     * 想加就发 {@code 开关 称呼 机器人,bot}（见 {@code BiliConfigPlugins} 的开关表）或直接改配置。
     * {@link LoadDSConfig#afterConfigChanged} 改到这个键时会立即刷一次，不必等这里的心跳。
     */
    public void applyAliases() {
        CmdPrefix.setAliasNames(CmdPrefix.splitNames(
                loadDSConfig.stringOf(LoadDSConfig.KEY_BOT_NAME_ALIASES, "")));
    }
}
