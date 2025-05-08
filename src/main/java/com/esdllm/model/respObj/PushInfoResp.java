package com.esdllm.model.respObj;

import lombok.Data;

@Data
public class PushInfoResp {
    private String name;
    private String roomId;
    private Boolean has;
    private Boolean livePush;
    private Boolean dynamicPush;
}
