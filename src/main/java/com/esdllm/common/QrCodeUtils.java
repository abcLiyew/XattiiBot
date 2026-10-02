package com.esdllm.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码渲染：把一段文本画成可扫的二维码，并转成 QQ 消息能用的 base64。
 *
 * <p><b>为什么需要它</b>：{@code bilibili-api} 的 {@code Login} 门面职责止于"拿到二维码内容"
 * （{@code QrCodeLogin.getUrl()}），把它画成图是<b>调用方的事</b>；上游把 zxing 限定在
 * {@code scope=test}（不向下游传递），所以本工程自己引。
 *
 * <p><b>参数口径直接沿用上游 {@code QrImages} 的实测结论</b>（那是在真机上验证过能扫出来的）：
 * <ul>
 *   <li>边长 600：B 站登录二维码内容约 100 字符，M 级纠错下版本约 7（45 个模块），
 *       加静默区后单模块仍有 12px 左右，远高于识别阈值；</li>
 *   <li>静默区 2 个模块：QR 规范推荐 4，但图片本身是独立白底、屏幕上已有留白，
 *       再少会让部分扫码器找不到定位图案（<b>表现为随机失败，不报错</b>）；</li>
 *   <li><b>刻意不设 {@code CHARACTER_SET}</b>：B 站登录二维码是纯 ASCII 地址，
 *       默认的 ISO-8859-1 兼容性最好 —— 显式设 UTF-8 时部分扫码器会因 ECI 头处理差异识别失败；</li>
 *   <li>纯黑白两色（{@code 0xFF000000} / {@code 0xFFFFFFFF}）：避免灰度抖动影响识别。</li>
 * </ul>
 *
 * <p><b>🔴 必须是 PNG，不能用 JPEG</b>：JPEG 是有损压缩，会在二维码的黑白边缘产生振铃，
 * 直接降低识别率。本工程既有的 {@code BiliBiliContant.imgToBase64} 用的是 jpg（那是给
 * Java2D 动态长图的，长图不怕有损），<b>不要复用它来发二维码</b>。
 */
public final class QrCodeUtils {

    /** 默认图片边长（像素） */
    public static final int DEFAULT_SIZE = 600;

    /** 静默区宽度（模块数） */
    private static final int QUIET_ZONE_MODULES = 2;

    private QrCodeUtils() {
    }

    /**
     * 把内容渲染成二维码，返回<b>PNG</b>的 base64（不带 {@code data:} 前缀）。
     *
     * @param content 二维码内容
     * @return base64 字符串，可直接用于 {@code MsgUtils.builder().img("base64://" + ...)}
     * @throws IOException 编码或写图失败
     */
    public static String renderBase64(String content) throws IOException {
        return renderBase64(content, DEFAULT_SIZE);
    }

    /**
     * 把内容渲染成指定边长的二维码，返回 PNG 的 base64。
     *
     * @param content 二维码内容
     * @param size    图片边长（像素）
     * @return base64 字符串
     * @throws IOException 编码或写图失败
     */
    public static String renderBase64(String content, int size) throws IOException {
        BufferedImage image = render(content, size);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ★ "png" 不能改成 "jpg"，理由见类注释
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 把内容渲染成黑白位图（不落盘）。
     *
     * @param content 二维码内容
     * @param size    图片边长（像素）
     * @return 仅含纯黑与纯白的 RGB 图
     * @throws IOException 编码失败
     */
    public static BufferedImage render(String content, int size) throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // M 级纠错：屏幕扫码足够稳，且比 H 级省容量 —— 版本更低 = 单模块更大 = 更好扫
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);

        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException e) {
            throw new IOException("二维码生成失败：" + e.getMessage(), e);
        }

        // 不用 zxing 的 MatrixToImageWriter（那在 javase 包里）：自己写 10 行就够，
        // 少引一个 jar 就少一处离线解析风险。
        BufferedImage image = new BufferedImage(
                matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }
}
