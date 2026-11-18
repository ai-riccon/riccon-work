package com.riccon.api.run.repository;

import com.riccon.api.run.domain.ModelInvocationSnapshot;
import com.riccon.api.run.persistence.ModelInvocationJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 模型调用记录仓储。
 *
 * <p>只读：调用记录由 Worker 在模型调用前后写入，API 仅在运行详情中展示。
 */
@Repository
public class ModelInvocationRepository {

    /** 调用记录 SQL。 */
    private final ModelInvocationJdbc modelInvocationJdbc;

    /**
     * 创建仓储。
     *
     * @param modelInvocationJdbc 调用记录 SQL
     */
    public ModelInvocationRepository(ModelInvocationJdbc modelInvocationJdbc) {
        this.modelInvocationJdbc = Objects.requireNonNull(modelInvocationJdbc, "modelInvocationJdbc must not be null");
    }

    /**
     * 读取运行的模型调用记录。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 调用记录列表
     */
    public List<ModelInvocationSnapshot> listByRun(TenantId tenantId, UUID runId) {
        return modelInvocationJdbc.listByRun(tenantId, runId);
    }
}
