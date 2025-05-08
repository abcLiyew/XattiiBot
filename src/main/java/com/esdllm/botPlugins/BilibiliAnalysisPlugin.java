package com.esdllm.botPlugins;

import com.esdllm.service.BilibiliAnalysis;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Shiro
@Component
public class  BilibiliAnalysisPlugin {

    @Resource
    private BilibiliAnalysis bilibiliAnalysis;

    @Async
    @AnyMessageHandler
    @MessageHandlerFilter(at = AtEnum.BOTH)
    public void bilibiliAnalysis(Bot bot, AnyMessageEvent event) {
        String message = event.getMessage();
        message = message.replace("\\","");
        message = message.replace("&#44;","");
        String[] strArr = message.split("\"");
        String url = "";
        for (String str : strArr) {
            int i = str.indexOf("http");
            int j = str.indexOf("www");
            if ((j>i||j ==-1)&&i!=-1) {
                str = str.substring(i);
            }else if (j!=-1){
                str = str.substring(j);
                str = "https://"+str;
            }
            str = str.trim();
            // 使用正则表达式查找空白字符及其后面的所有字符
            Pattern pattern = Pattern.compile("[\\s一-龥]|[\u3000-〿\uFF00-\uFFEF‐-‟\u3000-〿]");
            Matcher matcher = pattern.matcher(str);
            if (matcher.find()) {
                str = str.substring(0, matcher.start()); // 截取空白字符前的部分
            }
            if ((str.contains("http")||str.contains("www"))&&((str.contains("b23.tv")||str.contains("bilibili.com")))) {
                url = str.trim();
                break;
            }
        }
        if (url.isEmpty()) {
            for (String str : strArr) {
               int i = str.indexOf("BV");
               if (i!=-1) {
                   url = "https://www.bilibili.com/video/"+str.substring(i,i+12);
                   break;
               }
            }
        }
        if (url.isEmpty()) {
            return;
        }
        log.info("开始解析：{}",url);
        bilibiliAnalysis.bilibiliAnalysis(event,url,bot);
    }
}
