package com.aiopenplatform.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Normalized response returned by a model provider. */
@Data
@AllArgsConstructor
public class ProviderChatResponse {
    private String content;
    private int promptTokens;
    private int completionTokens;
    private String finishReason;
}
