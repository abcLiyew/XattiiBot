package com.esdllm.model.aichat;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Data
public class DeepSeekResp {
    private List<Choices> choices;
    private Integer created;
    private String id;
    private String model;
    private String object;
    private List<Object> tool_calls;
    private Usage usage;
    @Data
    public static class Choices{
        private String finish_reason;
        private DeepSeekReq.Message message;
    }
    @Data
    public static class Usage{
        private Integer completion_tokens;
        private Integer prompt_tokens;
        private Integer total_tokens;
    }
}
