package com.riccon.api.research.repository;

import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.api.research.persistence.RunStageJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 运行阶段时间线仓储。
 */
@Repository
public class RunStageRepository {

    /** 阶段 SQL。 */
    private final RunStageJdbc runStageJdbc;

    /**
     * 创建仓储。
     *
     * @param runStageJdbc 阶段 SQL
     */
    public RunStageRepository(RunStageJdbc runStageJdbc) {
        this.runStageJdbc = Objects.requireNonNull(runStageJdbc, "runStageJdbc must not be null");
    }

    /**
     * 读取运行时间线。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 阶段列表
     */
    public List<RunStageSnapshot> listByRun(TenantId tenantId, UUID runId) {
        return runStageJdbc.listByRun(tenantId, runId);
    }
}
