package com.riccon.worker.model.domain;

import com.riccon.platform.domain.model.ModelPurpose;
import java.util.UUID;

/**
 * 模型调用记录插入参数。
 *
 * @param id 调用 ID
 * @param runId 运行 ID，可为空
 * @param conversationId 会话 ID，可为空
 * @param messageId 回答消息 ID，可为空
 * @param modelId 模型 ID
 * @param modelKey 模型标识快照
 * @param purpose 用途
 * @param traceId 追踪 ID
 */
public record ModelInvocationRecord(
        UUID id,
        UUID runId,
        UUID conversationId,
        UUID messageId,
        UUID modelId,
        String modelKey,
        ModelPurpose purpose,
        String traceId) {}
