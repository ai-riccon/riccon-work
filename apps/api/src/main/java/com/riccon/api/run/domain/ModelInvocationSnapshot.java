package com.riccon.api.run.domain;

import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 模型调用记录快照。
 *
 * <p>用于 J1 会话运行详情展示模型标识、起止时间、token 与结果状态。记录不包含提示词、回答原文或凭据；
 * 模型标识使用调用时的 {@code model_key_snapshot}，避免目录改键后历史记录失真。
 *
 * @param id 调用 ID
 * @param modelId 模型 ID
 * @param modelKey 调用时的模型标识快照
 * @param purpose 调用用途
 * @param status 调用状态
 * @param firstTokenMs 首字延迟（毫秒）
 * @param durationMs 总耗时（毫秒）
 * @param promptTokens 输入 token
 * @param completionTokens 输出 token
 * @param errorCode 失败错误码
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 */
public record ModelInvocationSnapshot(
        UUID id,
        UUID modelId,
        String modelKey,
        ModelPurpose purpose,
        InvocationStatus status,
        Integer firstTokenMs,
        Long durationMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorCode,
        Instant startedAt,
        Instant finishedAt) {

    /** 校验必填字段。 */
    public ModelInvocationSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(modelKey, "modelKey must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
