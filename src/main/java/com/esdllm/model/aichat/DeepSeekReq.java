package com.esdllm.model.aichat;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
public class DeepSeekReq {
    private Integer max_tokens;
    private Double temperature=0.7;
    private Double top_p=0.7;
    private Integer top_k=50;
    private Integer n=1;
    private Integer frequency_penalty = 0;
    private String chat_id;
    private String model;
    private Boolean stream=false;
    private Object response_format;
    private List<Object> tools;
    private List<Message> messages;

    @Data
    public static class Message{
        private String role;
        private String content;
        private String reasoning_content;
    }
}
