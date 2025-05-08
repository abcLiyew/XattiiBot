package com.esdllm.service;

import com.esdllm.model.PushInfo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.esdllm.model.respObj.PushInfoResp;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;

import java.io.IOException;

/**
* @author LiYehe
* @description 针对表【push_info】的数据库操作Service
* @createDate 2025-04-22 23:58:21
*/
public interface PushInfoService extends IService<PushInfo> {

    PushInfoResp pushAdd(Long roomId,AnyMessageEvent event);
    PushInfoResp pushAdd(Long roomId,AnyMessageEvent event, Integer livePush, Integer dynamicPush);

    boolean pushDel(AnyMessageEvent event);
    boolean pushDel(AnyMessageEvent event,Long roomId);

    void livePush(Bot bot) ;

    void dynamicPush(Bot bot);
}
