package com.esdllm.botPlugins;

import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.common.BotAdminChecker;
import com.esdllm.config.LoadDSConfig;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用聊天命令管理 B 站的<b>全局出站配置</b>：登录 Cookie 与 HTTP 代理。
 *
 * <p>背景：动态推送依赖 {@code x/polymer/web-dynamic/v1/feed/space}，该端点匿名过不去
 * （不带指纹 Cookie → HTTP 412；带上匿名指纹 → 业务码 -352），必须注入真实登录 Cookie。
 * 手动改 SQLite 太重，这里给一条命令。
 *
 * <p><b>支持的命令</b>（<b>仅机器人所有者</b>可用，私聊与群聊一视同仁）：
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
 * </pre>
 *
 * <p><b>什么时候需要配代理</b>：Cookie 完全正确（日志里"出站身份"的键名齐全）却仍持续 412
 * —— 那是<b>出口 IP 被标记</b>，换出口是唯一的软件侧出路。详见
 * {@link LoadDSConfig#KEY_BILI_PROXY}。
 *
 * <p><b>权限模型</b>：这里的操作都是<b>全局</b>的（换 Cookie / 换出口会影响所有群、所有订阅），
 * 所以只认 {@code admin} 表白名单（{@code common/BotAdminChecker#isBotAdmin}）：
 * <b>群主/群管理员这类平台身份不算</b>，<b>私聊也不再默认放行</b>
 * —— 否则任何能给机器人发私信的人都能改全局凭据。
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

    /** 分隔符：空白、分号（兼容 `a=1; b=2` 与 `key:value` 混排） */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s;]+");
    /** 合法的 Cookie 键名 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{1,40}$");
    /** 至少要有其中一个键，否则认为粘贴内容不对，不覆盖已保存的 Cookie */
    private static final String[] REQUIRED_ANY = {"SESSDATA", "buvid3", "buvid4"};
    /** 单条命令的长度上限，防止异常输入 */
    private static final int MAX_LENGTH = 4000;

    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private BotAdminChecker botAdminChecker;

    /**
     * 设置 Cookie（增量合并 + 即时生效，无需重启）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SET, at = AtEnum.BOTH)
    public void setCookie(Bot bot, AnyMessageEvent event) {
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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

        String cookie = join(merged);
        try {
            loadDSConfig.updateConfig(LoadDSConfig.KEY_BILI_COOKIE, cookie);
        } catch (Exception e) {
            log.error("保存 B 站 Cookie 失败", e);
            send(bot, event, "保存失败：" + e.getMessage());
            return;
        }

        // 消息里含凭据 —— 尽量撤回
        boolean recalled = recall(bot, event);

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
     * 查看当前生效状态（不发起网络请求）。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_STATUS, at = AtEnum.BOTH)
    public void cookieStatus(Bot bot, AnyMessageEvent event) {
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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
        }
        send(bot, event, reply.toString());
    }

    /**
     * 清除 Cookie，回到只用匿名指纹。
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_CLEAR, at = AtEnum.BOTH)
    public void clearCookie(Bot bot, AnyMessageEvent event) {
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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
        if (!botAdminChecker.isBotAdmin(event)) {
            send(bot, event, denyMessage());
            return;
        }
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
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
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
     * <p>刻意写明"群主/群管理员不够"：这套判定与 QQ 群的平台身份无关，
     * 只有 {@code admin} 表白名单里的人才能改全局配置。
     */
    private static String denyMessage() {
        return "你没有权限改这个配置。\n"
                + "B 站 Cookie / 代理会影响整个机器人（动态推送），只允许 admin 表里的管理员修改。\n"
                + "（群主 / 群管理员身份不算，私聊也不例外）\n"
                + "加人方式：application.yaml 里把 bot.admin 设成你的 QQ，"
                + "或手工往 admin 表加一行 qq_uid=你的QQ。";
    }
}
