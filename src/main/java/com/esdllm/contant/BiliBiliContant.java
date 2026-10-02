package com.esdllm.contant;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.regex.Pattern;

@Slf4j
public class BiliBiliContant {
    public static final  String Format_Error = "\"请输入正确的格式：添加订阅 房间号 [直播订阅0/1->开/关] [动态订阅0/1->开/关]\"";
    public static final String Format_Error_= "房间号格式有误";
    public static final String Added_Live = "已添加订阅这个房间了";
    public static final String Exception = "发生异常，请检查房间号或者格式是否正确";

    /** CQ 码的起始前缀（大小写不敏感）。只此一种，见 {@link #escapeCq(String)} */
    private static final Pattern CQ_PREFIX = Pattern.compile("(?i)\\[cq:");

    /** 下载图片时用的 UA：B 站图床对空 UA / 非浏览器 UA 不友好 */
    private static final String IMAGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** 图片下载超时（毫秒） */
    private static final int IMAGE_CONNECT_TIMEOUT = 5000;
    private static final int IMAGE_READ_TIMEOUT = 8000;
    /** 单张图片体积上限，防止异常响应把内存吃爆 */
    private static final int IMAGE_MAX_BYTES = 8 * 1024 * 1024;

    /**
     * 把<b>第三方文本</b>里可能被当成 CQ 码的片段打残，供拼进将要发送的消息。
     *
     * <p><b>为什么必须做</b>：本项目的发送路径是 {@code bot.sendMsg(event, msg, false)} ——
     * 第三个参数 {@code autoEscape=false} 表示 <b>NapCat 会解析消息串里的 CQ 码</b>
     * （这正是 {@code MsgUtils#img} 能出图的原因）。而视频标题、评论正文、粉丝牌名这些都是
     * <b>别人可控的文本</b>：一旦原样拼进去，对方只要写成 {@code [CQ:at,qq=all]}
     * 就能让我们替他 @全体成员，或塞进任意图片 / 链接。
     *
     * <p><b>只打残 {@code [CQ:} 这个前缀，不碰其它方括号</b>。这里踩过一次坑：
     * 第一版把<b>所有</b> {@code [} 换成全角，结果 B 站评论里遍地都是的表情文本
     * {@code [doge]} 全变成了 {@code ［doge]} —— 防注入是做到了，但发出去的观感像坏了。
     * 而 NapCat 只认 {@code [CQ:} 这一个前缀（大小写不敏感），所以只拦它就够，
     * 其余方括号（表情、颜文字、引用）原样保留。
     *
     * <p>⚠️ 这条结论依赖"NapCat 的 CQ 语法只有 {@code [CQ:} 一种开头"这一前提。
     * 若将来上游支持了别的标记，这里要跟着扩。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 转义后的文本；入参为 {@code null} 时原样返回
     */
    public static String escapeCq(String text) {
        if (text == null || text.isEmpty() || text.indexOf('[') < 0) {
            return text;
        }
        return CQ_PREFIX.matcher(text).replaceAll("［CQ:");
    }

    /**
     * 图片转base64
     * @param imageIO 图片
     * @return base64编码
     */
    public static String imgToBase64(BufferedImage imageIO) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // 注意：Java2D 出图是 TYPE_INT_RGB（无 alpha），写 jpg 安全；
        // 若哪天换成带 alpha 的图，必须改 png，否则 JPEG 编码器会把透明通道压成异色
        ImageIO.write(imageIO, "jpg", baos);
        byte[] imageByte = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageByte);
    }

    /**
     * 网络图片下载后转 base64（供 QQ 消息内嵌）。
     *
     * <p><b>为什么不直接把 URL 丢给 NapCat</b>：B 站图床（{@code i0/i2.hdslb.com}）对不带
     * {@code Referer} 的请求常返回 403，而 NapCat 那侧的网络环境我们控制不了；
     * 本地下载 + 带上 Referer 再转 base64，等于把失败面收窄到自己这一侧。
     *
     * <p>失败（网络异常 / 非 2xx / 不是图片 / 体积超限）一律返回 {@code null} 并记日志，
     * 由调用方决定降级（例如只发文字），<b>不抛异常打断整条推送</b>。
     *
     * @param url 图片地址
     * @return base64 字符串；失败返回 {@code null}
     */
    public static String urlToBase64(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(IMAGE_CONNECT_TIMEOUT);
            conn.setReadTimeout(IMAGE_READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", IMAGE_USER_AGENT);
            // B 站图床的防盗链：不带 Referer 大概率 403
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("下载图片失败，HTTP {}，url={}", code, url);
                return null;
            }
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                log.warn("下载图片失败，响应不是图片（Content-Type={}），url={}", contentType, url);
                return null;
            }

            byte[] bytes = readAll(conn.getInputStream(), IMAGE_MAX_BYTES);
            if (bytes == null) {
                log.warn("下载图片失败，内容超过 {} 字节上限，url={}", IMAGE_MAX_BYTES, url);
                return null;
            }
            if (bytes.length == 0) {
                log.warn("下载图片失败，内容为空，url={}", url);
                return null;
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("下载图片异常，url={}，原因：{}", url, e.toString());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读取输入流全部字节，超过上限则放弃（返回 {@code null}）。
     */
    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (buffer.size() + read > maxBytes) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
