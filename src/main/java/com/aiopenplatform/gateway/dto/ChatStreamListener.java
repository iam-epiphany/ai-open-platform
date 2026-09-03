package com.aiopenplatform.gateway.dto;

/**
 * 流式输出回调：Provider 逐块推送内容增量，usage 仅在流终点（最后一个 chunk）到达，
 * finish 表示上游已结束（收到 [DONE] 或末 chunk 的 finish_reason）。
 */
public interface ChatStreamListener {

    /** 上游连接已建立：回调方可通过 abort 主动断开上游连接（客户端断连止损用） */
    default void onConnected(Runnable abort) {
    }

    /** 收到一段内容增量（choices[0].delta.content），可能为空 */
    void onDelta(String content);

    /** 流终点收到 usage 统计（prompt/completion tokens） */
    void onUsage(int promptTokens, int completionTokens);

    /** 上游流结束（finish_reason 或 [DONE]），此后不再回调 */
    void onFinish(String finishReason);
}
