package com.riccon.api.run.dto;

import com.riccon.api.run.domain.ModelInvocationSnapshot;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Instant;
import java.util.UUID;

/**
 * 模型调用记录响应，对应 OpenAPI {@code ModelInvocation}。
 *
 * <p>供 J1 运行详情展示模型标识、开始/结束时间、首字与总耗时、token 与结果状态；不包含提示词、回答原文、
 * 上游凭据或错误堆栈。
 *
 * @param id 调用 ID
 * @param modelId 模型 ID
 * @param modelKey 模型标识快照
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
public record ModelInvocationResponse(
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

    /**
     * 将调用记录快照映射为响应。
     *
     * @param invocation 调用记录
     * @return 响应
     */
    public static ModelInvocationResponse from(ModelInvocationSnapshot invocation) {
        return new ModelInvocationResponse(
                invocation.id(),
                invocation.modelId(),
                invocation.modelKey(),
                invocation.purpose(),
                invocation.status(),
                invocation.firstTokenMs(),
                invocation.durationMs(),
                invocation.promptTokens(),
                invocation.completionTokens(),
                invocation.errorCode(),
                invocation.startedAt(),
                invocation.finishedAt());
    }
}
