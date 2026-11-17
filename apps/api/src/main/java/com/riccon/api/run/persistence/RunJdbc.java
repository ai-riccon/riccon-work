package com.riccon.api.run.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunKind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent 运行持久化。
 *
 * <p>API 只创建 PENDING 运行并交给 Worker 推进；文件解析与知识库索引的运行类别决定关联字段：
 * FILE_PARSE 必须有 file_id，KNOWLEDGE_INDEX 不绑定文件。SQL 由 {@link TenantJdbc} 强制租户条件。
 */
@Component
public class RunJdbc {

    /** 插入 PENDING 运行；max_attempts 固定为 3，与 ADR-0008 的默认重试上限一致。 */
    private static final String INSERT = """
            INSERT INTO agent_run (
                tenant_id, id, kind, file_id, user_id, status, attempt, max_attempts,
                trace_id, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :kind, :fileId, :userId, 'PENDING', 1, 3,
                :traceId, :createdAt, :createdAt
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建运行持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public RunJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入 PENDING 运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param kind 运行类别
     * @param fileId 关联文件；知识库索引为 {@code null}
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void insertPending(
            TenantId tenantId, UUID runId, RunKind kind, UUID fileId, UUID userId, String traceId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(runId));
        params.put("kind", kind.name());
        params.put("fileId", fileId == null ? null : BinaryUuids.toBytes(fileId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("traceId", traceId);
        params.put("createdAt", SqlTimestamps.toDatabase(now));
        tenantJdbc.insert(tenantId, INSERT, params);
    }
}
