package com.riccon.api.workflow.version.repository;

import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.api.workflow.version.persistence.WorkflowVersionJdbc;
import com.riccon.api.workflow.version.persistence.WorkflowVersionRow;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 版本历史与生命周期仓储。
 *
 * <p>把版本行转换为业务快照，并封装状态/所有权条件更新；服务层不接触 {@code TenantJdbc} 或 SQL
 * （ADR-0015 分层）。版本行本身不可变，只有流程定义的 {@code status}/{@code owner_user_id} 可变。
 */
@Repository
public class WorkflowVersionRepository {

    /** 版本 SQL。 */
    private final WorkflowVersionJdbc versionJdbc;

    /**
     * 创建版本仓储。
     *
     * @param versionJdbc 版本 SQL
     */
    public WorkflowVersionRepository(WorkflowVersionJdbc versionJdbc) {
        this.versionJdbc = Objects.requireNonNull(versionJdbc, "versionJdbc must not be null");
    }

    /**
     * 分页列出版本。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 发布版本快照
     */
    public List<WorkflowPublishedVersionSnapshot> list(TenantId tenantId, UUID workflowId, int limit, int offset) {
        return versionJdbc.listVersions(tenantId, workflowId, limit, offset).stream()
                .map(WorkflowVersionRepository::toSnapshot)
                .toList();
    }

    /**
     * 统计流程版本数。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 版本数
     */
    public long count(TenantId tenantId, UUID workflowId) {
        return versionJdbc.countVersions(tenantId, workflowId);
    }

    /**
     * 条件更新流程状态。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param expectedStatus 期望的当前状态
     * @param expectedOwnerUserId 期望的当前所有者
     * @param status 目标状态
     * @param updatedBy 操作者
     * @param now 更新时间
     * @return 受影响行数
     */
    public int updateStatus(
            TenantId tenantId,
            UUID workflowId,
            WorkflowDefinitionStatus expectedStatus,
            UUID expectedOwnerUserId,
            WorkflowDefinitionStatus status,
            UUID updatedBy,
            Instant now) {
        return versionJdbc.updateStatus(
                tenantId, workflowId, expectedStatus, expectedOwnerUserId, status, updatedBy, now);
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
     * @return 受影响行数
     */
    public int updateOwner(
            TenantId tenantId,
            UUID workflowId,
            UUID expectedOwnerUserId,
            UUID ownerUserId,
            UUID updatedBy,
            Instant now) {
        return versionJdbc.updateOwner(tenantId, workflowId, expectedOwnerUserId, ownerUserId, updatedBy, now);
    }

    /**
     * 把持久化行转换为业务快照。
     *
     * @param row 版本行
     * @return 发布版本快照
     */
    private static WorkflowPublishedVersionSnapshot toSnapshot(WorkflowVersionRow row) {
        return new WorkflowPublishedVersionSnapshot(
                row.id(),
                row.workflowId(),
                row.versionNo(),
                row.runScope(),
                row.departmentId(),
                row.graphSha256(),
                row.nodeCount(),
                row.publishedBy(),
                row.publishedAt());
    }
}
