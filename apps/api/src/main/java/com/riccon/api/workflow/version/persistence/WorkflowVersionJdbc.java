package com.riccon.api.workflow.version.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 版本列表、停用/启用与所有权移交的租户 SQL。
 *
 * <p>版本表只读：这里没有 {@code workflow_version} 的 UPDATE/DELETE；V15 触发器同时拒绝运行时修改
 * （ADR-0024 §3.3.2，TM-73）。状态与所有权变更使用条件更新（{@code WHERE status/owner_user_id = 期望值}），
 * 受影响行数为 0 即并发冲突，由服务层映射 409 {@code RICCON-WF-409}。
 */
@Component
public class WorkflowVersionJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建版本 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowVersionJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 分页列出版本（版本号倒序）。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 版本文本行
     */
    public List<WorkflowVersionRow> listVersions(TenantId tenantId, UUID workflowId, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", BinaryUuids.toBytes(workflowId));
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, """
                SELECT v.id, v.workflow_id, v.version_no, v.run_scope, v.department_id,
                       v.graph_sha256, v.node_count, v.published_by, v.published_at
                  FROM workflow_version v
                 WHERE v.tenant_id = :tenantId AND v.workflow_id = :workflowId
                 ORDER BY v.version_no DESC, v.id DESC
                 LIMIT :limit OFFSET :offset
                """, params, WorkflowVersionJdbc::mapRow);
    }

    /**
     * 统计流程版本数。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 版本数
     */
    public long countVersions(TenantId tenantId, UUID workflowId) {
        return tenantJdbc.count(tenantId, """
                SELECT COUNT(*)
                  FROM workflow_version v
                 WHERE v.tenant_id = :tenantId AND v.workflow_id = :workflowId
                """, Map.of("workflowId", BinaryUuids.toBytes(workflowId)));
    }

    /**
     * 条件更新流程状态。
     *
     * <p>同时校验当前所有者，避免“读取时仍是所有者、提交前已被移交”的窗口让原所有者停用/启用他人流程；
     * 条件未命中返回 0，由服务层映射 409。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param expectedStatus 期望的当前状态
     * @param expectedOwnerUserId 期望的当前所有者
     * @param status 目标状态
     * @param updatedBy 操作者
     * @param now 更新时间
     * @return 受影响行数；0 表示状态或所有者已变化
     */
    public int updateStatus(
            TenantId tenantId,
            UUID workflowId,
            WorkflowDefinitionStatus expectedStatus,
            UUID expectedOwnerUserId,
            WorkflowDefinitionStatus status,
            UUID updatedBy,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", BinaryUuids.toBytes(workflowId));
        params.put("expectedStatus", expectedStatus.name());
        params.put("expectedOwnerUserId", BinaryUuids.toBytes(expectedOwnerUserId));
        params.put("status", status.name());
        params.put("updatedBy", BinaryUuids.toBytes(updatedBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE workflow_definition
                   SET status = :status,
                       updated_by = :updatedBy,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :workflowId
                   AND status = :expectedStatus
                   AND owner_user_id = :expectedOwnerUserId
                """, params);
    }

    /**
     * 条件更新流程所有者。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param expectedOwnerUserId 期望的当前所有者
     * @param ownerUserId 目标所有者
     * @param updatedBy 操作者
     * @param now 更新时间
     * @return 受影响行数；0 表示所有者已变化
     */
    public int updateOwner(
            TenantId tenantId,
            UUID workflowId,
            UUID expectedOwnerUserId,
            UUID ownerUserId,
            UUID updatedBy,
            Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", BinaryUuids.toBytes(workflowId));
        params.put("expectedOwnerUserId", BinaryUuids.toBytes(expectedOwnerUserId));
        params.put("ownerUserId", BinaryUuids.toBytes(ownerUserId));
        params.put("updatedBy", BinaryUuids.toBytes(updatedBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE workflow_definition
                   SET owner_user_id = :ownerUserId,
                       updated_by = :updatedBy,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :workflowId
                   AND owner_user_id = :expectedOwnerUserId
                """, params);
    }

    /**
     * 映射版本行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 版本行
     * @throws SQLException 读取失败
     */
    private static WorkflowVersionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowVersionRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                rs.getInt("version_no"),
                WorkflowRunScope.valueOf(rs.getString("run_scope")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")),
                rs.getString("graph_sha256"),
                rs.getInt("node_count"),
                BinaryUuids.fromBytes(rs.getBytes("published_by")),
                SqlTimestamps.toInstant(rs.getObject("published_at", LocalDateTime.class)));
    }
}
