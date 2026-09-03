package com.aiopenplatform.gateway.dto;

import lombok.Data;

/** OpenAI Compatible message item. */
@Data
public class ChatMessage {
    private String role;
    private String content;
}
