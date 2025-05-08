package com.esdllm.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ModelEnum {         
    GPT_3_5_TURBO("gpt-3.5-turbo"),
    GPT_3_5_TURBO_0301("gpt-3.5-turbo-0301"),
    GPT_4("gpt-4"),
    GPT_4_0314("gpt-4-0314"),
    GPT_4_32K("gpt-4-32k"),
    GPT_4_32K_0314("gpt-4-32k-0314"),
    GPT_4_1106_preview("gpt-4-1106-preview"),
    DeepSeek_R1("deepseek-ai/DeepSeek-R1"),
    DeepSeek_V3("deepseek-ai/DeepSeek-V3"),
    DeepSeek_R1_Official("deepseek-reasoner"),
    DeepSeek_V3_Official("deepseek-chat");
    private final String name;
}