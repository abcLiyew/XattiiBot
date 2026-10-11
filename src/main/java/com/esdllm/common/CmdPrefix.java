package com.esdllm.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 所有「命令类」handler 共用的<b>命令头前缀</b>与「这句话是不是在跟机器人说」的运行时判定。
 *
 * <h2>一、认三种写法</h2>
 * <pre>
 * 搜视频 影视飓风                        ← ① 不加 @（最稳）
 * [CQ:at,qq=213481590] 搜视频 影视飓风   ← ② 用 QQ 的 @ 选人（真 @）
 * ＠Hana 搜视频 影视飓风                 ← ③ 手打/粘贴的 @机器人（只是普通文本）
 * </pre>
 *
 * <p><b>为什么必须兜第 ③ 种</b>：shiro 在拿 {@code cmd} 去匹配之前，会把<b>真</b> @ 机器人
 * 的那个 CQ 段整段剥掉再 trim（{@code CommonUtils#msgExtract}，{@code at ∈ {NEED, BOTH}} 时），
 * 所以第 ② 种<b>本来就命中</b>；但用户手打文本 {@code @Hana}（不走 @ 选人，或从别处复制粘贴）时
 * 那段只是<b>普通文字</b>，剥不掉 —— 于是 {@code ^} 锚点第 0 位是个 {@code @}，整串不匹配，
 * 命令<b>静默无响应</b>，且因为 handler 压根没进，日志里连失败都看不到。
 * （2026-10-11 线上：群里 同志(2422168208) 两条 {@code @Hana 搜视频 …} 全没反应，
 * 而同群用真 @ 的两次都成功了。）
 *
 * <h2>二、@ 只认机器人自己 —— 但「机器人叫什么」绝不写死在代码里</h2>
 * {@code @张三 搜视频 xxx}（@ 别人时顺口带上命令词）不该触发命令，否则群里互相 @ 聊天
 * 就会被机器人插话。可是「机器人自己叫什么」是<b>运行时数据</b>：换号、改昵称、改群名片
 * 都会变，<b>写进常量/白名单就等于换个号就作废</b>。
 *
 * <p>所以这里分成两半：
 * <ul>
 *   <li>{@link #PREFIX}（<b>编译期常量</b>，因为注解值必须是常量）只管<b>语法</b>：
 *       命令词前面可以有 行首空白 / CQ 码 / <b>任意</b> {@code @某人}。这一层<b>不认识谁是谁</b>，
 *       所以它<b>永远不需要跟着账号改</b>。</li>
 *   <li>「这个 {@code @某人} 是不是机器人」由 {@link #isAddressedToSelf(Long, Long, String)}
 *       在<b>运行时</b>判：称呼来自
 *       <ul>
 *         <li>{@link SelfNameRegistry}：启动即问 OneBot 要 {@code get_login_info().nickname}、
 *             逐群 {@code get_group_member_info(self).card}、以及自身 QQ 号；</li>
 *         <li>配置项 {@code botNameAliases}（与账号无关的通用叫法，如「机器人」「bot」），
 *             默认<b>空</b>，要就自己加 —— 代码里一个具体名字都不出现。</li>
 *       </ul>
 *   </li>
 * </ul>
 * 两半由 {@code CmdAddressedAspect} 串起来：凡带 {@code cmd} 的 handler，进方法前先过这道闸。
 * 之所以放在 handler <b>入口</b>而不是把名字塞进正则：正则读不了运行时值；
 * 而放在入口也保证了「只有真命令才付这个代价」—— 链接解析、入站反刷屏那些不吃 {@code cmd}
 * 的路径完全不受影响。
 *
 * <p>🔁 想看看 OneBot 现在认为机器人叫什么：跑 {@code python temp/_onebot_ws.py}。
 *
 * <p>⚠️ <b>必须带 {@code (?isU)} 里那个大写 {@code U}</b>：Java 的 {@code \s} 默认
 * <b>不认全角空格 U+3000</b>（QQ 里 @ 完常常就是一个），要让它走 Unicode 口径必须是
 * <b>大写 {@code U}</b>（{@code UNICODE_CHARACTER_CLASS}）——
 * <b>小写 {@code u} 只管大小写折叠，不管 {@code \s}</b>
 * （实测：{@code (?isu)\s} 不匹配 U+3000，{@code (?isU)\s} 才匹配）。
 * 反过来，字符类里的 {@code \s} 在 {@code U} 口径下同样只吃 Unicode 空白，
 * 所以 {@code [^\s@＠]} 不会像小写 {@code u} 那样把全角空格当普通字符吞掉。
 *
 * <p>⚠️ shiro 判定用的是 {@code Matcher#matches()}（<b>整串匹配</b>，不是 {@code find()}），
 * 所以本前缀只管<b>开头</b>，收尾交给各命令自己的 {@code .*} / {@code \s*$}。
 *
 * <p>用法：各插件的 {@code cmd} 写成 {@code "^" + CmdPrefix.PREFIX + "…命令词…"}。
 * 前缀<b>自带 {@code (?isU)}</b>，插件那边不用再写；取参数时用 {@link #stripMention(String)}
 * 剥掉同一段 {@code @某人}。
 */
public final class CmdPrefix {

    private static final Logger log = LoggerFactory.getLogger(CmdPrefix.class);

    private CmdPrefix() {
    }

    /**
     * 统一 flags：{@code i} 忽略大小写 / {@code s} 点穿行 / <b>{@code U} Unicode 字符类</b>。
     *
     * <p>直接拼在 {@link #PREFIX} 开头，这样各插件<b>不需要</b>自己记得加 {@code U}。
     */
    public static final String FLAGS = "(?isU)";

    /** 名字长度上限（防呆：免得一行超长噪声被当成名字去比对）。 */
    private static final int MAX_NAME_CHARS = 32;

    /**
     * 「手打的 @某人」这一小节的<b>语法</b>：{@code @}（或全角 {@code ＠}）+ 名字 + 可选空白。
     *
     * <p>⚠️ 这里<b>有意不写任何具体昵称、也不能写</b> —— 名字对不对由
     * {@link #isAddressedToSelf(Long, Long, String)} 运行时判（见类注释第二节）。
     * 允许名字为空（光一个 {@code @}）是怕有人 @ 完没打名字，那种情况在判定里当"没有名字"放过。
     * 末尾 {@code \s*} 让 {@code @Hana搜视频}（中间没空格）也能用 ——
     * 靠正则回溯：{@code {0,32}} 先贪心吃掉 {@code Hana搜视频}，发现后面接不上命令词，
     * 再吐回来只吃 {@code Hana}。
     */
    public static final String MENTION_ANY = "[@＠][^\\s@＠]{0,32}\\s*";

    /**
     * 可选前缀 = 行首空白 + CQ 码 + 手打的 @某人。三段都可缺省，所以"裸命令"照旧命中。
     */
    public static final String PREFIX =
            FLAGS + "\\s*(?:\\[CQ:[^]]*\\]\\s*)*(?:" + MENTION_ANY + ")?";

    /** 逐个剥头部 CQ 段用（配 {@code region}+{@code lookingAt}，见 {@code skipLeadingNoise}） */
    private static final Pattern LEADING_CQ = Pattern.compile("\\[CQ:([^]]*)]");

    // ==================================================================== 运行时称呼

    /**
     * 与账号无关的称呼（昵称 + QQ 号）：任何群、私聊都认。
     *
     * <p>由 {@link SelfNameRegistry} 写入；<b>空 = 还没拿到</b>（此时判定降级放行，见下）。
     */
    private static volatile Set<String> globalNames = Set.of();

    /** 群名片（机器人改名只在某个群改了）：键 = 群号，值 = 该群里机器人的称呼。 */
    private static volatile Map<Long, Set<String>> groupNames = Map.of();

    /** 配置项 {@code botNameAliases} 里的通用叫法（与账号无关，如"机器人"），同样小写归一。 */
    private static volatile Set<String> aliasNames = Set.of();

    /** 只在"称呼还没拿到"时告警一次，避免每条消息刷一行日志。 */
    private static volatile boolean warnedNoNames;

    /**
     * 一条「头部手打 @」的解析结果。
     *
     * @param length    从字符串第 0 位起要剥掉的字符数（含 {@code @}、名字、尾随空白）
     * @param nameChars 归一化后名字的长度；{@code 0} 表示"光一个 @ / @ 后面直接空白"
     * @param self      这个名字命中已知称呼（{@link #globalNames}/{@link #groupNames}/{@link #aliasNames}）
     */
    private record Mention(int length, int nameChars, boolean self) {
    }

    /**
     * 写入 OneBot 那边取到的称呼：{@code global} = 昵称 + 自身 QQ 号，{@code perGroup} = 群名片。
     *
     * <p>由 {@link SelfNameRegistry} 调用，<b>启动即拉一次、之后定期刷新</b>。
     * 任何插件都<b>不该</b>再往这里塞写死的昵称 —— 换号就废，这正是当初要改成运行时的原因。
     */
    public static void setOneBotNames(Collection<String> global, Map<Long, ? extends Collection<String>> perGroup) {
        globalNames = Set.copyOf(normalizeAll(global));
        Map<Long, Set<String>> pg = new LinkedHashMap<>();
        if (perGroup != null) {
            perGroup.forEach((gid, names) -> {
                Set<String> n = normalizeAll(names);
                if (gid != null && !n.isEmpty()) {
                    pg.put(gid, n);
                }
            });
        }
        groupNames = pg.isEmpty() ? Map.of() : Map.copyOf(pg);
        warnedNoNames = false;
    }

    /** 写入配置项 {@code botNameAliases} 的通用叫法（逗号/顿号/竖线/空白分隔，见 {@link #splitNames}）。 */
    public static void setAliasNames(Collection<String> names) {
        aliasNames = Set.copyOf(normalizeAll(names));
    }

    /** 从配置值里拆出名字列表（{@code 机器人,bot} / {@code 机器人 bot} / {@code 机器人|bot} 都吃）。 */
    public static List<String> splitNames(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("[,，、|｜/／\\s]+")) {
            // 顺手把用户「照着写法抄」带上的 @ 去掉（配置里写 @机器人 也能用）
            String n = part.replace("@", "").replace("＠", "").trim();
            if (!n.isEmpty() && n.length() <= MAX_NAME_CHARS && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** OneBot 那边取到的称呼（探针/日志用；两个都空表示还没取到）。 */
    public static Set<String> oneBotNames() {
        Set<String> all = new LinkedHashSet<>(globalNames);
        groupNames.values().forEach(all::addAll);
        return all;
    }

    /** 某个群里适用的全部称呼：全局 ∪ 该群群名片 ∪ 配置别名。 */
    public static Set<String> namesFor(Long groupId) {
        Set<String> all = new LinkedHashSet<>(globalNames);
        if (groupId != null) {
            Set<String> g = groupNames.get(groupId);
            if (g != null) {
                all.addAll(g);
            }
        }
        all.addAll(aliasNames);
        return all;
    }

    /** 当前生效的全部称呼（调试/探针用）。 */
    public static Set<String> allNames() {
        Set<String> all = oneBotNames();
        all.addAll(aliasNames);
        return all;
    }

    /** 配置别名（探针用）。 */
    public static Set<String> aliasNames() {
        return aliasNames;
    }

    /**
     * 这条消息是不是在<b>跟机器人说</b>（决定要不要执行它命中了的命令）。
     *
     * <p>规则：消息<b>头部</b>的 @ 若存在，必须指向机器人自己；没有 @ 就是裸命令，放行。
     * <ul>
     *   <li>{@code 搜视频 x} ⇒ ✔（裸命令）</li>
     *   <li>{@code @<机器人昵称/群名片/QQ号> 搜视频 x} ⇒ ✔</li>
     *   <li>{@code @张三 搜视频 x} ⇒ ✘（@ 别人时顺口带的命令词，不该由机器人执行）</li>
     *   <li>{@code [CQ:at,qq=张三] 搜视频 x} ⇒ ✘（真 @ 别人同理）</li>
     *   <li>{@code [CQ:at,qq=all] 搜视频 x} ⇒ ✘（@全体成员同理）</li>
     *   <li>{@code [CQ:image,…] 搜视频 x} ⇒ ✔（图片不是 @，不拦）</li>
     *   <li>{@code 搜视频 @张三 的视频} ⇒ ✔（@ 在中间，不在"命令头"范围内）</li>
     * </ul>
     *
     * <p>⚠️ <b>降级放行</b>：若这个上下文里<b>一个称呼都还没有</b>（刚启动 / OneBot 没连上 /
     * 该群的成员信息没取到），此时无法判断 {@code @Hana} 是不是机器人，<b>一律放行</b>并告警一次
     * —— 宁可暂时回到"宽松"的旧行为，也不要让群友对着没反应的机器人发呆。
     * {@link SelfNameRegistry} 启动即拉一次、之后定期刷新，拿到之后立刻转为严格。
     *
     * @param selfId     机器人自己的 QQ 号（{@code bot.getSelfId()}），用于判真 @
     * @param groupId    群号（私聊传 {@code null}），用于只看该群的群名片
     * @param rawMessage 原始消息（{@code event.getMessage()}，含 CQ 码）
     */
    public static boolean isAddressedToSelf(Long selfId, Long groupId, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return true;
        }
        String text = rawMessage.strip();

        // ① 头部可能有若干 CQ 段（真 @ / 图片 / 表情…）：只有 @ 别人 / @全体 才拦
        int i = 0;
        Matcher cq = LEADING_CQ.matcher(text);
        while (i < text.length()) {
            cq.region(i, text.length());
            if (!cq.lookingAt()) {
                break;
            }
            String body = cq.group(1);
            if (body.toLowerCase(Locale.ROOT).startsWith("at,")) {
                String qq = cqParam(body, "qq");
                if (selfId == null || !String.valueOf(selfId).equals(qq)) {
                    return false;
                }
            }
            i = skipSpace(text, cq.end());
        }
        if (i >= text.length()) {
            return true;                        // 整条消息就是 CQ 码，没别的可判
        }

        // ② 再看有没有"手打的 @名字"
        if (!isAtSign(text.charAt(i))) {
            return true;
        }
        Set<String> names = namesFor(groupId);
        Mention m = mention(text.substring(i), names);
        if (m.nameChars() == 0) {
            return true;                        // 光一个 @ 没名字（或 @ 完直接跟命令词）⇒ 不指向某个人
        }
        if (m.self()) {
            return true;
        }
        if (names.isEmpty()) {
            if (!warnedNoNames) {
                warnedNoNames = true;
                log.warn("还没从 OneBot 取到机器人的称呼（selfId={}），暂时放行所有 @ —— "
                        + "拿到昵称/群名片后自动转为严格判定", selfId);
            }
            return true;
        }
        return false;
    }

    /** 便利重载：没有群上下文（私聊 / 探针）。 */
    public static boolean isAddressedToSelf(Long selfId, String rawMessage) {
        return isAddressedToSelf(selfId, null, rawMessage);
    }

    /**
     * 剥掉命令<b>最前面</b>那一段噪声（行首空白 + CQ 码 + 手打的 @某人），换成空格。
     *
     * <p><b>为什么匹配之外还要这个方法</b>：匹配命中之后各插件都要<b>取参数</b>
     * （房间号 / 开关项 / 关键词…），而取参数的写法普遍是"按空白切词、取第 N 个"。
     * 真 @ 的 CQ 段各插件的取参函数早就换成空格了，但<b>手打的 {@code @Hana}
     * 只是普通文本</b>，会作为第一个 token 顶在命令词前面 —— 于是
     * {@code @Hana 反刷屏 设置 窗口 60} 的 {@code tokens.get(2)} 从「窗口」挤成「设置」、
     * {@code @Hana 开关 热评 开} 把「@Hana」当成开关名 ⇒ 命令明明命中了却回一句"不认识的 xx"。
     *
     * <p><b>剥多少</b>：优先按<b>已知称呼</b>精确剥（这样 {@code @Hana搜视频 影视飓风}
     * 只会剥掉 {@code @Hana}，留下 {@code 搜视频 影视飓风}）；认不出就退化成
     * "@ + 一串非空白"（≤{@link #MAX_NAME_CHARS} 字）整体剥掉。之所以退化分支可以这么宽：
     * 能走到 handler 里的前置 @ 已经被 {@link #isAddressedToSelf} 判过是"跟机器人说"了。
     *
     * <p>所以：<b>匹配用 {@link #PREFIX}，取值用这个方法</b>，两者同一份 {@link #MENTION_ANY} 语义。
     */
    public static String stripMention(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int i = skipLeadingNoise(text);
        return i == 0 ? text : " " + text.substring(i);
    }

    /** 跳过行首空白 + 连续 CQ 段 + 手打 @某人，返回"真实内容"的起始下标。 */
    private static int skipLeadingNoise(String text) {
        int i = skipSpace(text, 0);
        Matcher cq = LEADING_CQ.matcher(text);
        while (i < text.length() && text.charAt(i) == '[') {
            cq.region(i, text.length());
            if (!cq.lookingAt()) {
                break;
            }
            i = skipSpace(text, cq.end());
        }
        if (i < text.length() && isAtSign(text.charAt(i))) {
            Mention m = mention(text.substring(i), allNames());
            i = skipSpace(text, i + Math.max(1, m.length()));
        }
        return i;
    }

    /**
     * 解析「头部手打 @」：{@code text} 必须以 {@code @}/{@code ＠} 开头。
     *
     * <p>名字命中判定用 {@link String#startsWith} 而<b>不是</b>整串相等 ——
     * 这样 {@code @Hana搜视频}（@ 完没打空格）也算命中，且不需要
     * {@link Pattern#quote}（名字里带正则元字符也不会炸）。
     */
    private static Mention mention(String text, Set<String> names) {
        if (text.isEmpty() || !isAtSign(text.charAt(0))) {
            return new Mention(0, 0, false);
        }
        String rest = text.substring(1);
        String compact = compact(rest);

        String best = null;
        for (String n : names) {
            if (compact.startsWith(n) && (best == null || n.length() > best.length())) {
                best = n;
            }
        }

        int i;
        int nameChars;
        if (best != null) {
            // 按「归一化后第 best.length() 个非空白字符」在原文里定位结尾，
            // 这样 @Ha na 也能正确剥到 "na" 结束
            int need = best.length();
            int seen = 0;
            i = 0;
            while (i < rest.length() && seen < need) {
                if (!Character.isWhitespace(rest.charAt(i))) {
                    seen++;
                }
                i++;
            }
            nameChars = need;
        } else {
            i = 0;
            while (i < rest.length() && i < MAX_NAME_CHARS) {
                char ch = rest.charAt(i);
                if (Character.isWhitespace(ch) || isAtSign(ch)) {
                    break;
                }
                i++;
            }
            nameChars = i;
        }
        int end = skipSpace(rest, i);
        return new Mention(1 + end, nameChars, best != null);
    }

    /** 从 CQ 参数串（{@code at,qq=123,name=x}）里取某个 key。 */
    private static String cqParam(String cqBody, String key) {
        for (String part : cqBody.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static boolean isAtSign(char c) {
        return c == '@' || c == '＠';
    }

    /** 跳过空白。<b>不能用 {@code String.trim} 那套</b>：{@code Character.isWhitespace} 认 U+3000。 */
    private static int skipSpace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static Set<String> normalizeAll(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            String v = compact(n);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    /** 归一化：去掉名字里的<b>全部</b>空白、转小写，让 {@code @Ha na} 也能对上 {@code Hana}。 */
    private static String compact(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
