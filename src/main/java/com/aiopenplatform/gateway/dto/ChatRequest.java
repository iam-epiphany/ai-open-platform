package com.aiopenplatform.gateway.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Request body accepted by POST /v1/chat/completions. */
@Data
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private Boolean stream;
}
