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

@Slf4j
public class BiliBiliContant {
    public static final  String Format_Error = "\"请输入正确的格式：添加订阅 房间号 [直播订阅0/1->开/关] [动态订阅0/1->开/关]\"";
    public static final String Format_Error_= "房间号格式有误";
    public static final String Added_Live = "已添加订阅这个房间了";
    public static final String Exception = "发生异常，请检查房间号或者格式是否正确";

    /** 下载图片时用的 UA：B 站图床对空 UA / 非浏览器 UA 不友好 */
    private static final String IMAGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** 图片下载超时（毫秒） */
    private static final int IMAGE_CONNECT_TIMEOUT = 5000;
    private static final int IMAGE_READ_TIMEOUT = 8000;
    /** 单张图片体积上限，防止异常响应把内存吃爆 */
    private static final int IMAGE_MAX_BYTES = 8 * 1024 * 1024;

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
