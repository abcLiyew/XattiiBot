package com.esdllm.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cookie 串的解析与合并（键值对 ↔ {@code "k=v; k=v"}）。
 *
 * <p><b>为什么单独抽出来</b>：Cookie 在<b>两处</b>被写进 {@code config.biliCookie} ——
 * 手工命令（{@code BiliConfigPlugins}）和扫码登录（{@code BiliLoginPlugins}）。
 * 两边必须用同一套解析口径，否则"扫码登录把手工配的 buvid 冲掉"这类问题会很难查。
 * （{@code BiliConfigPlugins} 里那份是它的私有实现，尚未迁过来 —— 逻辑一致，
 * 后续可一并收敛到本类。）
 *
 * <p><b>安全约定</b>：本类<b>只做解析</b>，不产生任何包含值的输出；
 * {@link #keys(String)} 专供日志使用（只回键名，值一律不出）。
 */
public final class CookieUtils {

    /** 合法的 Cookie 键名（与 BiliConfigPlugins 保持同一口径） */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{1,40}$");

    private CookieUtils() {
    }

    /**
     * 把 {@code "k=v; k=v"} 拆成键值对，<b>保持原顺序</b>。
     *
     * @param cookie Cookie 串；{@code null} 返回空 Map
     * @return 保序键值对（键名不合法或格式残缺的片段被跳过）
     */
    public static Map<String, String> parse(String cookie) {
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

    /**
     * 合并：以 {@code existing} 为基础，用 {@code updates} 覆盖同名的键。
     *
     * <p>语义是"<b>只更新给出的键，其余沿用</b>" —— 这样扫码登录得到的新 {@code SESSDATA}
     * 不会把手工配的 {@code buvid3/buvid4} 冲掉（缺 buvid 时库每次都要额外领一次匿名指纹）。
     *
     * @param existing 已有 Cookie 串，可为 {@code null}
     * @param updates  新键值对
     * @return 合并后的 Cookie 串
     */
    public static String merge(String existing, Map<String, String> updates) {
        Map<String, String> merged = parse(existing);
        if (updates != null) {
            merged.putAll(updates);
        }
        return join(merged);
    }

    /**
     * 拼成 {@code "k=v; k=v"}。
     *
     * @param pairs 键值对
     * @return Cookie 串
     */
    public static String join(Map<String, String> pairs) {
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
     * 取出 Cookie 里的键名（<b>专供日志</b>，绝不含值）。
     *
     * @param cookie Cookie 串
     * @return 逗号分隔的键名；无键时返回空串
     */
    public static String keys(String cookie) {
        return String.join(",", parse(cookie).keySet());
    }
}
