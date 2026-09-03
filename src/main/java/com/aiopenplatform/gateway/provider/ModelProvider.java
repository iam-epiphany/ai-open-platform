package com.aiopenplatform.gateway.provider;

import com.aiopenplatform.gateway.dto.ChatRequest;
import com.aiopenplatform.gateway.dto.ChatStreamListener;
import com.aiopenplatform.gateway.dto.ProviderChatResponse;

/** A provider adapter keeps the public API independent of a vendor SDK. */
public interface ModelProvider {
    String providerName();

    ProviderChatResponse chat(ChatRequest request);

    /** 流式输出（SSE）：同步读上游增量并逐块回调；默认实现表示该供应商不支持流式。 */
    default void chatStream(ChatRequest request, ChatStreamListener listener) {
        throw new IllegalStateException("该模型供应商暂不支持流式输出");
    }
}
