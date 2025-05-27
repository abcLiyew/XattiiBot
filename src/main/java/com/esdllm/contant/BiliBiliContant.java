package com.esdllm.contant;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class BiliBiliContant {
    public static final  String Format_Error = "\"请输入正确的格式：添加订阅 房间号 [直播订阅0/1->开/关] [动态订阅0/1->开/关]\"";
    public static final String Format_Error_= "房间号格式有误";
    public static final String Added_Live = "已添加订阅这个房间了";
    public static final String Exception = "发生异常，请检查房间号或者格式是否正确";

    /**
     * 图片转base64
     * @param imageIO 图片
     * @return base64编码
     */
    public static String imgToBase64(BufferedImage imageIO) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imageIO, "jpg", baos);
        byte[] imageByte = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageByte);
    }
}
