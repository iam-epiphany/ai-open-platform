package com.aiopenplatform.gateway.provider;

import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ProviderChatResponse;

/** A provider adapter keeps the public API independent of a vendor SDK. */
public interface ModelProvider {
    String providerName();

    ProviderChatResponse chat(ChatRequest request);
}
