package com.riccon.worker.model.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.InvocationStatus;
import com.riccon.worker.model.domain.ModelInvocationRecord;
import com.riccon.worker.model.persistence.ModelInvocationJdbc;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录仓储。
 *
 * <p>只暴露 STARTED 插入与终态推进；没有修改历史记录的入口，保证调用时间线可核对。
 */
@Component
public class ModelInvocationRepository {

    /** 调用记录访问。 */
    private final ModelInvocationJdbc invocationJdbc;

    /**
     * 创建调用记录仓储。
     *
     * @param invocationJdbc 调用记录访问
     */
    public ModelInvocationRepository(ModelInvocationJdbc invocationJdbc) {
        this.invocationJdbc = Objects.requireNonNull(invocationJdbc, "invocationJdbc must not be null");
    }

    /**
     * 写入 STARTED。
     *
     * @param tenantId 当前租户
     * @param record 记录
     */
    public void started(TenantId tenantId, ModelInvocationRecord record) {
        invocationJdbc.insertStarted(tenantId, record);
    }

    /**
     * 标记成功。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param modelId 最终成功模型 ID
     * @param modelKey 最终成功模型标识快照
     * @param firstTokenMs 首字延迟
     * @param durationMs 总耗时
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param attemptCount 实际尝试次数
     */
    public void succeeded(
            TenantId tenantId,
            UUID id,
            UUID modelId,
            String modelKey,
            Integer firstTokenMs,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            int attemptCount) {
        invocationJdbc.markSucceeded(
                tenantId,
                id,
                modelId,
                modelKey,
                firstTokenMs,
                durationMs,
                promptTokens,
                completionTokens,
                attemptCount);
    }

    /**
     * 标记失败或取消。
     *
     * @param tenantId 当前租户
     * @param id 调用 ID
     * @param status FAILED 或 CANCELLED
     * @param errorCode 错误码
     * @param durationMs 总耗时
     * @param firstTokenMs 首字延迟
     * @param attemptCount 实际尝试次数
     */
    public void terminated(
            TenantId tenantId,
            UUID id,
            InvocationStatus status,
            String errorCode,
            long durationMs,
            Integer firstTokenMs,
            int attemptCount) {
        invocationJdbc.markTerminated(tenantId, id, status, errorCode, durationMs, firstTokenMs, attemptCount);
    }
}
