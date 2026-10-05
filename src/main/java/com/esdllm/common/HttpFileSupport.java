package com.esdllm.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 按 HTTP 语义把<b>本地文件</b>喂给客户端时，两个出口共用的一小组工具。
 *
 * <p><b>为什么抽出来</b>：本项目现在有两条"把录播文件发出去"的路 ——
 * 内置的 {@code RecordFileServer}（给 NapCat 下载）和 {@code RecordWebController}
 * （给群成员的浏览器播放 / 下载）。两者要处理的是同一件麻烦事：
 * <b>Range</b>。而 Range 解析是<b>安全相关</b>的代码（越界、负数、后缀写法都要正确拒绝），
 * 复制两份之后"改了一处"就会变成"一个出口能越界读、另一个不能"——这类缺陷不会报错，
 * 只会悄悄多读几字节。所以只留一份。
 *
 * <p>这里刻意<b>不</b>依赖任何 HTTP 框架类型（{@code HttpExchange} / {@code HttpServletResponse}
 * 都进不来）：调用方把已经解析好的 {@code Range} 字符串传进来、把文件流和响应流传进来，
 * 剩下的交给这两个静态方法。于是 JDK 内建的 {@code com.sun.net.httpserver} 与 Spring MVC
 * 能共用同一份实现。
 *
 * @author 饿死的流浪猫
 */
public final class HttpFileSupport {

    private HttpFileSupport() {
    }

    /** 一次 Range 解析的结果。{@code partial = false} 表示"整文件"（也是没有 Range 头时的默认）。 */
    public record ByteRange(long start, long end, boolean partial) {

        /** 本次要发的字节数 */
        public long length() {
            return end - start + 1;
        }
    }

    /**
     * 解析 {@code Range: bytes=start-end}，<b>只支持单段</b>。
     *
     * <p>支持三种写法：{@code bytes=0-99}（闭区间）、{@code bytes=500-}（到结尾）、
     * {@code bytes=-500}（<b>最后</b> 500 字节 —— 这个最容易漏，注意 {@code -} 在前时数字是"长度"而不是"起点"）。
     *
     * <p>多段（{@code bytes=0-99,200-299}）刻意<b>不支持，回整文件</b>：
     * 客户端拿到 200 会自己退化成全量下载，这是 HTTP 允许的行为。
     * 为了一个几乎不会出现的用法去实现 {@code multipart/byteranges}，成本远大于收益。
     *
     * @param header 请求头原文，可为 {@code null} / 空白 / 语法非法（一律按"整文件"处理）
     * @param total  文件总字节数
     * @return 解析结果；<b>语义非法</b>（起点越界、区间反向、数字解析不了）时返回 {@code null}，
     *         调用方应当回 {@code 416} 并带上 {@code Content-Range: bytes *}{@code /total}
     */
    public static ByteRange parseRange(String header, long total) {
        long whole = Math.max(0, total - 1);
        if (header == null || header.isBlank() || !header.startsWith("bytes=")) {
            return new ByteRange(0, whole, false);
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            // 多段：给整文件，让客户端自己退化 —— 不是错误，别回 416
            return new ByteRange(0, whole, false);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                // "-500" = 最后 500 字节
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= total) {
                return null;
            }
            end = Math.min(end, total - 1);
            if (end < start) {
                return null;
            }
            return new ByteRange(start, end, true);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按扩展名给 Content-Type。认不出来的给 {@code application/octet-stream}（浏览器会当附件下）。 */
    public static String contentType(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".flv")) {
            return "video/x-flv";
        }
        return "application/octet-stream";
    }

    /**
     * 文件名要进 HTTP 响应头（{@code Content-Disposition}），
     * 而非 ASCII 字符（中文主播名）会破坏头格式 ⇒ 只留可打印 ASCII，其余换下划线。
     *
     * <p>⚠️ 这是<b>给客户端看</b>的名字，与磁盘上的真实文件名无关，所以替换没有副作用。
     */
    public static String safeAsciiName(String name) {
        if (name == null || name.isBlank()) {
            return "record.mp4";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(c < 128 && c > 31 && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }

    /**
     * 从 {@code in} 跳过 {@code skip} 字节，再<b>精确</b>搬运 {@code length} 字节到 {@code out}。
     *
     * <p>两处刻意：
     * <ul>
     *   <li>跳过用循环而不是一次 {@code skip()} —— {@code InputStream#skip} 允许"少跳"，
     *       单次调用跳不够会导致响应内容整体错位（而错位的数据看起来照样是合法视频流，
     *       查起来极难）；</li>
     *   <li>只搬 {@code length} 字节、绝不搬运到流尾 —— 这是 Range 语义的落点，
     *       多发一字节客户端就会认为响应损坏。</li>
     * </ul>
     *
     * @return 实际搬运的字节数（正常等于 {@code length}；文件被截断时会小于它）
     */
    public static long copyRange(InputStream in, OutputStream out, long skip, long length)
            throws IOException {
        long remaining = skip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (in.read() < 0) {
                return 0;
            }
            remaining--;
        }
        byte[] buffer = new byte[1 << 16];
        long left = length;
        long written = 0;
        while (left > 0) {
            int want = (int) Math.min(buffer.length, left);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            left -= read;
            written += read;
        }
        return written;
    }
}
