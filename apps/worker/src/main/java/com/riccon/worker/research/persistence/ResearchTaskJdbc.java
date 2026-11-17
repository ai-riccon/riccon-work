package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 报告任务只读访问。
 *
 * <p>Worker 只校验任务存在与主题，不修改任务状态；任务状态由 API 根据运行状态派生（M02 后续任务）。
 */
@Component
public class ResearchTaskJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建任务访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ResearchTaskJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取任务。
     *
     * @param tenantId 租户
     * @param taskId 任务 ID
     * @return 任务
     */
    public Optional<ResearchTaskRecord> find(TenantId tenantId, UUID taskId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, topic, status, mcp_tool_id, created_by
                  FROM research_task
                 WHERE tenant_id = :tenantId
                   AND id = :taskId
                """,
                Map.of("taskId", UuidBinary.toBytes(taskId)),
                (rs, rowNum) -> new ResearchTaskRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("topic"),
                        ResearchTaskStatus.valueOf(rs.getString("status")),
                        rs.getBytes("mcp_tool_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("mcp_tool_id")),
                        UuidBinary.fromBytes(rs.getBytes("created_by"))));
    }
}
