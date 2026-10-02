package com.esdllm.botPlugins;

import com.esdllm.bilibiliApi.bilibiliApi.Ranking;
import com.esdllm.bilibiliApi.bilibiliApi.Search;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.model.data.pojo.video.PopularList;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import com.esdllm.common.NumFormat;
import com.esdllm.contant.BiliBiliContant;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B 站<b>搜索 / 热搜 / 热门</b>三条查询命令（P1-4）。
 *
 * <p><b>支持的命令</b>：
 * <pre>
 * 搜视频 影视飓风     ← 视频搜索 Top5（标题 / UP / 播放量 / 链接）
 * 热搜               ← 热搜榜 Top10
 * 今日热门           ← 全站综合热度 Top5
 * </pre>
 *
 * <p><b>为什么不做权限门</b>：三条命令都是<b>只读、匿名、无副作用</b> ——
 * 不改任何配置、不动订阅数据、不需要 Cookie（三个端点实测都不需要凭据），
 * 与"发个链接自动解析"是同一性质。给它们套管理员权限只会让群员问不出话，
 * 并不能换来任何安全性。<b>真正需要闸门的是"改全局配置"与"夺取出站身份"那两类</b>
 * （见 {@code BiliConfigPlugins} / {@code BiliLoginPlugins}）。
 *
 * <p><b>但要有节流</b>：这三条命令每条都会真打 B 站（1 次，首次搜索还要多一步 WBI 签名），
 * 而 B 站的风控是<b>请求密度敏感</b>型。所以同一目标（群 / 私聊）3 秒内只处理一条 ——
 * 正常聊天不会触发，连点刷屏会被吃掉，避免把出口 IP 推上去。
 *
 * <p>⚠️ 三条命令都<b>不含凭据</b>：没有一条会因为"Cookie 过期"而失败，
 * 反过来它们也不会因为<b>带上</b> Cookie 而拿到更多东西。
 */
@Slf4j
@Component
@Shiro
public class BiliSearchPlugins {

    /** 允许出现在前置的 CQ 码（@机器人 / 图片等），匹配时忽略 */
    private static final String LEADING_CQ = "(\\[CQ:[^]]*\\]\\s*)*";

    /** `搜视频 xxx`（也接受「搜索视频」「视频搜索」） */
    private static final String CMD_SEARCH_VIDEO =
            "(?is)^" + LEADING_CQ + "(?:搜视频|搜索视频|视频搜索).*";
    /** `热搜`（也接受「热搜榜」） */
    private static final String CMD_HOT_SEARCH =
            "(?is)^" + LEADING_CQ + "(?:b站|B站)?(?:热搜榜|热搜)\\s*$";
    /** `今日热门`（也接受「热门视频」「B站热门」） */
    private static final String CMD_POPULAR =
            "(?is)^" + LEADING_CQ + "(?:b站|B站)?(?:今日热门|热门视频|热门)\\s*$";

    /** 搜索结果展示条数 */
    private static final int MAX_SEARCH_RESULT = 5;
    /** 热搜展示条数（与库的默认值一致） */
    private static final int HOT_LIMIT = 10;
    /** 热门展示条数 */
    private static final int POPULAR_LIMIT = 5;
    /** 关键词长度上限（防超长输入把请求 URL 撑坏） */
    private static final int MAX_KEYWORD_LENGTH = 50;
    /** 同一目标的最小命令间隔（毫秒） */
    private static final long MIN_INTERVAL_MS = 3000L;

    /**
     * 每个目标（群 / 私聊）上次执行命令的时刻。
     *
     * <p>键的数量等于"用过的群 + 私聊数"，天然有限，不会无界增长；
     * 用 {@link ConcurrentHashMap} 是因为可能有多条消息并发进来。
     */
    private final Map<Long, Long> lastCallAt = new ConcurrentHashMap<>();

    /**
     * 视频搜索：`搜视频 <关键词>`。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SEARCH_VIDEO, at = AtEnum.BOTH)
    public void searchVideo(Bot bot, AnyMessageEvent event) {
        try {
            if (tooFast(event)) {
                return;
            }
            String keyword = extractKeyword(event.getMessage());
            if (keyword.isEmpty()) {
                reply(bot, event, "用法：搜视频 关键词\n例如：搜视频 影视飓风");
                return;
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                reply(bot, event, "关键词太长了（超过 " + MAX_KEYWORD_LENGTH + " 个字），换个短点的吧");
                return;
            }

            SearchTypeResult<SearchVideo> page;
            try {
                page = new Search().searchVideos(keyword, 1);
            } catch (Exception e) {
                log.error("B 站视频搜索失败：keyword={}，{}", keyword, e.getMessage());
                reply(bot, event, "搜索失败了：" + e.getMessage());
                return;
            }
            List<SearchVideo> videos = page == null ? null : page.getResult();
            if (videos == null || videos.isEmpty()) {
                reply(bot, event, "没搜到「" + keyword + "」相关的视频");
                return;
            }

            StringBuilder sb = new StringBuilder("搜「").append(keyword).append("」");
            if (page.getNumResults() != null) {
                sb.append(" 共约 ").append(NumFormat.count(page.getNumResults().longValue())).append(" 个结果");
            }
            int count = 0;
            for (SearchVideo video : videos) {
                if (video == null || video.getBvid() == null) {
                    continue;
                }
                if (count >= MAX_SEARCH_RESULT) {
                    break;
                }
                sb.append("\n\n").append(count + 1).append(". ").append(clean(video.getCleanTitle()))
                        .append("\n   ").append(clean(video.getAuthor()))
                        .append(" · 播放 ").append(NumFormat.count(video.getPlay()));
                if (video.getDuration() != null && !video.getDuration().isBlank()) {
                    sb.append(" · ").append(video.getDuration());
                }
                sb.append("\n   https://www.bilibili.com/video/").append(video.getBvid());
                count++;
            }
            reply(bot, event, sb.toString());
        } catch (Throwable e) {
            log.error("处理「搜视频」命令异常", e);
            reply(bot, event, "搜索出错了：" + e.getMessage());
        }
    }

    /**
     * 热搜榜：`热搜`。
     *
     * <p>注意榜单在响应的 {@code data.trending} 里，不是 {@code data} 本身 ——
     * 库的门面已经把这一层收好了（{@link HotSearch#getTrending()}）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_HOT_SEARCH, at = AtEnum.BOTH)
    public void hotSearch(Bot bot, AnyMessageEvent event) {
        try {
            if (tooFast(event)) {
                return;
            }
            HotSearch hot;
            try {
                hot = new Search().getHotSearch(HOT_LIMIT);
            } catch (Exception e) {
                log.error("取 B 站热搜失败：{}", e.getMessage());
                reply(bot, event, "取热搜失败了：" + e.getMessage());
                return;
            }
            List<HotSearch.Item> items =
                    hot == null || hot.getTrending() == null ? null : hot.getTrending().getList();
            if (items == null || items.isEmpty()) {
                reply(bot, event, "热搜榜现在是空的");
                return;
            }
            StringBuilder sb = new StringBuilder("B站热搜榜");
            int count = 0;
            for (HotSearch.Item item : items) {
                if (item == null || item.getKeyword() == null || item.getKeyword().isBlank()) {
                    continue;
                }
                if (count >= HOT_LIMIT) {
                    break;
                }
                sb.append("\n").append(count + 1).append(". ").append(item.getKeyword());
                if (item.getHeat_score() != null) {
                    sb.append("  🔥").append(NumFormat.count(item.getHeat_score()));
                }
                count++;
            }
            reply(bot, event, sb.toString());
        } catch (Throwable e) {
            log.error("处理「热搜」命令异常", e);
            reply(bot, event, "取热搜出错了：" + e.getMessage());
        }
    }

    /**
     * 今日热门：`今日热门`。
     *
     * <p>⚠️ {@code getPopular} 的参数顺序是 <b>(ps 每页条数, pn 页码)</b> ——
     * 「每页数」在前，与"页码在前"的直觉相反，写反了会拿到"每页 1 条的第 5 页"。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_POPULAR, at = AtEnum.BOTH)
    public void popular(Bot bot, AnyMessageEvent event) {
        try {
            if (tooFast(event)) {
                return;
            }
            PopularList list;
            try {
                list = new Ranking().getPopular(POPULAR_LIMIT, 1);
            } catch (Exception e) {
                log.error("取 B 站热门失败：{}", e.getMessage());
                reply(bot, event, "取热门失败了：" + e.getMessage());
                return;
            }
            List<VideoBrief> videos = list == null ? null : list.getList();
            if (videos == null || videos.isEmpty()) {
                reply(bot, event, "暂时没拿到热门列表");
                return;
            }
            StringBuilder sb = new StringBuilder("B站今日热门");
            int count = 0;
            for (VideoBrief video : videos) {
                if (video == null || video.getBvid() == null) {
                    continue;
                }
                if (count >= POPULAR_LIMIT) {
                    break;
                }
                sb.append("\n\n").append(count + 1).append(". ").append(clean(video.getTitle()));
                String upName = video.getOwner() == null ? null : video.getOwner().getName();
                if (upName != null && !upName.isBlank()) {
                    sb.append("\n   ").append(upName);
                }
                Long view = video.getStat() == null ? null : video.getStat().getView();
                if (view != null) {
                    sb.append(" · 播放 ").append(NumFormat.count(view));
                }
                sb.append("\n   https://www.bilibili.com/video/").append(video.getBvid());
                count++;
            }
            reply(bot, event, sb.toString());
        } catch (Throwable e) {
            log.error("处理「今日热门」命令异常", e);
            reply(bot, event, "取热门出错了：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 从消息里取关键词：剥掉 CQ 码与命令词，剩下的一整段（允许中间有空格）。
     */
    private static String extractKeyword(String message) {
        if (message == null) {
            return "";
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ");
        body = body.replaceFirst("(?is)(?:搜视频|搜索视频|视频搜索)", " ");
        return body.trim();
    }

    /**
     * 同一目标 3 秒内只处理一条命令。
     *
     * <p>命中时限<b>静默忽略</b>（只记 debug）：节流的存在是为了挡"连点刷屏"，
     * 而"慢一点"这类提示本身也会变成一串消息。正常使用触发不到它。
     *
     * @return {@code true} 表示本条被节流吃掉，调用方应直接返回
     */
    private boolean tooFast(AnyMessageEvent event) {
        Long target = event.getGroupId() != null ? event.getGroupId() : event.getUserId();
        if (target == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastCallAt.get(target);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            log.debug("B 站查询命令过于频繁，已忽略：target={}", target);
            return true;
        }
        lastCallAt.put(target, now);
        return false;
    }

    /**
     * 统一的回复出口：<b>群聊里 @ 提问者，私聊不加</b>。
     *
     * <p>私聊不加 @ 是因为那会无端显示一个"@自己"；群里加是因为多条命令交错时
     * 不加 @ 分不清回给谁。
     *
     * <p>⚠️ 正文一律过 {@link BiliBiliContant#escapeCq(String)}：这里回的内容里
     * <b>有大量第三方文本</b>（视频标题、UP 名、热搜词），甚至 {@code e.getMessage()} 里也可能
     * 夹带服务端返回的原文。发送参数是 {@code autoEscape=false}（否则 {@code builder.at} 就不起作用了），
     * 所以不转义就等于把"替别人 @全体成员"的能力开放出去。收口在这一处是为了
     * <b>三条命令 + 所有异常分支一次覆盖</b>，不必在每个拼接点各写一遍。
     */
    private static void reply(Bot bot, AnyMessageEvent event, String message) {
        MsgUtils builder = MsgUtils.builder();
        if (event.getGroupId() != null) {
            builder = builder.at(event.getUserId());
        }
        bot.sendMsg(event, builder.text(BiliBiliContant.escapeCq(message)).build(), false);
    }

    /**
     * 清洗服务端文本：{@code null} → 空串。
     *
     * <p>⚠️ <b>标题必须用 {@code getCleanTitle()}，不能直接用 {@code getTitle()}</b> ——
     * 搜索结果里的标题带 {@code <em class="keyword">} 关键词高亮标签，直接用会发出
     * {@code 标题里有<em>关键词</em>} 这种东西。这里只兜 {@code null}，不自己写正则去掉标签
     * （那是库已经做过的事，自己做只会做出第二套口径）。
     */
    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
