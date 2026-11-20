package com.esdllm.botPlugins;

import com.esdllm.common.BotAdminChecker;
import com.esdllm.common.CmdPrefix;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指令帮助：<b>把机器人有哪些命令、分别要什么权限，说给用户听</b>。
 *
 * <p>支持两种粒度：
 * <pre>
 * help / 帮助 / 菜单 / 指令 / 命令       ← 一览（按当前身份裁剪）
 * help 录播 / 帮助 推送 / help 配置      ← 某一类的详细说明（含示例与坑）
 * </pre>
 *
 * <h2>为什么一览要"按人裁剪"</h2>
 * <p>同一份命令表里有四种权限档（公开 / 群管理员 / 白名单 / 所有者）。
 * 若把全部命令都列出来，会同时产生两种伤害：
 * <ul>
 *   <li><b>对用户是噪音</b>：普通群友看到一堆按了没反应的命令，只会以为"机器人坏了"；</li>
 *   <li><b>对系统是暴露</b>：「录播管理码」「设置代理」这类命令名本身就是信息，
 *       没必要广而告之（虽然 handler 上都带权限闸，命令名不等于漏洞，但少说一句不亏）。</li>
 * </ul>
 * 所以一览只列<b>当前这个人真能用的</b>——判定用同一份 {@link BotAdminChecker}，
 * 与各 handler 自己那道门<b>完全同源</b>，不会出现"帮助里说能用、发出去被拒"的错位。
 *
 * <h2>为什么判定只做一次</h2>
 * <p>三档判定各是一次 SQL {@code count}。一屏几十条命令，如果每行都判一次，
 * 一条「帮助」就是几十次查询 —— 在群聊里这是纯浪费（还可能把出口熔断的配额挤掉）。
 * 所以先把发送者算成<b>一个档位数字</b>，随后每行只做整数比较（见 {@link #rank}）。
 *
 * <h2>命令为什么长这样</h2>
 * <p>触发词刻意只收「help / 帮助 / 菜单 / 指令 / 命令」这几个<b>整词</b>，
 * 且必须有行尾锚点：{@code 帮助} 后面跟了别的字（如「帮助我看看这个」）<b>不触发</b>。
 * 没把 {@code ?} / {@code ？} 收进来，是因为群里发一个问号太常见，
 * 那种误触发比"少一种写法"讨厌得多。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Shiro
@Component
public class HelpPlugins {

    /** 与其它插件同一口径的命令头（行首空白 / CQ 码 / 手打的 @机器人） */
    private static final String CMD_PREFIX = CmdPrefix.PREFIX;

    /**
     * 触发词。<b>斜杠可选</b>（{@code /help} 与 {@code help} 都吃），
     * 全角斜杠 {@code ／} 也认 —— 中文输入法下很容易打成全角。
     */
    private static final String WORD = "(?:/|／)?(?:help|帮助|菜单|指令|命令)";

    /** 一览：`help`（也接受「帮助」「菜单」「指令」「命令」） */
    private static final String CMD_OVERVIEW = "^" + CMD_PREFIX + WORD + "\\s*$";

    /**
     * 单类详情：`help 录播`。
     *
     * <p>与 {@link #CMD_OVERVIEW} 靠"有没有参数"互斥：这条要求后面至少一个非空白字符，
     * 一览要求直接到行尾。两个都是 {@code matches()} 整串匹配，所以不会同时命中。
     */
    private static final String CMD_DETAIL = "^" + CMD_PREFIX + WORD + "\\s+\\S.*$";

    /** 单条回复的长度保险（QQ 单条消息装不下几万字，异常输入也不该把回复拼爆） */
    private static final int MAX_ARG_LENGTH = 64;

    @Resource
    private BotAdminChecker botAdminChecker;

    // ================================================================== 命令

    /** 指令一览（按当前身份裁剪）。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_OVERVIEW, at = AtEnum.BOTH)
    public void overview(Bot bot, AnyMessageEvent event) {
        int rank = rank(event);
        try {
            send(bot, event, renderOverview(rank));
        } catch (Exception e) {
            log.error("生成指令一览失败", e);
            safeSend(bot, event, "生成帮助失败：" + e.getMessage());
        }
    }

    /** 某一类的详细帮助：`help 录播`。 */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_DETAIL, at = AtEnum.BOTH)
    public void detail(Bot bot, AnyMessageEvent event) {
        int rank = rank(event);
        String keyword = normalizeKeyword(event.getMessage());
        try {
            send(bot, event, detailText(keyword, rank));
        } catch (Exception e) {
            log.error("生成「{}」的帮助失败", keyword, e);
            safeSend(bot, event, "生成帮助失败：" + e.getMessage());
        }
    }

    // ================================================================== 权限

    /**
     * 权限档，<b>按顺序递增</b>（用 {@link #ordinal()} 与 {@link #rank} 比大小）。
     *
     * <p>顺序不能随便调：{@code ordinal()} 就是"这个档有多高"，
     * 排序错了会让普通群友看到所有者命令。
     */
    private enum Level {
        /** 所有人可用（含私聊） */
        PUBLIC,
        /** 本群管理员（群主 / 群管理 / 白名单）；私聊里等价于"要所有者" */
        GROUP,
        /** admin 白名单 */
        BOT_ADMIN,
        /** 机器人所有者 */
        OWNER
    }

    /**
     * 把发送者折算成一个档位数字：0 = 普通群友，1 = 本群管理员，2 = 白名单，3 = 所有者。
     *
     * <p><b>fail-closed</b>：任何一步查库抛异常都按"更低一档"处理，绝不在判定失败时抬高权限。
     *
     * <p>私聊里 {@link BotAdminChecker#isAdmin} 会一律放行（那是既有约定），
     * 但帮助面板不能用它 —— 否则任何人私聊机器人就能看到群管理命令。
     * 所以这里的"群管理员"必须带 {@code groupId != null} 这个前提，
     * 与 {@code LiveRecordPlugins#check} 的 {@code Level.GROUP} 同一口径。
     */
    private int rank(AnyMessageEvent event) {
        try {
            if (botAdminChecker.isBotOwner(event)) {
                return Level.OWNER.ordinal();
            }
        } catch (Exception e) {
            log.warn("帮助：判定所有者失败，继续降档：{}", e.toString());
        }
        try {
            if (botAdminChecker.isBotAdmin(event)) {
                return Level.BOT_ADMIN.ordinal();
            }
        } catch (Exception e) {
            log.warn("帮助：判定白名单失败，继续降档：{}", e.toString());
        }
        try {
            if (event.getGroupId() != null && botAdminChecker.isAdmin(event)) {
                return Level.GROUP.ordinal();
            }
        } catch (Exception e) {
            log.warn("帮助：判定群管理员失败，按普通成员处理：{}", e.toString());
        }
        return Level.PUBLIC.ordinal();
    }

    private static boolean visible(Level level, int rank) {
        return level.ordinal() <= rank;
    }

    private static String identity(int rank) {
        return switch (rank) {
            case 3 -> "机器人所有者";
            case 2 -> "白名单管理员";
            case 1 -> "本群管理员";
            default -> "群成员";
        };
    }

    // ================================================================== 一览

    /** 一行命令：命令写法 + 一句人话 + 最低权限档 */
    private record Line(String cmd, String desc, Level level) {
    }

    /** 一组命令 */
    private record Section(String title, List<Line> lines) {
    }

    /**
     * 命令总表。<b>这里是唯一一份"机器人会什么"的清单</b> ——
     * 各插件的类注释虽然也有，但那是写给改代码的人看的；这一份是发给用户看的。
     *
     * <p>⚠️ 加新命令时<b>顺手在这儿加一行</b>，否则用户永远不知道它存在
     * （项目里没有自动扫描 {@code @MessageHandlerFilter} 的机制，也不值得为此引反射）。
     */
    private static List<Section> sections() {
        return List.of(
                new Section("B 站解析与搜索", List.of(
                        new Line("直接发链接 / BV 号", "视频、直播、动态、b23.tv 短链都会自动解析", Level.PUBLIC),
                        new Line("搜视频 关键词", "搜 B 站视频，回前 5 条", Level.PUBLIC),
                        new Line("热搜", "B 站热搜榜", Level.PUBLIC),
                        new Line("今日热门", "B 站当前热门视频", Level.PUBLIC))),

                new Section("签到与互动", List.of(
                        new Line("签到", "每日签到，攒好感度", Level.PUBLIC),
                        new Line("查询", "看自己的好感度与等级", Level.PUBLIC),
                        new Line("今日运势", "抽一句今日运势", Level.PUBLIC),
                        new Line("今日老婆", "抽本群一位群友当今天的「老婆」", Level.PUBLIC),
                        new Line("离婚 / 撤回离婚", "换人（有冷静期，见 help 老婆）", Level.PUBLIC))),

                new Section("直播推送订阅", List.of(
                        new Line("添加订阅 房间号", "主播开播 / 发动态时推送到本群", Level.GROUP),
                        new Line("取消订阅 [房间号]", "只订了一个房间时可以不写房间号", Level.GROUP),
                        new Line("开播@全体 [房间号]", "开播时 @全体成员", Level.GROUP),
                        new Line("取消开播@全体 [房间号]", "取消 @全体", Level.GROUP),
                        new Line("开播@我 [房间号]", "开播时只 @ 你自己（谁都能设）", Level.PUBLIC),
                        new Line("取消开播@我 [房间号]", "取消 @ 自己", Level.PUBLIC))),

                new Section("录播（直播录制）", List.of(
                        new Line("录播订阅 房间号", "这个房间开播就自动录（录到本群名下）", Level.GROUP),
                        new Line("录播订阅列表", "看本群盯了哪些房间", Level.GROUP),
                        new Line("录播取消订阅 房间号", "不再自动录（已录的文件保留）", Level.GROUP),
                        new Line("录播开关 房间号 开|关", "暂停 / 恢复某个订阅", Level.GROUP),
                        new Line("录播列表 [页码]", "列已录好的场次（编号 = 取用时的编号）", Level.GROUP),
                        new Line("录播下载 编号", "转 mp4 后发到本群 / 私聊", Level.GROUP),
                        new Line("录播保留 编号", "打保留标记：不压缩、不淘汰", Level.GROUP),
                        new Line("录播取消保留 编号", "取消保留标记", Level.GROUP),
                        new Line("删除录播 编号", "删这一场（连文件一起）", Level.GROUP),
                        new Line("录播网页", "拿本群的回放网页链接（可转给群成员）", Level.BOT_ADMIN),
                        new Line("录播网页重置", "旧链接立刻失效", Level.BOT_ADMIN),
                        new Line("录播管理码", "网页上改保留 / 删录播用的码（私聊回你）", Level.BOT_ADMIN),
                        new Line("录播状态", "占用、水位、正在录几路", Level.BOT_ADMIN),
                        new Line("录播地址", "排障：NapCat 该用哪个地址取文件", Level.BOT_ADMIN),
                        new Line("录播 开|关", "录播总闸（决定要不要自动录）", Level.OWNER),
                        new Line("录播配置 [项] [值]", "看 / 改录播参数（水位、并发、目录…）", Level.OWNER),
                        new Line("录播整理", "立刻跑一次容量整理（压缩 / 淘汰）", Level.OWNER),
                        new Line("录播订阅列表 全部", "跨群总览：哪个群在盯哪些房间", Level.OWNER),
                        new Line("录播取消订阅 <群号> <房间号>", "跨群停某个群的订阅（群号 0 = 全局）", Level.OWNER),
                        new Line("录播管理码重置", "让所有网页管理权一起失效", Level.OWNER))),

                new Section("机器人配置（全局，仅所有者）", List.of(
                        new Line("开关", "功能开关一览；改：开关 热评 开", Level.OWNER),
                        new Line("设置cookie / cookie状态 / 清除cookie", "B 站凭据", Level.OWNER),
                        new Line("设置代理 / 代理状态 / 清除代理", "B 站出口", Level.OWNER),
                        new Line("登录 / 登录状态", "扫码登录（二维码 = 一次授权转移）", Level.OWNER),
                        new Line("反刷屏 / 反刷屏 开|关 / 反刷屏 动作 / 反刷屏 设置", "群消息防刷屏", Level.OWNER))),

                new Section("帮助", List.of(
                        new Line("help / 帮助 / 菜单", "这份清单", Level.PUBLIC),
                        new Line("help 录播 / help 推送 / help 老婆 / help 签到", "某一类的详细说明", Level.PUBLIC))));
    }

    private static String renderOverview(int rank) {
        StringBuilder sb = new StringBuilder("🤖 指令一览（当前身份：")
                .append(identity(rank))
                .append("）\n只列出你能用的命令。");
        int shown = 0;
        for (Section section : sections()) {
            StringBuilder body = new StringBuilder();
            for (Line line : section.lines()) {
                if (!visible(line.level(), rank)) {
                    continue;
                }
                body.append("· ").append(line.cmd());
                if (!line.desc().isBlank()) {
                    body.append("　—　").append(line.desc());
                }
                body.append("\n");
                shown++;
            }
            if (body.isEmpty()) {
                continue;                   // 这一组整组都超出你的权限 ⇒ 连标题都不出现
            }
            sb.append("\n【").append(section.title()).append("】\n").append(body);
        }
        if (shown == 0) {
            // 理论上走不到（公开命令对所有人生效），留作配置异常时的兜底，别回一条空白
            sb.append("\n（没有可显示的命令 —— 公开命令表为空，这是配置问题，请让所有者看一下）");
            return sb.toString();
        }
        if (rank < Level.GROUP.ordinal()) {
            // 群成员连"有这个功能"都看不到（整组被裁掉了），但"想用该找谁"必须说一句，
            // 否则他只会以为机器人不会录播
            sb.append("\n⚠️ 直播推送订阅、录播（自动录制直播）都归群管理员操作 —— 需要就找本群管理。");
        }
        // 「help 配置」只对所有者有内容（其他人点进去只能是拒绝说明，列出来纯属浪费一次点击）
        sb.append("\n看详情：help 录播　｜　help 推送　｜　help 老婆　｜　help 签到");
        if (rank >= Level.OWNER.ordinal()) {
            sb.append("　｜　help 配置");
        }
        sb.append("\n⚠️ 命令前面可以不带 @；带了就得 @ 机器人自己（@ 别人不算）。");
        return sb.toString();
    }

    // ================================================================== 详情

    /** 取 `help xxx` 里的关键词：剥 CQ 码与手打 @，再剥触发词，最后归一化。 */
    private static String normalizeKeyword(String message) {
        if (message == null || message.length() > MAX_ARG_LENGTH * 8) {
            return "";
        }
        String body = CmdPrefix.stripMention(message.replaceAll("\\[CQ:[^]]*]", " ").replace('\u3000', ' ')).strip();
        // 触发词有几种形态（help / 帮助 / 菜单 / 指令 / 命令，斜杠可选），逐个试
        for (String word : new String[]{"help", "帮助", "菜单", "指令", "命令", "/", "／"}) {
            if (body.toLowerCase().startsWith(word)) {
                body = body.substring(word.length()).strip();
                break;
            }
        }
        // 上面只剥一次，`/help 录播` 会先命中 "/"，于是还剩 "help 录播"——再剥一遍
        for (String word : new String[]{"help", "帮助", "菜单", "指令", "命令"}) {
            if (body.toLowerCase().startsWith(word)) {
                body = body.substring(word.length()).strip();
                break;
            }
        }
        // 关键词只取第一段（`help 录播 管理码` 这种也认，但只看第一个词）
        String first = body.split("\\s+")[0];
        return first.length() > 16 ? first.substring(0, 16) : first;
    }

    private String detailText(String keyword, int rank) {
        String key = keyword.toLowerCase();
        return switch (key) {
            case "录播", "录制", "录像", "record" -> recordDetail(rank);
            case "推送", "订阅", "直播", "开播", "push" -> pushDetail(rank);
            case "签到", "好感度", "sign" -> """
                    【签到】
                    签到　　　每天一次，好感度 +（按你的等级浮动）
                    查询　　　看自己的好感度、等级、距离下一级
                    ⚠️ 好感度按「群 + 你」分别记 —— 换个群发「查询」看到的是另一个数。""";
            case "老婆", "离婚", "wife" -> """
                    【今日老婆】
                    今日老婆　抽本群一位群友当今天的「老婆」（带对方头像）
                    离婚　　　换人。⚠️ 不是立刻生效：先进冷静期，到点再发一次「离婚」才正式离
                    撤回离婚　撤销申请（不退还当天申请次数）
                    · 默认每人每天 1 次，当天第一次抽到谁就锁定谁
                    · 冷静期逐次翻倍（默认 30 → 60 → 120… 封顶 24 小时）
                    · 离婚成功后当天次数归零，可以重新抽""";
            case "解析", "搜索", "链接", "search", "视频" -> """
                    【解析与搜索】
                    · 直接发链接：bilibili.com 的视频/直播/动态、b23.tv 短链，或直接发 BV 号
                    · 搜视频 关键词：搜视频，回前 5 条（也接受「搜索视频」「视频搜索」）
                    · 热搜：B 站热搜榜
                    · 今日热门：热门视频
                    解析是否附带热评 / AI 摘要 / 主播信息，由所有者用「开关」控制。""";
            case "配置", "管理", "开关", "cookie", "反刷屏", "代理", "登录", "admin", "owner" ->
                    configDetail(rank);
            default -> unknown(keyword, rank);
        };
    }

    private static String recordDetail(int rank) {
        StringBuilder sb = new StringBuilder("""
                【录播】主播开播时自动把直播录下来，录完能在群里取用、也能做成网页给群成员看。

                ① 先订阅（群管理员）
                　录播订阅 1024　　　　这个房间开播就录，录到本群名下
                　录播订阅列表　　　　看本群盯了哪些房间
                　录播开关 1024 关　　暂停 / 恢复（「开」恢复）
                　录播取消订阅 1024　　不再自动录；已录好的文件都保留

                ② 取用（群管理员）
                　录播列表 [页码]　　　列本群可见的场次，前面的 #编号 就是下面用的编号
                　录播下载 12　　　　　转成 mp4 后发到本群 / 私聊（大文件要等）
                　录播保留 12　　　　　打标记：不参与压缩、不会被淘汰
                　录播取消保留 12
                　删除录播 12　　　　　连文件一起删，不可恢复""");
        if (rank < Level.BOT_ADMIN.ordinal()) {
            // 网页那几条是白名单档，对群成员只有"找谁要"这一个有用信息
            sb.append("\n\n想看网页回放：找群管理员或机器人所有者要本群的回放链接。");
        }
        if (rank >= Level.BOT_ADMIN.ordinal()) {
            // ③④ 合并成一段：它们都是"白名单及以上"，中间再分一层只会让人数编号
            sb.append("\n\n③ 网页与运维（白名单）\n")
                    .append("　录播网页　　　　　　拿本群的回放链接，转给群成员即可（链接本身就是密码）\n")
                    .append("　　（正在录的场次在网页上能点「▶ 实时」看当前画面，比直播慢约 10 秒）\n")
                    .append("　录播网页重置　　　　链接泄了就重置，旧链接立刻失效\n")
                    .append("　录播管理码　　　　　网页上改保留 / 删录播用（会私聊发你，别发群里）\n")
                    .append("　录播状态　　　　　　占用、水位、正在录几路\n")
                    .append("　录播地址　　　　　　排障：NapCat 该用哪个地址来取文件");
        }
        if (rank >= Level.OWNER.ordinal()) {
            sb.append("\n\n④ 跨群与总闸（仅所有者）\n")
                    .append("　录播订阅列表 全部　　跨群总览：哪个群在盯哪些房间（含全局订阅/暂停/在录）\n")
                    .append("　录播取消订阅 101 1024　只停「群 101 对房间 1024」那一条；群号 0 = 全局订阅\n")
                    .append("　录播 开 / 录播 关　　总闸。关着的时候一个 B 站请求都不发（已在录的会录完）\n")
                    .append("　录播配置　　　　　　看全部参数（检测间隔、水位、并发、目录、交付方式…）\n")
                    .append("　录播配置 硬水位 20480　改一项，即时生效\n")
                    .append("　录播整理　　　　　　立刻跑一次容量整理（压缩 / 淘汰）\n")
                    .append("　录播管理码重置　　　让所有网页管理权一起失效\n")
                    .append("⚠️ 水位差就是留给压缩的缓冲：压缩水位与硬水位贴太近，会出现「还没压完就到删除线」。\n")
                    .append("⚠️ 容量是全机器一本账、不按群配额：一个群订得多，会挤占所有群的空间。");
        }
        sb.append("\n\n⚠️ 订阅是【按群】记的：群里只能看到本群订阅的房间录出来的场次。");
        return sb.toString();
    }

    private static String pushDetail(int rank) {
        StringBuilder sb = new StringBuilder("""
                【直播推送订阅】主播开播（以及发动态）时把消息推到这个群。

                添加订阅 1024　　　　　加订阅
                取消订阅 [房间号]　　　房间号可省（本群只订了一个时）
                开播@全体 [房间号]　　开播时 @全体成员（⚠️ 需要机器人是群管理员，否则只发不 @）
                取消开播@全体 [房间号]
                开播@我 [房间号]　　　开播时只 @ 你自己（谁都能设，不影响别人）
                取消开播@我 [房间号]

                @ 的优先级：@全体 > @列表首项 > 不 @。""");
        if (rank < Level.GROUP.ordinal()) {
            sb.append("\n⚠️ 加 / 取消订阅要本群管理员；「开播@我」你自己就能设。");
        }
        return sb.toString();
    }

    private static String configDetail(int rank) {
        if (rank < Level.OWNER.ordinal()) {
            return """
                    【机器人配置】
                    这一组的命令（开关 / 设置cookie / 设置代理 / 登录 / 反刷屏）改的都是
                    **整个机器人共用**的东西（凭据、出口、所有群的开关），所以只对机器人所有者开放。

                    你现在看到的身份不是所有者。需要改配置请找机器人所有者。""";
        }
        return """
                【机器人配置】（都是全局的，改完即时生效）

                开关　　　　　　　　　　全部功能开关一览；改：开关 热评 开 / 开关 摘要 关
                　　　　　　　　　　　　单看一项：开关 热评
                设置cookie SESSDATA=…　增量合并，粘几个键改几个键
                cookie状态 / 清除cookie
                设置代理 http://…　　　B 站出口（改完即时生效）
                代理状态 / 清除代理
                登录　　　　　　　　　　扫码登录；⚠️ 二维码是一次授权转移，谁扫到机器人就以谁的账号出站
                登录状态　　　　　　　　当前凭据状态（也接受「凭据状态」）

                反刷屏　　　　　　　　　状态面板
                反刷屏 开 / 反刷屏 关
                反刷屏 动作 alert|recall|ban
                反刷屏 设置 窗口 60　　（还有 去重 / 冷却 / 监控群 / 单人阈值 / 全群阈值）

                录播的配置在「录播配置」，不在「开关」里（只有网页开关、网页基址在那边）。""";
    }

    private static String unknown(String keyword, int rank) {
        StringBuilder sb = new StringBuilder("没有找到「")
                .append(keyword == null || keyword.isBlank() ? "（空）" : keyword)
                .append("」相关的帮助。\n可以试：help 录播　｜　help 推送　｜　help 签到　｜　help 老婆　｜　help 解析");
        if (rank >= Level.OWNER.ordinal()) {
            sb.append("　｜　help 配置");
        }
        sb.append("\n直接发「help」看全部指令。");
        return sb.toString();
    }

    // ================================================================== 工具

    private static void send(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }

    private static void safeSend(Bot bot, AnyMessageEvent event, String message) {
        try {
            send(bot, event, message);
        } catch (Exception e) {
            log.debug("帮助命令回消息失败，已忽略：{}", e.toString());
        }
    }
}
