package com.riccon.worker.model.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.model.domain.ModelAttemptStatus;
import com.riccon.worker.model.persistence.ModelAttemptJdbc;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型调用尝试仓储。
 *
 * <p>只暴露开始与终态推进；行不可修改或删除，保证尝试数与审计/指标可核对。
 */
@Component
public class ModelAttemptRepository {

    /** 尝试明细访问。 */
    private final ModelAttemptJdbc attemptJdbc;

    /**
     * 创建尝试仓储。
     *
     * @param attemptJdbc 尝试明细访问
     */
    public ModelAttemptRepository(ModelAttemptJdbc attemptJdbc) {
        this.attemptJdbc = Objects.requireNonNull(attemptJdbc, "attemptJdbc must not be null");
    }

    /**
     * 写入 STARTED。
     *
     * @param tenantId 请求租户
     * @param invocationId 调用 ID
     * @param attemptNo 尝试序号
     * @param modelId 模型 ID
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID，可为空
     */
    public void started(
            TenantId tenantId, UUID invocationId, int attemptNo, UUID modelId, UUID providerId, UUID credentialId) {
        attemptJdbc.insertStarted(tenantId, invocationId, attemptNo, modelId, providerId, credentialId);
    }

    /**
     * 推进终态。
     *
     * @param tenantId 请求租户
     * @param invocationId 调用 ID
     * @param attemptNo 尝试序号
     * @param status 终态
     * @param errorCode 错误码
     * @param firstTokenMs 首字延迟
     */
    public void finished(
            TenantId tenantId,
            UUID invocationId,
            int attemptNo,
            ModelAttemptStatus status,
            String errorCode,
            Integer firstTokenMs) {
        attemptJdbc.finish(tenantId, invocationId, attemptNo, status, errorCode, firstTokenMs);
    }
}
