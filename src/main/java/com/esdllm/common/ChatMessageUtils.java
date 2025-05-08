package com.esdllm.common;

import com.esdllm.model.aichat.DeepSeekReq;

public class ChatMessageUtils {

    private final DeepSeekReq.Message message = new DeepSeekReq.Message();

    public static ChatMessageUtils builder(){
        return new ChatMessageUtils();
    }
    public ChatMessageUtils role(String role){
        message.setRole(role);
        return this;
    }
    public ChatMessageUtils text(String text){
        message.setContent(text);
        return this;
    }
    public ChatMessageUtils reasoningText(String reasoningText){
        message.setReasoning_content(reasoningText);
        return this;
    }
    public DeepSeekReq.Message build(){
        return message;
    }
}
