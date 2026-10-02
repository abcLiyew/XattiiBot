package com.esdllm.service.impl;

import com.esdllm.bilibiliApi.bilibiliApi.*;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.comment.Comment;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import com.esdllm.bilibiliApi.model.data.pojo.content.ArticleInfo;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.*;
import com.esdllm.common.NumFormat;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.service.BilibiliAnalysis;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Objects;

/**
 * B 站链接解析。
 *
 * <p>⚠️ 本文件同时用到 <b>两个都叫 Comment 的类</b>，别搞混：
 * <ul>
 *   <li>{@code com.esdllm.bilibiliApi.bilibiliApi.Comment} —— <b>门面</b>（发请求的那个），
 *       走 {@code import ...bilibiliApi.*} 进来；</li>
 *   <li>{@code com.esdllm.bilibiliApi.model.data.pojo.comment.Comment} —— <b>模型</b>（一条评论），
 *       显式 import，按 Java 规则<b>单类型导入优先于通配导入</b>，所以下文写 {@code Comment}
 *       指的是模型；要门面时一律写全限定名。</li>
 * </ul>
 */
@Slf4j
@Service
public class BilibiliAnalysisImpl implements BilibiliAnalysis {

    /** 标签最多展示几个（实测一次会给十来个标签，全发会把消息撑得很长） */
    private static final int MAX_TAGS = 6;

    /** 相关推荐最多展示几条 */
    private static final int MAX_RELATED = 3;

    /** 热评最多展示几条（实测该接口一页只回 3 条，这里给上限是防它以后变多） */
    private static final int MAX_COMMENTS = 3;

    /** 单条热评正文的展示上限（原文可能几百字，原样发会把消息撑爆） */
    private static final int MAX_COMMENT_CHARS = 60;

    /** AI 摘要总纲的展示上限（实测样本本身就有一百四十来字，给宽一点但要有上限） */
    private static final int MAX_SUMMARY_CHARS = 200;

    /** AI 摘要最多展示几个分段（实测一个总结可能给十几段，全发等于把视频目录贴一遍） */
    private static final int MAX_SUMMARY_OUTLINE = 3;

    /** 每个摘要分段下最多展示几条要点 */
    private static final int MAX_SUMMARY_PARTS = 2;

    /** 单条摘要要点的展示上限 */
    private static final int MAX_SUMMARY_PART_CHARS = 40;

    @Resource
    private LoadDSConfig loadDSConfig;

    @Override
    public void bilibiliAnalysis(AnyMessageEvent event, String url, Bot bot) {
        if (url.contains("b23.tv")){
            ShortChain shortChain = new ShortChain(url);
            shortChainAnalysis(event,shortChain,bot);
            return;
        }
        if(url.contains("live.bilibili.com")){
            onLiveUrl(bot,event,url);
            return;
        }
        if (url.contains("bilibili.com/video")){
            onVideoUrl(bot,event,url);
            return;
        }
        if (url.contains("bilibili.com/opus")){
            onDynamicUrl(bot,event,url);
        }
        // ★ P1-3：专栏。改之前这里没有分支 ⇒ 用户发专栏链接**完全没反应**（不报错、不回复）。
        if (url.contains("bilibili.com/read/cv")){
            onArticleUrl(bot,event,url);
        }
    }
    private void onDynamicUrl(Bot bot, AnyMessageEvent event, String url) {
        String dynamicIdStr = getId(url);
        BilibiliDynamicResp.Data.Card  card;
        try {
            Dynamic dynamic = new Dynamic();
            card = dynamic.getDynamicDetail(dynamicIdStr);
        } catch (IOException e) {
            log.error(e.getMessage());
            return;
        }
        sendDynamicMsg(event,card,bot);
    }

    private String getId(String url) {
        String[] urlArr = url.split("/");
        int indexOf = urlArr[urlArr.length - 1].indexOf("?");
        if (indexOf<=0){
            indexOf =urlArr[urlArr.length-1].length();
        }
        return urlArr[urlArr.length-1].substring(0, indexOf);
    }

    private void onVideoUrl(Bot bot, AnyMessageEvent event, String url) {
        String[] urlArr = url.split("/");
        String videoIdStr = null;
        for (String urlSplit : urlArr) {
            if (urlSplit.startsWith("BV")||urlSplit.startsWith("av")||urlSplit.startsWith("AV")){
                int indexOf = urlSplit.indexOf("?");
                if (indexOf<=0){
                    indexOf =urlSplit.length();
                }
                videoIdStr = urlSplit.substring(0, indexOf);
                break;
            }
        }
        if (videoIdStr==null){
            return ;
        }
        // ★ av / BV 归一化：纯算法换算、**零出站**（VideoExtra#toBvid，底层是 base58）。
        //   原先 av 号走的是 BilibiliClient#getVideoInfo(aid) 那个重载，而 view/detail 只收 bvid ——
        //   就地换算一次，比"为换 bvid 再打一次 view"省掉一个请求（这正是风控敏感的东西）。
        String bvid = toBvid(videoIdStr);
        if (bvid == null) {
            log.warn("视频号无法识别成 BV 号，已跳过：{}", videoIdStr);
            return;
        }
        ViewDetail detail;
        try {
            // ★ P0-2：view → view/detail。仍然是 1 次请求，但同一个响应里**顺带**给了
            //   标签 / 相关推荐 / UP 主概览（库不为它们单独发请求）。
            //   getView() 返回的就是原来的 VideoInfo 类型，所以下游的拼装逻辑与输出结构不用改。
            detail = new VideoExtra().getViewDetail(bvid);
        } catch (IOException e) {
            // 与改前一致：解析失败只记日志、不回复（对方发的是个坏链接，不值得刷屏）
            log.error("获取视频详情失败：bvid={}，{}", bvid, e.getMessage());
            return;
        }
        if (detail == null || detail.getView() == null) {
            log.warn("视频详情为空，已跳过：bvid={}", bvid);
            return;
        }
        sendVideoMsg(event, detail.getView(), detail.getTags(), detail.getRelated(), bot);
    }

    /**
     * 把 BV / av / AV 号归一到 BV 号。<b>纯算法、零出站</b>。
     *
     * <p>⚠️ 这里刻意用 {@code VideoExtra.toBvid}，<b>不用</b> {@code BilibiliClient.getVideoBv} ——
     * 后者首次调用会真发一次请求（同名不同物，是个陷阱）。
     *
     * @param videoIdStr URL 里取出的视频号
     * @return BV 号；无法识别时返回 {@code null}
     */
    private static String toBvid(String videoIdStr) {
        if (videoIdStr.startsWith("BV")) {
            return videoIdStr;
        }
        try {
            long aid = Long.parseLong(videoIdStr.substring(2));
            return new VideoExtra().toBvid(aid);
        } catch (IllegalArgumentException e) {
            // toBvid 对不在 [1, 2^51) 内的 aid 抛 IllegalArgumentException；
            // av 号本身不是数字时 Long.parseLong 抛的 NumberFormatException 是它的子类，一并接住。
            return null;
        }
    }
    /**
     * 解析 B 站专栏（{@code bilibili.com/read/cvNNN}）。
     *
     * <p><b>它补的是一个"静默丢弃"</b>：改之前四个分支都不匹配这类链接，方法直接返回 ——
     * 用户看到的是"发了链接，机器人毫无反应"（连报错都没有），这是现存最明显的功能空洞。
     *
     * <p>用 {@code Content#getArticleInfo}，它是 Content 门面里<b>唯一免凭据</b>的方法
     * （实测匿名 {@code code=0}，23 个键与带凭据逐字相同）⇒ 专栏解析<b>不依赖 Cookie</b>，
     * 凭据失效期间它照常工作。
     */
    private void onArticleUrl(Bot bot, AnyMessageEvent event, String url) {
        // 复用现成的 getId：它取路径最后一段并剥掉查询串（`.../read/cv4538122?spm=x` → `cv4538122`）
        String idStr = getId(url);
        if (idStr == null || !idStr.startsWith("cv")) {
            log.warn("专栏链接形态不认识，已跳过：{}", url);
            return;
        }
        long cvId;
        try {
            cvId = Long.parseLong(idStr.substring(2));
        } catch (NumberFormatException e) {
            log.warn("专栏号不是数字，已跳过：{}", idStr);
            return;
        }
        ArticleInfo info;
        try {
            info = new Content().getArticleInfo(cvId);
        } catch (IOException e) {
            // 专栏不存在 / 被删 / 风控，都会走到这里；内层消息带着业务码，别抹掉
            log.error("获取专栏信息失败：cvId={}，{}", cvId, e.getMessage());
            return;
        }
        if (info == null) {
            log.warn("专栏信息为空，已跳过：cvId={}", cvId);
            return;
        }
        sendArticleMsg(event, cvId, info, bot);
    }

    /**
     * 发送专栏解析结果。
     *
     * <p>🔴 <b>统计数一律读 {@code getStats()}，绝不读顶层的 {@code getLike()} /
     * {@code getCoin()} / {@code getFavorite()}</b> —— 顶层那组表达的是"<b>当前凭据</b>对这篇文章
     * 做过什么"（我点没点赞、我收藏没有），<b>匿名恒为 0</b>；文章真正的汇总数在 {@code stats} 里。
     * 两组同名、含义相反，写错<b>不报错</b>，只会安静地显示"0 赞"。
     */
    private void sendArticleMsg(AnyMessageEvent event, long cvId, ArticleInfo info, Bot bot) {
        ArticleInfo.Stats stats = info.getStats();
        // 封面：专栏可能没配 banner，退而用正文第一张图；都没有就不发图
        String cover = info.getBanner_url();
        if ((cover == null || cover.isBlank()) && info.getImage_urls() != null && !info.getImage_urls().isEmpty()) {
            cover = info.getImage_urls().get(0);
        }
        if (cover != null && cover.startsWith("//")) {
            // B 站的图片字段常是协议相对地址，直接当 URL 用会失败
            cover = "https:" + cover;
        }

        MsgUtils builder = MsgUtils.builder();
        if (cover != null && !cover.isBlank()) {
            builder = builder.img(cover);
        }
        builder = builder.text(
                "专栏 cv" + cvId + "\n" +
                        "标题：" + info.getTitle() + "\n" +
                        "作者：" + info.getAuthor_name() + "（uid " + info.getMid() + "）\n" +
                        (stats == null
                                ? "（服务端未返回统计数据）\n"
                                : "阅读：" + NumFormat.count(stats.getView())
                                        + "，点赞：" + NumFormat.count(stats.getLike())
                                        + "，评论：" + NumFormat.count(stats.getReply()) + "\n"
                                        + "收藏：" + NumFormat.count(stats.getFavorite())
                                        + "，投币：" + NumFormat.count(stats.getCoin()) + "\n")
        );
        String msg = builder.text("https://www.bilibili.com/read/cv" + cvId).build();
        bot.sendMsg(event, msg, false);
    }

    private void onLiveUrl(Bot bot, AnyMessageEvent event, String url){
        String roomIdStr = getId(url);
        Long roomId = Long.parseLong(roomIdStr);
        LiveRoom liveRoom = new Live().getLiveRoom(roomId);
        if (liveRoom==null){
            return ;
        }
         sendLiveMsg(event,liveRoom,bot);
    }

    private void shortChainAnalysis(AnyMessageEvent event, ShortChain shortChain, Bot bot) {
        Integer type = shortChain.getShotChainInfo().getType();
        switch (type) {
            case 0:
                LiveRoom liveRoom = shortChain.getLiveRoom();
                sendLiveMsg(event,liveRoom,bot);
                break;
            case 1:
                VideoInfo info = shortChain.getVideoInfo();
                sendVideoMsg(event,info,bot);
                break;
            case 2:
                BilibiliDynamicResp.Data.Card  card = shortChain.getDynamicCard();
                sendDynamicMsg(event,card,bot);
        }
    }

    private void sendDynamicMsg(AnyMessageEvent event, BilibiliDynamicResp.Data.Card card, Bot bot) {
        Dynamic dynamic = new Dynamic();
        try {
            BufferedImage dynamicImg = dynamic.getDynamicImg(card.getDesc().getDynamic_id_str());
            if (Objects.isNull(dynamicImg)){
                return;
            }
            String base64Image = BiliBiliContant.imgToBase64(dynamicImg);
            String sendMsg = MsgUtils.builder().text(card.getDesc().getUser_profile().getInfo().getUname()+" 的动态：\n")
                    .img("base64://"+base64Image).text("https://www.bilibili.com/opus/"+card.getDesc().getDynamic_id_str())
                    .build();
            bot.sendMsg(event,sendMsg,false);
        }catch (Exception e){
            log.error("获取动态图片失败,动态id:{}",card.getDesc().getDynamic_id_str(),e);
        }
    }

    /**
     * 发送视频解析结果（<b>短链路径专用</b>）。
     *
     * <p>短链那边拿到的只有 {@code ShortChain#getVideoInfo()} 一个 {@link VideoInfo}
     * （给不出标签 / 相关推荐），所以走这个"只有基础信息"的重载。
     * ⚠️ <b>刻意保留这个签名</b>：短链分支因此一行都不用改（最小影响面）。
     */
    private void sendVideoMsg(AnyMessageEvent event, VideoInfo info, Bot bot) {
        sendVideoMsg(event, info, null, null, bot);
    }

    /**
     * 发送视频解析结果（含标签与相关推荐）。
     *
     * <p>{@code tags} / {@code related} 都来自 {@code VideoExtra#getViewDetail} 的<b>同一个响应</b>
     * —— 库<b>不</b>为它们单独发请求 ⇒ 多发这些内容是"免费"的，请求数与改前一样是 1 次。
     *
     * @param tags    标签，可为 null / 空
     * @param related 相关推荐，可为 null / 空
     */
    private void sendVideoMsg(AnyMessageEvent event, VideoInfo info, List<VideoTag> tags,
                              List<VideoBrief> related, Bot bot) {
        // ⚠️ 统计段单独拼：view/detail 与 view 两个端点的字段形状实测一致
        //   （getView() 与原来的 getVideoInfo 返回的就是同一个 VideoInfo 类），
        //   但"一个 null 让整条解析静默失败"的代价太大（用户什么都收不到），
        //   所以取不到就明说，而不是显示成 0 误导人。
        Stat stat = info.getStat();
        String statText = stat == null
                ? "（服务端未返回统计数据）"
                : "播放量：" + NumFormat.count(stat.getView())
                        + "，弹幕：" + stat.getDanmaku()
                        + "\n评论：" + stat.getReply()
                        + "，收藏：" + stat.getFavorite()
                        + "\n点赞：" + stat.getLike()
                        + "，投币：" + stat.getCoin()
                        + "\n分享：" + stat.getShare();

        MsgUtils builder = MsgUtils.builder().img(info.getPic()).text(
                "av" + info.getAid() + "\n" +
                        info.getBvid() + "\n" +
                        "标题：" + info.getTitle() + "\n" +
                        "简介：" + info.getDesc() + "\n" +
                        "上传时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(info.getPubdate() * 1000) + "\n" +
                        statText +
                        "\nup主：" + info.getOwner().getName() + "\n" + "up主uid：" + info.getOwner().getMid() + "\n" +
                        getStaff(info.getStaff())
        );
        // ★ P2-7：AI 摘要。**默认关**（开关见 LoadDSConfig#KEY_BILI_ANALYSIS_WITH_SUMMARY）。
        //   关着、或没配 Cookie、或这个视频本来就没有摘要 —— 三种情况下都返回空串，消息与改前逐字相同。
        //   放在这里（基础信息之后、标签之前）：摘要是"这个视频讲了啥"，与「简介」同属内容级信息；
        //   标签 / 相关推荐 / 热评属"周边"信息，跟在后面读起来更顺。基础信息那段拼接一个字没动。
        String summaryText = formatAiSummary(fetchAiSummary(info.getBvid(), info.getCid()));
        if (!summaryText.isEmpty()) {
            builder = builder.text("\n" + summaryText);
        }
        String tagText = formatTags(tags);
        if (!tagText.isEmpty()) {
            builder = builder.text("\n" + tagText);
        }
        String relatedText = formatRelated(related);
        if (!relatedText.isEmpty()) {
            builder = builder.text("\n" + relatedText);
        }
        // ★ P2-5：热评。**默认关**（开关见 LoadDSConfig#KEY_BILI_ANALYSIS_WITH_COMMENTS）。
        //   关着的时候 fetchHotComments 直接返回 null，**一个请求都不发** —— 所以上面那段
        //   "tags/related 是免费的"的结论在默认配置下依然成立。
        //   放在最后一段：基础信息 → 标签 → 相关推荐 → 热评，前两段的相对顺序一点没动（最小影响面）。
        String commentText = formatHotComments(fetchHotComments(info.getBvid()));
        if (!commentText.isEmpty()) {
            builder = builder.text("\n" + commentText);
        }
        String msg = builder.text("\nhttps://www.bilibili.com/video/" + info.getBvid()).build();
        bot.sendMsg(event, msg, false);
    }

    /**
     * 拉一次热评。<b>开关没开时一个请求都不发</b>（这里就是"默认不改变请求数"的落点）。
     *
     * <p><b>为什么不从 {@code getViewDetail} 的 {@code reply} 字段顺手拿</b>（那样才是真·0 请求）：
     * 真机实测（2026-10-02，三个不同 bvid 全部复现）该字段<b>恒回 1 条空壳</b> ——
     * {@code like=0}、{@code mid=0}、{@code member=null}、{@code content.message=null}；
     * 而库自己的日志会把数组长度数成"热评 1 条"。这是个<b>看起来有内容、其实是骨架</b>的陷阱，
     * 照它渲染只会得到三行空白。热评的真内容只能走评论接口。
     *
     * <p>用 {@code getRepliesByBvid} 而不是收 aid 的 {@code getReplies}：前者内部
     * <b>纯算法换算 aid</b>，省掉一次 {@code view} 请求（上游 B5 起的优化）。
     *
     * <p><b>失败一律吞掉</b>：热评是锦上添花，风控/网络/被删都只记一行日志，
     * 绝不能让"拿不到评论"变成"整条视频解析没了"。
     *
     * @param bvid BV 号
     * @return 热评列表；开关关闭 / 无 bvid / 失败时返回 {@code null}
     */
    private List<Comment> fetchHotComments(String bvid) {
        if (!loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_ANALYSIS_WITH_COMMENTS)) {
            return null;
        }
        if (bvid == null || bvid.isBlank()) {
            // 短链那边偶尔给不出 bvid；没有它就没法查评论，静默跳过（不额外发请求）
            return null;
        }
        try {
            // 门面类与模型类同名，这里用全限定名把"要的是门面"说清楚
            CommentPage page = new com.esdllm.bilibiliApi.bilibiliApi.Comment()
                    .getRepliesByBvid(bvid, 1, MAX_COMMENTS);
            return page == null ? null : page.getReplies();
        } catch (Exception e) {
            log.warn("获取热评失败，已跳过该段：bvid={}，{}", bvid, e.toString());
            return null;
        }
    }

    /**
     * 拼「热评」段（最多 {@value #MAX_COMMENTS} 条）；没有可展示的内容时返回空串。
     *
     * <p>⚠️ 评论正文与昵称都是<b>第三方可控文本</b>，一律过
     * {@link BiliBiliContant#escapeCq(String)} —— 否则别人在评论区留一句
     * {@code [CQ:at,qq=all]}，机器人就会替他 @全体成员。
     */
    private static String formatHotComments(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("热评：");
        int count = 0;
        for (Comment c : comments) {
            if (count >= MAX_COMMENTS) {
                break;
            }
            if (c == null || c.getContent() == null) {
                continue;
            }
            String message = c.getContent().getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            sb.append("\n").append(count + 1).append(". ")
                    .append(truncate(BiliBiliContant.escapeCq(message), MAX_COMMENT_CHARS));
            String uname = c.getMember() == null ? null : c.getMember().getUname();
            Integer like = c.getLike();
            sb.append("（赞 ").append(NumFormat.count(like == null ? null : like.longValue()));
            if (uname != null && !uname.isBlank()) {
                sb.append(" · ").append(BiliBiliContant.escapeCq(uname));
            }
            sb.append("）");
            count++;
        }
        return count == 0 ? "" : sb.toString();
    }

    /**
     * 压平并截断超长文本。
     *
     * <p>换行必须压成空格：评论正文里的换行会把"1. / 2. / 3."的编号版式拆散，
     * 看上去像机器人发了一堆乱行。
     */
    private static String truncate(String text, int max) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }

    /**
     * 拉一次 B 站 AI 摘要（{@code x/web-interface/view/conclusion/get}）。
     *
     * <p>🔴 <b>本端点要「WBI 签名 + 登录凭据」两样，缺一不可</b>（库作者 2×2 实测：
     * 匿名无签名 {@code -403}、匿名有签名 <b>{@code -101}</b>、有凭据无签名 {@code -403}、
     * 两样齐才 {@code code=0}）。签名由库的 {@code HttpPolicy} 那层自动算，我们管不了；
     * <b>登录得靠自己</b> ⇒ 所以这里第一件事就是查有没有 Cookie。
     *
     * <p>⚠️ <b>没配 Cookie 时一个请求都不发</b>：那种情况下打过去必然是 {@code -101}，
     * 是纯粹浪费一次请求，而 B 站的 412 是请求密度敏感型（这条纪律与
     * {@code CredentialGuard} 的"无 Cookie 零请求"一致）。
     *
     * <p>⚠️ <b>用 {@code getAiSummary(bvid, cid)} 而不是 {@code getAiSummary(bvid)}</b>：
     * 后者内部会先打一次 {@code x/web-interface/view} 去换 {@code cid} ⇒ <b>多一次请求</b>。
     * 而 {@code cid} 早在 {@code getViewDetail} 的响应里就有了（{@code VideoInfo#getCid()}）。
     * 所以开启后是 <b>+1 次请求</b>，不是 +2。
     *
     * <p>⚠️ <b>"有没有摘要"只认 {@code hasSummary()}</b>，不看 {@code code} 也不看 {@code status}
     * —— 库实测两者都是 0 而摘要正常返回。有些视频本身就没有 AI 摘要，
     * 那是<b>正常情况不是错误</b> ⇒ 静默跳过，不报错刷屏。
     *
     * <p>其余失败（未登录 {@code -101} / 风控 {@code -352} / 网络）一律吞掉只记日志：
     * 摘要是锦上添花，绝不能拖垮整条视频解析。
     *
     * @param bvid BV 号
     * @param cid  分 P 的 cid（来自 {@code VideoInfo}）
     * @return AI 摘要；开关关闭 / 无 Cookie / 参数不全 / 该视频无摘要 / 失败时返回 {@code null}
     */
    private AiSummary fetchAiSummary(String bvid, Long cid) {
        if (!loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_ANALYSIS_WITH_SUMMARY)) {
            return null;
        }
        if (!HttpPolicy.hasCookie()) {
            // 见方法注释：没登录必然是 -101，这个请求发了也没用
            return null;
        }
        if (bvid == null || bvid.isBlank() || cid == null || cid <= 0) {
            return null;
        }
        try {
            AiSummary summary = new VideoExtra().getAiSummary(bvid, cid);
            if (summary == null || !summary.hasSummary()) {
                // 该视频没有 AI 摘要 —— 正常情况，静默跳过（默认 debug，免得刷屏）
                log.debug("该视频暂无 AI 摘要：bvid={} cid={}", bvid, cid);
                return null;
            }
            return summary;
        } catch (Exception e) {
            log.warn("获取 AI 摘要失败，已跳过该段：bvid={} cid={}，{}", bvid, cid, e.toString());
            return null;
        }
    }

    /**
     * 拼「AI 摘要」段：<b>一句话总纲 + 前若干个分段大纲</b>；没有可展示的内容时返回空串。
     *
     * <p>版式（真实数据长相）：
     * <pre>
     * AI摘要：iPhone 18 Pro与Duo深度对比，Pro系列优化屏幕一致性……
     * 分段：
     * 1. 折叠屏体验与直板机对比分析（00:01）
     *    · 00:16 折叠屏耐久性不足，压展测试出现屏幕闪烁问题
     *    · 00:28 双顶配价格超2.6万，性价比争议显著
     * 2. iPhone 18 Pro/Duo多屏交互与影像升级（10:00）
     *    · 10:37 外屏内屏绑定同步信息，避免误操作导致情绪波动
     * </pre>
     *
     * <p>为什么不发 {@code subtitle}（字幕级分段）：那是逐句字幕，一个十几分钟的视频能有上百条，
     * 发出去等于刷屏；它的信息量与总纲/大纲重复度极高。
     *
     * <p>时间点用 {@link #formatDuration(int)} 转成 {@code mm:ss}：接口给的是<b>秒数</b>
     * （实测 {@code 637} 秒），直接发数字没人看得懂。
     *
     * <p>🔐 总纲与要点都是 <b>AI 根据视频内容生成的文本</b> ⇒ 仍然属于<b>第三方可控</b>
     * （投稿者能左右视频内容，也就能左右摘要里出现什么字），一律过
     * {@link BiliBiliContant#escapeCq(String)}。
     */
    private static String formatAiSummary(AiSummary summary) {
        if (summary == null || !summary.hasSummary()) {
            return "";
        }
        AiSummary.Result result = summary.getModel_result();
        StringBuilder sb = new StringBuilder("AI摘要：")
                .append(truncate(BiliBiliContant.escapeCq(result.getSummary()), MAX_SUMMARY_CHARS));

        List<AiSummary.Outline> outline = result.getOutline();
        if (outline != null && !outline.isEmpty()) {
            sb.append("\n分段：");
            int count = 0;
            for (AiSummary.Outline section : outline) {
                if (count >= MAX_SUMMARY_OUTLINE) {
                    break;
                }
                if (section == null || section.getTitle() == null || section.getTitle().isBlank()) {
                    continue;
                }
                sb.append("\n").append(count + 1).append(". ")
                        .append(BiliBiliContant.escapeCq(section.getTitle()));
                if (section.getTimestamp() != null) {
                    sb.append("（").append(formatDuration(section.getTimestamp())).append("）");
                }
                List<AiSummary.OutlinePart> parts = section.getPart_outline();
                if (parts != null) {
                    int shown = 0;
                    for (AiSummary.OutlinePart part : parts) {
                        if (shown >= MAX_SUMMARY_PARTS) {
                            break;
                        }
                        if (part == null || part.getContent() == null || part.getContent().isBlank()) {
                            continue;
                        }
                        sb.append("\n   · ");
                        if (part.getTimestamp() != null) {
                            sb.append(formatDuration(part.getTimestamp())).append(" ");
                        }
                        sb.append(truncate(BiliBiliContant.escapeCq(part.getContent()), MAX_SUMMARY_PART_CHARS));
                        shown++;
                    }
                }
                count++;
            }
        }
        return sb.toString();
    }

    /**
     * 秒数转 {@code mm:ss}（满 1 小时转 {@code h:mm:ss}）。
     *
     * <p>接口给的时间点是<b>秒</b>（实测 {@code 637}），原样发没人看得懂。
     * 负数按 {@code 00:00} 处理 —— 不抛异常，一个坏时间点不该让整段摘要消失。
     */
    private static String formatDuration(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }

    /** 拼「标签」段；没有可展示的标签时返回空串 */
    private static String formatTags(List<VideoTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("标签：");
        int count = 0;
        for (VideoTag tag : tags) {
            if (tag == null || tag.getTag_name() == null || tag.getTag_name().isBlank()) {
                continue;
            }
            if (count >= MAX_TAGS) {
                break;
            }
            if (count > 0) {
                sb.append(" / ");
            }
            sb.append(tag.getTag_name());
            count++;
        }
        return count == 0 ? "" : sb.toString();
    }

    /** 拼「相关推荐」段（Top {@value #MAX_RELATED}）；没有可展示的条目时返回空串 */
    private static String formatRelated(List<VideoBrief> related) {
        if (related == null || related.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("相关推荐：");
        int count = 0;
        for (VideoBrief brief : related) {
            if (brief == null || brief.getBvid() == null) {
                continue;
            }
            if (count >= MAX_RELATED) {
                break;
            }
            sb.append("\n").append(count + 1).append(". ").append(brief.getTitle());
            String upName = brief.getOwner() == null ? null : brief.getOwner().getName();
            if (upName != null && !upName.isBlank()) {
                sb.append("（").append(upName);
                Long relatedView = brief.getStat() == null ? null : brief.getStat().getView();
                if (relatedView != null) {
                    sb.append(" · ").append(NumFormat.count(relatedView));
                }
                sb.append("）");
            }
            count++;
        }
        return count == 0 ? "" : sb.toString();
    }

    private void sendLiveMsg(AnyMessageEvent event, LiveRoom liveRoom, Bot bot) {
        CardInfo cardInfo = new CardInfo();
        // ★ P2-6：主播信息（粉丝数 / 粉丝牌）。**默认关**（见 LoadDSConfig#KEY_BILI_LIVE_WITH_MASTER_INFO）。
        //   关着的时候 fetchMasterInfo 直接返回 null，一个请求都不发。
        String masterText = formatMasterInfo(fetchMasterInfo(liveRoom.getUid()));
        String msg = MsgUtils.builder()
                .text(
                        "房间号："+liveRoom.getRoom_id()+"\n"+
                                "标题："+liveRoom.getTitle()+"\n"+
                                "up主："+cardInfo.getUserName(liveRoom.getUid())+"\n"+
                                "up主uid："+liveRoom.getUid()+"\n"+
                                masterText+
                                "观看人数："+liveRoom.getOnline()+"\n"+
                                "直播分区："+liveRoom.getArea_name()+"\n"+
                                "开播状态: "+(liveRoom.getLive_status()==1?"正在直播":liveRoom.getLive_status()==0?"未开播":"轮播中")+"\n"+
                                (liveRoom.getLive_status().equals(1)?("开播时间："+liveRoom.getLive_time()+"\n"):"")+
                                "https://live.bilibili.com/"+liveRoom.getRoom_id()+"\n\n"
                ).img(liveRoom.getUser_cover()).build();
        bot.sendMsg(event,msg,false);
    }

    /**
     * 拉一次主播信息。<b>开关没开时一个请求都不发。</b>
     *
     * <p>⚠️ {@code getMasterInfo} 的入参是<b>主播 uid，不是房间号</b>。这里刻意直接用
     * {@code liveRoom.getUid()}，而<b>不</b>调 {@code Live#getUid(roomId)} —— 后者是一次额外请求，
     * 而 uid 早就躺在 {@code getLiveRoom(roomId)} 的响应里了（{@code sendLiveMsg} 上面几行
     * 本来就在用它拼"up主"）。所以开启后是 <b>+1 次请求</b>，不是 +2。
     *
     * <p><b>失败一律吞掉</b>：拿不到粉丝数不该影响开播信息本身。
     *
     * @param uid 主播 uid（来自 {@code LiveRoom}）
     * @return 主播信息；开关关闭 / uid 非法 / 失败时返回 {@code null}
     */
    private MasterInfo fetchMasterInfo(Long uid) {
        if (!loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_LIVE_WITH_MASTER_INFO)) {
            return null;
        }
        if (uid == null || uid <= 0) {
            return null;
        }
        try {
            return new LiveExtra().getMasterInfo(uid);
        } catch (Exception e) {
            log.warn("获取主播信息失败，已跳过该段：uid={}，{}", uid, e.toString());
            return null;
        }
    }

    /**
     * 拼「主播信息」段（粉丝数 / 粉丝牌名）；没有可展示的内容时返回空串。
     *
     * <p>返回的串自带结尾换行（或者为空串），这样可以直接插进消息中间不用再判。
     * 粉丝牌名是 UP 主自定义文本 ⇒ 过一遍 {@link BiliBiliContant#escapeCq(String)}。
     */
    private static String formatMasterInfo(MasterInfo info) {
        if (info == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (info.getFollower_num() != null) {
            sb.append("主播粉丝数：").append(NumFormat.count(info.getFollower_num())).append("\n");
        }
        if (info.getMedal_name() != null && !info.getMedal_name().isBlank()) {
            sb.append("粉丝牌：").append(BiliBiliContant.escapeCq(info.getMedal_name())).append("\n");
        }
        return sb.toString();
    }
    public String getStaff(List<Staff> staff){
        if (staff==null||staff.isEmpty()){
            return "";
        }
        final String prefix = "合作up主：";
        StringBuilder str = new StringBuilder(prefix);
        for (Staff s : staff){
            if (s==null||s.getName()==null){
                continue;
            }
            str.append(s.getName()).append(",");
        }
        if (str.length()==prefix.length()){
            return "";
        }
        return str.substring(0,str.length()-1);
    }
}
