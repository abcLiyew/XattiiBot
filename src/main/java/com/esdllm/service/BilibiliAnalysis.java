package com.esdllm.service;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;

public interface BilibiliAnalysis {
    void bilibiliAnalysis(AnyMessageEvent event, String url, Bot bot);
}
