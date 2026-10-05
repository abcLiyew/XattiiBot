package com.esdllm.botPlugins;

import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.common.BotAdminChecker;
import com.esdllm.common.CookieUtils;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.service.CredentialGuard;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用聊天命令管理 B 站的<b>全局出站配置</b>：登录 Cookie 与 HTTP 代理。
 *
 * <p>背景：动态推送依赖 {@code x/polymer/web-dynamic/v1/feed/space}，该端点匿名过不去
 * （不带指纹 Cookie → HTTP 412；带上匿名指纹 → 业务码 -352），必须注入真实登录 Cookie。
 * 手动改 SQLite 太重，这里给一条命令。
 *
 * <p><b>支持的命令</b>（<b>仅机器人管理员</b>可用，私聊与群聊一视同仁）：
 * <pre>
 * 设置cookie buvid3:xxxx buvid4:yyyy SESSDATA:zzzz
 * 设置cookie
 *   buvid3:xxxx
 *   buvid4:yyyy
 *   SESSDATA:zzzz
 * 设置cookie SESSDATA=zzzz; bili_jct=xxxx        ← 直接贴浏览器的整串 Cookie 也行
 * cookie状态                                     ← 看当前生效状态
 * 清除cookie                                     ← 清掉，回到只用匿名指纹
 *
 * 设置代理 127.0.0.1:7890                        ← 让 B 站请求走这个 HTTP 代理
 * 代理状态                                       ← 看当前出口
 * 清除代理                                       ← 恢复直连
 *
 * 开关                                           ← 列出所有功能开关
 * 开关 热评 开                                   ← 改一项（热评/主播/摘要/动态源/探测间隔/录播网页…）
 * 开关 探期间隔 12                                ← 数字项直接给值
 * 开关 录播网页基址 https://rec.example.com       ← 文本项直接给值（发「自动」可清空）
 * </pre>
 *
 * <p><b>什么时候需要配代理</b>：Cookie 完全正确（日志里"出站身份"的键名齐全）却仍持续 412
 * —— 那是<b>出口 IP 被标记</b>，换出口是唯一的软件侧出路。详见
 * {@link LoadDSConfig#KEY_BILI_PROXY}。
 *
 * <p><b>权限模型</b>：这里的操作都是<b>全局</b>的（换 Cookie / 换出口 / 改开关会影响所有群、
 * 所有订阅），所以只认<b>机器人所有者</b>
 * （{@code common/BotAdminChecker#isBotOwner}：{@code admin} 表里 {@code qq_uid} 匹配
 * <b>且</b> {@code group_id} 为空的那一条）：
 * <b>群主/群管理员这类平台身份不算</b>，<b>「按群授权」的管理员也不算</b>，
 * <b>私聊也不再默认放行</b> —— 否则任何能给机器人发私信的人都能改全局凭据。
 *
 * <p>⚠️ <b>新增命令一律走 {@link #guard}</b>：权限判定与异常兜底都在那里，
 * 各 handler 不要再自己写一遍（原先有 7 份拷贝，而且都没有 {@code catch}）。
 *
 * <p><b>为什么是"所有者"而不是"机器人管理员"</b>：全局配置改的是<b>整个机器人共用</b>的
 * 凭据与出口，影响面 = 所有群 + 所有订阅，写坏了也只能由所有者收拾 ⇒ 与
 * {@code BiliLoginPlugins} 的「登录」取<b>同一档</b>。早先这里用的是 {@code isBotAdmin}
 * （只按 {@code qq_uid} 命中 {@code admin} 表），它的覆盖面包含「按群授权」的记录
 * （{@code group_id} 有值）—— 等于把"他在某个群当管理员"放大成
 * "他能改整个机器人的出站身份"：<b>越想收窄授权，实际越放大权限</b>。
 *
 * <p><b>安全约定</b>：
 * <ul>
 *   <li>命令发出的消息里含凭据 → 处理完会<b>尝试撤回</b>该消息（撤回失败会提示手动撤回）；</li>
 *   <li>回复里<b>只出现键名，绝不出现值</b>；日志同样只打长度；</li>
 *   <li>只更新你发来的键，<b>其余键沿用已保存的</b>（SESSDATA 过期时只发 SESSDATA 就行）。</li>
 * </ul>
 */
@Slf4j
@Component
@Shiro
public class BiliConfigPlugins {

    /** 允许出现在前置的 CQ 码（@机器人 / 图片等），匹配时忽略 */
    private static final String LEADING_CQ = "(\\[CQ:[^]]*\\]\\s*)*";

    /** 设置 Cookie：`设置cookie ...` / `配置cookie ...`（`. *` 配 DOTALL，允许换行粘贴） */
    private static final String CMD_SET = "(?is)^" + LEADING_CQ + "(?:设置|配置)cookie.*";
    /** 查看 Cookie 状态 */
    private static final String CMD_STATUS = "(?is)^" + LEADING_CQ + "(?:cookie状态|查看cookie|cookie查询).*";
    /** 清除 Cookie */
    private static final String CMD_CLEAR = "(?is)^" + LEADING_CQ + "(?:清除|删除|清空)cookie.*";
    /** 设置代理 */
    private static final String CMD_PROXY_SET = "(?is)^" + LEADING_CQ + "(?:设置|配置)代理.*";
    /** 查看代理状态 */
    private static final String CMD_PROXY_STATUS = "(?is)^" + LEADING_CQ + "(?:代理状态|查看代理|代理查询).*";
    /** 清除代理 */
    private static final String CMD_PROXY_CLEAR = "(?is)^" + LEADING_CQ + "(?:清除|删除|清空)代理.*";
    /**
     * 功能开关：<code>开关</code>（列出全部）/ <code>开关 热评 开</code>（修改一项）。
     *
     * <p>末尾刻意用 <code>(?:\\s.*|状态)?$</code> 而不是 <code>.*</code>：尾巴上必须紧跟
     * <b>空白或行尾</b>，否则群友随手打的「<b>开关</b>灯怎么修」会被当成命令吃掉并回一句
     * "未知开关"。宁可漏判（用户补个空格即可），也不要误判别人的日常聊天。
     */
    private static final String CMD_SWITCH =
            "(?is)^" + LEADING_CQ + "(?:功能)?开关(?:\\s.*|状态)?$";

    /** 分隔符：空白、分号（兼容 `a=1; b=2` 与 `key:value` 混排） */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s;]+");
    /** 合法的 Cookie 键名 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{1,40}$");

    /**
     * {@link Kind#TEXT} 项的合法形状：{@code http(s)://主机[:端口][/路径]}，不接受空白。
     *
     * <p>校验的意义在于<b>别把错字存进去</b>：基址会被拼进发出去的链接，一旦写成
     * {@code rec.example.com}（漏 scheme）或 {@code http://}（没主机），
     * 群里拿到的就是一条点开必错的链接 —— 而且它是"显式配置"，会绕过"推断不出公网就不发"那道闸。
     * 所以宁可在这里拒绝、让人重打一遍。
     *
     * <p>⚠️ 主机那一段刻意写成"<b>方括号 IPv6 或 不含 {@code / : 空白} 的串</b>"，
     * 而不是图省事的 {@code [^/\s]+}：后者会把 {@code http://:2233} 整个当主机收下
     * —— 只有端口没有主机，看着像 URL 其实点不开（这个漏网是探针先试出来的）。
     */
    private static final Pattern HTTP_URL =
            Pattern.compile("(?i)^https?://(\\[[^]]+]|[^\\s/:]+)(:\\d{1,5})?(/\\S*)?$");

    /**
     * {@link Kind#TEXT} 项的"清空"记号（大小写不敏感）。
     *
     * <p>为什么需要它：{@link LoadDSConfig#stringOf} 把<b>空白值当作"没配"</b>，
     * 所以"写空串"就等于"回到默认（这里=自动推断）"。但没法让用户打一个空字符串 —
     * 得给个词。{@code -} 也在表里，那是删配置时的顺手习惯。
     */
    private static final Set<String> CLEAR_WORDS = Set.of("自动", "auto", "清空", "删除", "-", "none");

    /** 提示里怎么称呼"清空"这件事（用 {@link #CLEAR_WORDS} 里最顺口的那个）。 */
    private static final String CLEAR_WORD_HINT = "自动";

    /** 至少要有其中一个键，否则认为粘贴内容不对，不覆盖已保存的 Cookie */
    private static final String[] REQUIRED_ANY = {"SESSDATA", "buvid3", "buvid4"};
    /** 单条命令的长度上限，防止异常输入 */
    private static final int MAX_LENGTH = 4000;

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private BotAdminChecker botAdminChecker;
    @Resource
    private CredentialGuard credentialGuard;

    /**
     * 设置 Cookie（增量合并 + 即时生效，无需重启）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SET, at = AtEnum.BOTH)
    public void setCookie(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doSetCookie(bot, event));
    }

    /** 「设置cookie」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doSetCookie(Bot bot, AnyMessageEvent event) {
        String message = event.getMessage();
        if (message == null || message.length() > MAX_LENGTH) {
            send(bot, event, "内容过长或为空，已忽略");
            return;
        }

        Map<String, String> pairs = parsePairs(message);
        if (pairs.isEmpty()) {
            send(bot, event, MsgUtils.builder()
                    .text("没解析出 Cookie，格式示例：\n")
                    .text("设置cookie buvid3:xxx buvid4:yyy SESSDATA:zzz\n")
                    .text("或直接贴浏览器的整串：设置cookie SESSDATA=xxx; bili_jct=yyy")
                    .build());
            return;
        }
        boolean hasRequired = false;
        for (String key : REQUIRED_ANY) {
            if (pairs.containsKey(key)) {
                hasRequired = true;
                break;
            }
        }
        if (!hasRequired) {
            send(bot, event, "解析出的键里没有 SESSDATA / buvid3 / buvid4，" +
                    "为免把已有 Cookie 覆盖坏，本次不保存。\n解析到的键：" + String.join(",", pairs.keySet()));
            return;
        }

        // 与已保存的合并：只更新本次发来的键，其余沿用（SESSDATA 过期时只发 SESSDATA 即可）
        Map<String, String> merged = new LinkedHashMap<>();
        String saved = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_COOKIE);
        if (saved != null && !saved.isBlank()) {
            merged.putAll(parseCookieString(saved));
        }
        int before = merged.size();
        merged.putAll(pairs);

        // 消息里含凭据 —— 尽量撤回。
        // ⚠️ 撤回刻意提前到**写库之前**：这条消息里已经确认有 SESSDATA / buvid 之类的凭据，
        //    那么无论后面保存成败，都不该让它继续留在群聊记录里。
        //    （原先放在"保存成功之后" ⇒ 一旦写库抛异常就直接 return，凭据会一直挂在群里。）
        boolean recalled = recall(bot, event);

        String cookie = join(merged);
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_BILI_COOKIE, cookie);
        } catch (Exception e) {
            log.error("保存 B 站 Cookie 失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }

        StringBuilder reply = new StringBuilder("✅ 已保存并即时生效（无需重启）\n")
                .append("键：").append(String.join(", ", merged.keySet())).append("\n")
                .append("长度：").append(cookie.length());
        if (merged.size() > before) {
            reply.append("\n（新增 ").append(merged.size() - before).append(" 个键，其余与原有合并）");
        }
        if (!merged.containsKey("SESSDATA")) {
            reply.append("\n⚠️ 没看到 SESSDATA —— 动态列表接口大概率仍会报 -352");
        }
        reply.append("\n下一轮动态推送（≤60s）就会用它。");
        if (event.getGroupId() != null && !recalled) {
            reply.append("\n⚠️ 未能撤回你刚才那条消息，请手动撤回（里面含凭据）");
        }
        send(bot, event, reply.toString());
        log.info("已通过聊天命令更新 B 站 Cookie，键：{}，长度 {}",
                String.join(",", merged.keySet()), cookie.length());
    }

    /**
     * 查看当前生效状态：<b>本地事实 + 服务端实时校验</b>。
     *
     * <p>前几项（是否注入 / 键名 / 长度 / config 表有没有值）都是本地事实，零成本。
     * 但从 P0-1 起，命令会<b>再问一次服务端</b> —— 因为「已注入」不等于「还有效」，
     * 而后者才是用户真正想知道的（历史上"Cookie 过期只能靠推送失败发现"就是这么来的）。
     *
     * <p>⚠️ 这一两个请求只在<b>用户主动发命令</b>时产生，频率天然很低，可以接受；
     * <b>不要把这条逻辑搬进定时任务</b> —— 原因见 {@link CredentialGuard} 的类注释
     * （探测打的是被风控盯着的域，且已登录时一次探测要 2 个请求）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_STATUS, at = AtEnum.BOTH)
    public void cookieStatus(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doCookieStatus(bot, event));
    }

    /** 「cookie状态」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doCookieStatus(Bot bot, AnyMessageEvent event) {
        String applied = HttpPolicy.getCookie();
        StringBuilder reply = new StringBuilder("B站 Cookie 状态\n")
                .append("已注入：").append(HttpPolicy.hasCookie() ? "是" : "否").append("\n");
        if (HttpPolicy.hasCookie()) {
            reply.append("键：").append(String.join(", ", parseCookieString(applied).keySet())).append("\n")
                    .append("长度：").append(applied.length()).append("\n");
        }
        String saved = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_COOKIE);
        reply.append("config 表 key=").append(LoadDSConfig.KEY_BILI_COOKIE)
                .append("：").append(saved == null || saved.isBlank() ? "未配置" : "已配置");
        if (!HttpPolicy.hasCookie()) {
            reply.append("\n\n配法：设置cookie buvid3:xxx buvid4:yyy SESSDATA:zzz");
            send(bot, event, reply.toString());
            return;
        }

        // —— 服务端实时校验（绕过节流：用户明确问了一次，就该拿到此刻的答案）——
        CredentialGuard.Status status = credentialGuard.probeNow(CredentialGuard.Reason.USER_COMMAND);
        switch (status.getVerdict()) {
            case VALID -> reply.append("\n服务端判定：✅ 有效")
                    .append("\n账号：").append(status.getUname() == null ? status.getUid() : status.getUname())
                    .append("（uid ").append(status.getUid()).append("）");
            case INVALID -> reply.append("\n服务端判定：❌ 已失效（").append(status.getSummary()).append("）")
                    .append("\n修法：私聊发「登录」扫码，或发「设置cookie SESSDATA:...」")
                    .append("\n（这与 -412 路径/出口被封不是一回事，换代理无效）");
            default -> reply.append("\n服务端判定：⚠️ 未能确认（").append(status.getSummary()).append("）")
                    .append("\n这属于网络/出口问题，不是凭据失效");
        }
        send(bot, event, reply.toString());
    }

    /**
     * 清除 Cookie，回到只用匿名指纹。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_CLEAR, at = AtEnum.BOTH)
    public void clearCookie(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doClearCookie(bot, event));
    }

    /** 「清除cookie」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doClearCookie(Bot bot, AnyMessageEvent event) {
        if (loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_COOKIE) == null) {
            send(bot, event, "本来就没配置 Cookie");
            return;
        }
        try {
            loadDSConfig.deleteConfig(LoadDSConfig.KEY_BILI_COOKIE);
        } catch (Exception e) {
            log.error("清除 B 站 Cookie 失败", e);
            send(bot, event, "清除失败：" + e.getMessage());
            return;
        }
        send(bot, event, "🗑 已清除 B站 Cookie，回到只用匿名指纹 —— 动态推送会因 B 站风控（-352）拉不到列表");
    }

    // ------------------------------------------------------------------ 代理

    /**
     * 设置 B 站请求走的 HTTP 代理，即时生效。
     *
     * <p>只接受 {@code host:port}（也容忍 {@code http://host:port}）。本库的代理参数只有
     * host/port，不带认证，所以形如 {@code user:pass@host:port} 会明确拒绝而不是"悄悄连不上"。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_PROXY_SET, at = AtEnum.BOTH)
    public void setProxy(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doSetProxy(bot, event));
    }

    /** 「设置代理」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doSetProxy(Bot bot, AnyMessageEvent event) {
        String raw = parseProxyArg(event.getMessage());
        if (raw.isEmpty()) {
            send(bot, event, "要给出地址，例如：设置代理 127.0.0.1:7890");
            return;
        }
        String[] target = LoadDSConfig.parseProxyTarget(raw);
        if (target == null) {
            send(bot, event, "地址格式不对（收到：" + raw + "）\n"
                    + "应为 host:port，例如 127.0.0.1:7890 或 http://10.0.0.5:8080\n"
                    + "不支持带账号密码的代理（user:pass@host:port）。");
            return;
        }
        String value = target[0] + ":" + target[1];
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_BILI_PROXY, value);
        } catch (Exception e) {
            log.error("保存 B 站代理失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        send(bot, event, "✅ B 站请求将走代理 " + value + "（已即时生效）\n"
                + "下一轮动态推送（≤60s）生效；若仍 412，说明该出口同样被标记。");
        log.info("已通过聊天命令设置 B 站代理：{}", value);
    }

    /**
     * 查看当前的出口与出站身份（不发起网络请求）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_PROXY_STATUS, at = AtEnum.BOTH)
    public void proxyStatus(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doProxyStatus(bot, event));
    }

    /** 「代理状态」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doProxyStatus(Bot bot, AnyMessageEvent event) {
        String saved = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_PROXY);
        StringBuilder reply = new StringBuilder("B站出站状态\n")
                .append("代理：").append(HttpPolicy.hasProxy()
                        ? HttpPolicy.getProxyHost() + ":" + HttpPolicy.getProxyPort() : "直连").append("\n")
                .append("config 表 key=").append(LoadDSConfig.KEY_BILI_PROXY)
                .append("：").append(saved == null || saved.isBlank() ? "未配置" : "已配置").append("\n")
                .append("Cookie：").append(HttpPolicy.hasCookie()
                        ? "已注入（" + HttpPolicy.cookieKeys() + "）" : "未注入").append("\n")
                .append("最小请求间隔：").append(HttpPolicy.getMinRequestIntervalMs()).append("ms\n")
                .append("风控轮换身份：").append(HttpPolicy.isRotateOnRiskControl() ? "开" : "关");
        if (!HttpPolicy.hasProxy()) {
            reply.append("\n\n持续 412 时（且 Cookie 键名齐全）可试：设置代理 host:port");
        }
        send(bot, event, reply.toString());
    }

    /**
     * 清除代理，恢复直连。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_PROXY_CLEAR, at = AtEnum.BOTH)
    public void clearProxy(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doClearProxy(bot, event));
    }

    /** 「清除代理」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doClearProxy(Bot bot, AnyMessageEvent event) {
        if (loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_PROXY) == null) {
            send(bot, event, "本来就没配置代理");
            return;
        }
        try {
            loadDSConfig.deleteConfig(LoadDSConfig.KEY_BILI_PROXY);
        } catch (Exception e) {
            log.error("清除 B 站代理失败", e);
            send(bot, event, "清除失败：" + e.getMessage());
            return;
        }
        send(bot, event, "🗑 已清除代理，B 站请求恢复直连");
    }

    // ------------------------------------------------------------------ 功能开关

    /**
     * 看 / 改 config 表里那几个<b>会影响 B 站请求量</b>的开关。
     *
     * <p>为什么要有这条命令：{@link LoadDSConfig#KEY_BILI_ANALYSIS_WITH_COMMENTS} 等几个键
     * 在部署文档里只写了"去 config 表加一行 SQL" —— 可这些开关的使用场景恰恰是<b>临时</b>的
     * （想让某条视频解析多带点信息、想临时把请求量压下来），为一次开关去连服务器改库不划算。
     * 这里给一条命令，<b>改完即时生效、不用重启</b>（取值都是每次现读 configMap）。
     *
     * <p><b>为什么权限顶格到"所有者"</b>：这几个开关是<b>全局</b>的（影响所有群、所有订阅的
     * 请求量），与「设置cookie / 设置代理」同一性质 ⇒ 同一口径，只认机器人所有者。
     *
     * <p><b>权限</b>：与「设置cookie / 设置代理」同口径（{@code BotAdminChecker#isBotOwner}），
     * 由 {@link #guard} 统一判定并兜住异常。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SWITCH, at = AtEnum.BOTH)
    public void switchConfig(Bot bot, AnyMessageEvent event) {
        guard(bot, event, () -> doSwitchConfig(bot, event));
    }

    /** 「开关」的实际逻辑；权限判定与异常兜底见 {@link #guard}。 */
    private void doSwitchConfig(Bot bot, AnyMessageEvent event) {
        String message = event.getMessage();
        if (message == null || message.length() > MAX_LENGTH) {
            send(bot, event, "内容过长或为空，已忽略");
            return;
        }

        List<String> args = parseSwitchArgs(message);
        if (args.isEmpty()) {
            send(bot, event, switchPanel());
            return;
        }
        Option option = Option.parse(args.get(0));
        if (option == null) {
            send(bot, event, "没有这个开关：「" + args.get(0) + "」\n\n" + switchPanel());
            return;
        }
        if (args.size() == 1) {
            // 只给了名字 ⇒ 展开这一项的详情（含配置键、代价、改法）
            send(bot, event, describe(option));
            return;
        }
        apply(bot, event, option, args.get(1));
    }

    /**
     * 校验并归一化用户输入。
     *
     * <p>⚠️ 用 switch <b>表达式</b>（每支 {@code yield}）而<b>不是</b>语句：语句形式的 switch over enum
     * 少写一支也编译得过（新加的 {@link Kind} 会静默走进"没校验直接落库"），表达式强制穷尽。
     * ⚠️ 也正因为是表达式，这里<b>不能写 {@code return}</b>（JLS 禁止在 switch 表达式里 return）——
     * 校验失败改成 {@code yield null}，由调用方判 null 提前返回。
     *
     * @return 要落进 config 的值；<b>{@code null} = 校验不过</b>
     *         （⚠️ 此时<b>已经回过消息了</b>，调用方直接 return，别重复报错）
     */
    private String normalize(Bot bot, AnyMessageEvent event, Option option, String rawValue) {
        return switch (option.kind) {
            case BOOL -> {
                Boolean bool = parseBool(rawValue);
                if (bool == null) {
                    send(bot, event, "「" + option.label + "」只认开 / 关"
                            + "（也接受 on/off、true/false、1/0、启用/禁用）。收到：" + rawValue);
                    yield null;
                }
                yield bool.toString();
            }
            case INT -> {
                try {
                    yield String.valueOf(Integer.parseInt(rawValue.trim()));
                } catch (NumberFormatException e) {
                    send(bot, event, "「" + option.label + "」要一个整数（单位小时）。收到：" + rawValue);
                    yield null;
                }
            }
            case SOURCE -> {
                String v = rawValue.trim().toLowerCase();
                Map<String, String> notes = option.valueNotes();
                if (!notes.containsKey(v)) {
                    StringBuilder hint = new StringBuilder("「" + option.label + "」只能填这几个值：");
                    notes.forEach((k, note) -> hint.append("\n  ").append(k).append(" = ").append(note));
                    hint.append("\n收到：").append(rawValue);
                    send(bot, event, hint.toString());
                    yield null;
                }
                yield v;
            }
            case TEXT -> {
                String v = rawValue.trim();
                // 清空记号 ⇒ 落空串：stringOf 把空白当"没配"，于是回到该项默认行为（基址=自动推断）
                if (CLEAR_WORDS.contains(v.toLowerCase())) {
                    yield "";
                }
                if (!HTTP_URL.matcher(v).matches()) {
                    send(bot, event, "「" + option.label + "」要一个 http/https 地址："
                            + "https://rec.example.com 或 http://1.2.3.4:2233\n"
                            + "（发「" + CLEAR_WORD_HINT + "」可清空、回到自动推断）\n收到：" + rawValue);
                    yield null;
                }
                yield v;
            }
        };
    }

    /**
     * 写一项配置。值的合法性按 {@link Kind} 分派校验，<b>校验不过就原样报错、不落库</b>
     * —— 免得把 config 写成半吊子值（例如给布尔项写个 {@code ture}），
     * 那种值在 fail-closed 判定下会静默变成"关"，反而比报错难查。
     */
    private void apply(Bot bot, AnyMessageEvent event, Option option, String rawValue) {
        String value = normalize(bot, event, option, rawValue);
        if (value == null) {
            return;                     // 校验不过：normalize 已经回过消息了
        }

        try {
            loadDSConfig.updateConfig(option.key, value);
        } catch (Exception e) {
            log.error("保存功能开关失败，key={}", option.key, e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }
        // 改完顺手把"这个值是什么意思"说清楚 —— 否则用户只看到 follow 一个词，还是不知道开了什么
        StringBuilder reply = new StringBuilder("✅ ").append(option.label).append(" → ")
                .append(shortValue(option, value))
                .append("（").append(option.key).append('=').append(value).append("，已即时生效、无需重启）\n")
                .append("   作用：").append(option.desc);
        String note = option.valueNotes().get(value);
        if (note != null) {
            reply.append("\n   ").append(value).append(" = ").append(note);
        }
        send(bot, event, reply.toString());
        log.info("已通过聊天命令修改功能开关：{}={}", option.key, value);
    }

    /**
     * 全部开关的一览。
     *
     * <p>用中文主名而不是配置键：用户发命令时该打的是「热评」，
     * 键名放在 {@link #describe} 的单查详情里（要对着 config 表核对的场合才需要）。
     */
    private String switchPanel() {
        StringBuilder sb = new StringBuilder("B站功能开关（均即时生效、无需重启）\n");
        for (Option option : Option.values()) {
            sb.append("· ").append(option.label).append("  [").append(currentValue(option)).append("]\n")
                    .append("    ").append(option.desc).append("\n");
            // 枚举项把「可选值 + 含义」一起列出来 —— 光甩 auto/follow/space 三个词等于没解释
            option.valueNotes().forEach((value, note) ->
                    sb.append("      ").append(value).append(" = ").append(note).append("\n"));
        }
        sb.append("""
                
                改：开关 <名称> <值>　例：开关 热评 开 ｜ 开关 摘要 关 ｜ 开关 动态源 follow
                查单项：开关 热评""");
        return sb.toString();
    }

    /** 单项详情：当前值 + 配置键 + 代价 + 改法。 */
    private String describe(Option option) {
        StringBuilder sb = new StringBuilder(option.label + "：" + currentValue(option) + "\n")
                .append("配置键：").append(option.key).append("\n")
                .append("作用：").append(option.desc).append("\n")
                .append("影响：").append(option.cost).append("\n");
        Map<String, String> notes = option.valueNotes();
        if (!notes.isEmpty()) {
            sb.append("可选值：\n");
            notes.forEach((value, note) -> sb.append("  ").append(value).append(" = ").append(note).append("\n"));
        }
        sb.append("改：开关 ").append(option.label).append(' ').append(option.exampleValue());
        return sb.toString();
    }

    /**
     * 该项在 config 表里的<b>当前值</b>，供人看。
     *
     * <p>布尔项刻意区分三种情形：未配置 / 明确的关 / <b>有值但读不出开或关</b>。
     * 最后一种要显式打出来 —— 因为 {@link LoadDSConfig#isEnabled} 是 fail-closed 的，
     * 一个拼错的 {@code ture} 会静默按"关"处理，不提示的话用户会以为"明明配了却不生效"。
     */
    private String currentValue(Option option) {
        String raw = loadDSConfig.getConfigMap().get(option.key);
        if (raw == null || raw.isBlank()) {
            // "没配是什么意思"按类型不同，收在 Option#emptyHint 一处 ——
            // 免得这里一个 switch、别处又写一遍，加类型时漏掉一边
            return "未配置（" + option.emptyHint() + "）";
        }
        if (option.kind == Kind.BOOL) {
            if (loadDSConfig.isEnabled(option.key)) {
                return "开";
            }
            return parseBool(raw) == null ? "关（值无效：" + raw + "）" : "关";
        }
        return raw;
    }

    /** 给回复用的短值（布尔项译成中文，其余原样）。 */
    private static String shortValue(Option option, String value) {
        if (option.kind == Kind.BOOL) {
            return "true".equals(value) ? "开" : "关";
        }
        if (option.kind == Kind.TEXT && (value == null || value.isBlank())) {
            // 文本项被清空 ⇒ 落库的是空串，回显时得说清楚这是"回到默认"而不是"没改成"
            return "(已清空，回到" + option.emptyHint() + ")";
        }
        return value;
    }

    /**
     * 拆出「开关」之后的参数。
     *
     * <p>先剥前置 CQ 码与命令词，再按空白/分号切。全角空格（U+3000）先归一化 ——
     * 中文输入法下极容易打出来，不归一化会被当成选项名的一部分而"找不到这个开关"。
     */
    private static List<String> parseSwitchArgs(String message) {
        String body = message.replaceAll("\\[CQ:[^]]*]", " ")
                .replace('\u3000', ' ');
        // 命令词本身可能有几种形态（开关 / 功能开关 / 开关状态），一并吃掉
        body = body.replaceFirst("(?is)^\\s*(?:功能)?开关(?:状态)?", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(body)) {
            String t = token.trim().replaceAll("^[\"',]+", "").replaceAll("[\"',]+$", "");
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * 解析布尔值。<b>只认明确的词</b>，认不出返回 {@code null}（调用方会拒绝落库），
     * 与 {@link LoadDSConfig#isEnabled} 的 fail-closed 口径保持一致。
     */
    private static Boolean parseBool(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "开", "开启", "启用", "是", "on", "true", "1", "yes" -> Boolean.TRUE;
            case "关", "关闭", "禁用", "否", "off", "false", "0", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** 值的类型，决定怎么写进 config 表、怎么校验。 */
    private enum Kind {
        /** 布尔开关，落库为 {@code true}/{@code false} */
        BOOL,
        /** 整数（如小时数） */
        INT,
        /** 枚举（如 auto/follow/space） */
        SOURCE,
        /**
         * 自由文本（如 URL），合法形状由 {@code apply} 按项校验。
         *
         * <p>⚠️ 加这个常量<b>必须同步改 {@link #currentValue} 的 {@code switch (kind)}</b>
         * —— 那是 switch <b>表达式</b>，少一个分支直接编译不过（好事：编译器替我们兜住）。
         * 而 {@code apply} 里那个是 switch <b>语句</b>，编译器不查穷尽 —— 那边靠 {@link HTTP_URL} 这类
         * 校验自己兜，别以为加了枚举值就万事大吉。
         */
        TEXT
    }

    /**
     * 一条可配置项：中文名 + 别名 + 配置键 + 类型 + 说明/代价。
     *
     * <p>只收「有明确取舍、用户会想临时改」的项。像 {@code pushedDynamicIds}（去重记录）
     * 这种<b>运行状态</b>刻意不进这张表 —— 它不是给人改的参数，改坏了会重推一轮。
     */
    private enum Option {
        HOT_COMMENTS("热评", Kind.BOOL, LoadDSConfig.KEY_BILI_ANALYSIS_WITH_COMMENTS,
                "视频解析附带热评",
                "开：单次解析的 B 站请求 1→2（热评不在 view/detail 响应里，要另打一次评论接口）",
                "评论", "comments", "comment"),
        LIVE_MASTER("主播", Kind.BOOL, LoadDSConfig.KEY_BILI_LIVE_WITH_MASTER_INFO,
                "直播解析附带主播信息（粉丝数 / 粉丝牌）",
                "开：单次解析 1→2（要另打一次 getMasterInfo）",
                "直播", "live", "master"),
        AI_SUMMARY("摘要", Kind.BOOL, LoadDSConfig.KEY_BILI_ANALYSIS_WITH_SUMMARY,
                "视频解析附带 AI 摘要（B 站官方「AI 视频总结」）",
                "开：单次解析 1→2；⚠️ 且该端点硬要求登录，未配 Cookie 时开着也不发请求",
                "ai", "summary", "总结"),
        DYNAMIC_SOURCE("动态源", Kind.SOURCE, LoadDSConfig.KEY_BILI_DYNAMIC_SOURCE,
                "动态推送的数据源",
                "⚠️ 切到 follow 的前提：配 Cookie 的那个 B 站账号必须已关注被订阅的 UP，否则推不到",
                "动态", "数据源", "source", "dynamic"),
        CRED_CHECK_HOURS("探测间隔", Kind.INT, LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS,
                "凭据兜底探测间隔（小时）",
                "≤0 = 关闭兜底（只保留推送失败时的事件驱动探测）；不配则默认 "
                        + CredentialGuard.DEFAULT_CHECK_HOURS,
                "探测", "间隔", "interval", "hours"),
        // ⚠️ 录播这两项属于「要暴露本机文件 / 要人知道真实拓扑」的那一类，与换 Cookie、换出口同性质
        // ⇒ 走同一个入口（只认机器人所有者），也正好对应"让所有者配一个公网可达的域名或 IP"。
        RECORD_WEB("录播网页", Kind.BOOL, LoadDSConfig.KEY_BILI_RECORD_WEB_ENABLED,
                "把本群订阅的录播做成网页（浏览 / 在线播放 / 下载）",
                "开：本机磁盘上的录播文件会暴露给浏览器。链接本身就是凭证（等于这个群的共享密码）、"
                        + "可以被转发；泄了就发「录播网页重置」",
                "网页", "录播", "web", "record"),
        RECORD_WEB_BASE("录播网页基址", Kind.TEXT, LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL,
                "「录播网页」发出去的链接用哪个地址开头",
                "不配则按网卡自动推断；推断出来不是公网地址时不会发链接（只回一段提示让你配这项）"
                        + " ⇒ 机器人跑在内网/容器里时，这是必配项",
                "网页基址", "基址", "网址", "baseurl", "weburl");

        private final String label;
        private final Kind kind;
        private final String key;
        private final String desc;
        private final String cost;
        private final String[] aliases;

        Option(String label, Kind kind, String key, String desc, String cost, String... aliases) {
            this.label = label;
            this.kind = kind;
            this.key = key;
            this.desc = desc;
            this.cost = cost;
            this.aliases = aliases;
        }

        /** 按中文主名或别名找一项；找不到返回 {@code null}。 */
        static Option parse(String name) {
            if (name == null) {
                return null;
            }
            String value = name.trim();
            for (Option option : values()) {
                if (option.label.equalsIgnoreCase(value)) {
                    return option;
                }
                for (String alias : option.aliases) {
                    if (alias.equalsIgnoreCase(value)) {
                        return option;
                    }
                }
            }
            return null;
        }

        /** 详情里给的示例值，让人照着改。 */
        String exampleValue() {
            return switch (kind) {
                case INT -> "12";
                case SOURCE -> "follow";
                case TEXT -> "https://rec.example.com";
                default -> "开";
            };
        }

        /**
         * 该项<b>没配</b>时的实际行为（面板/详情里写在"未配置（…）"括号里）。
         *
         * <p>单独一个方法是为了让"每种类型没配是什么样"只有一份 —— 之前这段是内联在
         * {@code currentValue} 的 switch 里的，加 {@link Kind#TEXT} 时必然要改两处。
         */
        String emptyHint() {
            return switch (kind) {
                case BOOL -> "按关处理";
                case INT -> "默认 " + CredentialGuard.DEFAULT_CHECK_HOURS;
                case SOURCE -> "按 auto 处理";
                case TEXT -> this == RECORD_WEB_BASE ? "自动推断基址" : "不配该项";
            };
        }

        /**
         * 枚举型选项的「取值 → 一句解释」，<b>按展示顺序</b>排列；非枚举项返回空表。
         *
         * <p>🔴 <b>取值集合也从这里取</b>（{@code keySet()}）—— 校验和说明是<b>同一份数据</b>，
         * 免得出现"能填的值"和"告诉用户能填的值"对不上。原先这三个取值的解释只写在
         * {@code cost} 字段里，而 {@code cost} 只在「单查」时显示 ⇒ 面板和改完后的回复里
         * 都只有干巴巴的 `auto / follow / space`，用户根本不知道是什么意思。
         */
        Map<String, String> valueNotes() {
            Map<String, String> notes = new LinkedHashMap<>();
            if (this == DYNAMIC_SOURCE) {
                notes.put("auto", "先试空间流（按 uid 逐个拉），被判风控就自动切关注流 —— 默认值，不用改");
                notes.put("follow", "始终走关注流：一轮 1 次请求覆盖全部订阅；⚠️ 要求配 Cookie 的账号已关注被订阅的 UP");
                notes.put("space", "始终按 uid 拉空间动态（出口没被 B 站单独封的环境用这个）");
            } else if (this == RECORD_WEB_BASE) {
                // ⚠️ TEXT 项的 notes 只用于**展示**：它的合法性由 HTTP_URL 正则判，
                // 不像 SOURCE 那样拿 keySet() 当取值集合。所以这里可以放心写示例。
                notes.put("https://rec.example.com",
                        "推荐：域名 + 反代。⚠️ 反代必须透传 Range 头，否则进度条和断点续传失效");
                notes.put("http://1.2.3.4:2233", "公网 IP 直连：端口写 HTTP 服务真实监听的端口");
                notes.put(CLEAR_WORD_HINT, "清空 ⇒ 回到按网卡自动推断（机器有公网网卡时才推得出来）");
            }
            return notes;
        }
    }

    /**
     * 取代理参数：命令词之后的第一段非空内容。
     *
     * @param message 原始消息
     * @return 地址字符串；没给出时返回空串
     */
    private static String parseProxyArg(String message) {
        if (message == null || message.length() > MAX_LENGTH) {
            return "";
        }
        String body = message.replaceAll("\\[CQ:[^]]*]", " ");
        body = body.replaceFirst("(?is)(?:设置|配置|清除|删除|清空|查看|查询)?代理(?:状态|查询)?", " ");
        for (String token : TOKEN_SPLIT.split(body)) {
            String t = token.trim().replaceAll("^[\"'<>,]+", "").replaceAll("[\"'<>,]+$", "");
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 从消息里解析 {@code key:value} / {@code key=value} 片段。
     *
     * @param message 原始消息
     * @return 解析出的键值（保序）；解析不出返回空 Map
     */
    private static Map<String, String> parsePairs(String message) {
        String body = message.replaceAll("\\[CQ:[^]]*]", " ");
        // 中文输入法很容易打出全角冒号，先归一化，否则会被误判成"格式不对"
        body = body.replace('：', ':');
        // 去掉命令词本身
        body = body.replaceFirst("(?is)(?:设置|配置)cookie", " ");
        body = body.replaceFirst("(?is)(?:cookie状态|查看cookie|cookie查询|清除cookie|删除cookie|清空cookie)", " ");

        Map<String, String> pairs = new LinkedHashMap<>();
        for (String token : TOKEN_SPLIT.split(body)) {
            String t = token.trim();
            // 去掉两端可能残留的引号/逗号
            t = t.replaceAll("^[\"',]+", "").replaceAll("[\"',]+$", "");
            if (t.isEmpty() || t.contains("://") || t.contains("/")) {
                continue;
            }
            int colon = t.indexOf(':');
            int equals = t.indexOf('=');
            int sep;
            if (colon < 0) {
                sep = equals;
            } else if (equals < 0) {
                sep = colon;
            } else {
                sep = Math.min(colon, equals);
            }
            if (sep <= 0 || sep >= t.length() - 1) {
                continue;
            }
            String key = t.substring(0, sep).trim();
            String value = t.substring(sep + 1).trim();
            if (!KEY_PATTERN.matcher(key).matches() || value.isEmpty()) {
                continue;
            }
            pairs.put(key, value);
        }
        return pairs;
    }

    /**
     * 把已保存的 Cookie 字符串拆回键值对。
     */
    private static Map<String, String> parseCookieString(String cookie) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (cookie == null) {
            return pairs;
        }
        for (String part : cookie.split(";")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = t.substring(0, eq).trim();
            if (!KEY_PATTERN.matcher(key).matches()) {
                continue;
            }
            pairs.put(key, t.substring(eq + 1).trim());
        }
        return pairs;
    }

    private static String join(Map<String, String> pairs) {
        return CookieUtils.join(pairs);
    }

    /**
     * 尝试撤回含凭据的消息。
     *
     * @return 是否确实撤回成功
     */
    private static boolean recall(Bot bot, AnyMessageEvent event) {
        Integer messageId = event.getMessageId();
        if (messageId == null) {
            return false;
        }
        try {
            var result = bot.deleteMsg(messageId);
            boolean ok = result != null && result.getRetCode() != null && result.getRetCode() == 0;
            if (!ok) {
                log.warn("撤回含凭据的消息失败，retCode={}", result == null ? null : result.getRetCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("撤回含凭据的消息异常：{}", e.toString());
            return false;
        }
    }

    private static void send(Bot bot, AnyMessageEvent event, String message) {
        bot.sendMsg(event, message, false);
    }

    /**
     * 权限不足的提示。
     *
     * <p>刻意写明"什么身份不算"：这套判定与 QQ 群的平台身份无关，也不认「按群授权」的管理员，
     * 只有 {@code admin} 表里 {@code group_id} 为空的那一条（= {@code bot.admin}）能改全局配置。
     */
    private static String denyMessage() {
        return """
                你没有权限改这个配置。
                B 站 Cookie / 代理 / 开关是整个机器人共用的（影响所有群、所有订阅），\
                只允许机器人所有者修改。
                （群主 / 群管理员不算，「按群授权」的管理员也不算，私聊也不例外）
                成为所有者的方式：application.yaml（或启动参数）把 bot.admin 设成你的 QQ，\
                或手工往 admin 表加一行 qq_uid=你的QQ、group_id 留空。""";
    }

    /**
     * 所有「改全局配置」命令的统一外壳：<b>权限判定 + 异常兜底</b>。
     *
     * <p>为什么把这段抽出来（原先 7 个 handler 各写一遍，IDEA 会提示"重复的代码段"）：
     * <ol>
     *   <li><b>口径只有一份</b>：以后调权限只需改这里，不会出现"改漏了某条命令"；</li>
     *   <li><b>安全判定要 fail-closed</b>：权限不足要明确回复；<b>拿不准也按无权限处理</b>
     *       —— 所以连 {@code isBotOwner} 自己抛异常都归到"拒绝"这一支；</li>
     *   <li><b>异常不能穿出 handler</b>：原先这里一个 {@code catch} 都没有 —— 处理中一旦抛异常，
     *       消息处理链路就断了，<b>用户什么回复都收不到</b>，只能靠翻日志猜。</li>
     * </ol>
     *
     * <p>⚠️ 只包「改全局配置」这一组命令。<b>不要把只读查询类命令也套进来</b>
     * —— 例如 {@code BiliSearchPlugins} 的「搜视频 / 热搜」刻意不做权限门（只读、匿名、无副作用），
     * 套上会让群里所有人都用不了。
     *
     * @param action 真正的命令逻辑，<b>只在通过权限判定后执行</b>
     * @return 是否执行了 {@code action}（{@code false} = 权限不足，已回复拒绝消息）
     */
    private boolean guard(Bot bot, AnyMessageEvent event, Runnable action) {
        try {
            if (!botAdminChecker.isBotOwner(event)) {
                send(bot, event, denyMessage());
                return false;
            }
        } catch (Exception e) {
            // 权限判定本身炸了（例如查 admin 表出错）⇒ 按"无权限"处理，绝不放行
            log.error("B站配置命令的权限判定异常，已按拒绝处理", e);
            safeSend(bot, event, denyMessage());
            return false;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.error("处理 B站配置命令失败", e);
            safeSend(bot, event, "处理失败：" + e.getMessage());
        }
        return true;
    }

    /**
     * 回复命令结果，<b>并保证"回复失败"不会再抛出去</b>。
     *
     * <p>用在 catch 路径上：那时进程已经处于"出过错"的状态，如果连发消息也失败
     * （连接断了 / 被禁言），异常会从 {@link #guard} 的 catch 里二次逃逸。
     */
    private static void safeSend(Bot bot, AnyMessageEvent event, String message) {
        try {
            send(bot, event, message);
        } catch (Exception e) {
            log.debug("回复命令结果时再次异常，已忽略：{}", e.toString());
        }
    }
}
