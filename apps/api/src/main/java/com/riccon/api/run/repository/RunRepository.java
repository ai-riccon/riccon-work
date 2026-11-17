package com.riccon.api.run.repository;

import com.riccon.api.run.persistence.RunJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Agent 运行仓储。
 *
 * <p>当前只提供“创建 PENDING 运行”；状态推进由 Worker 在租户作用域内完成。
 */
@Repository
public class RunRepository {

    /** 运行 SQL。 */
    private final RunJdbc runJdbc;

    /**
     * 创建仓储。
     *
     * @param runJdbc 运行 SQL
     */
    public RunRepository(RunJdbc runJdbc) {
        this.runJdbc = Objects.requireNonNull(runJdbc, "runJdbc must not be null");
    }

    /**
     * 创建 PENDING 运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param kind 运行类别
     * @param fileId 关联文件；知识库索引为 {@code null}
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void createPending(
            TenantId tenantId, UUID runId, RunKind kind, UUID fileId, UUID userId, String traceId, Instant now) {
        runJdbc.insertPending(tenantId, runId, kind, fileId, userId, traceId, now);
    }
}
