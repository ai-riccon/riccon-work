package com.riccon.api.workflow.definition.repository;

import com.riccon.api.workflow.definition.domain.WorkflowDefinitionCreation;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSourceType;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionView;
import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.api.workflow.definition.domain.WorkflowTemplateSnapshot;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.persistence.WorkflowDefinitionInsert;
import com.riccon.api.workflow.persistence.WorkflowDefinitionJdbc;
import com.riccon.api.workflow.persistence.WorkflowDefinitionRow;
import com.riccon.api.workflow.persistence.WorkflowDefinitionTemplateJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 流程定义仓储。
 *
 * <p>把持久化行转换为业务快照，并把模板复制所需的全局目录读取封装在仓储层；服务层只依赖本类，
 * 不接触 {@code JdbcClient} 或租户 SQL（ADR-0015，ArchUnit 分层）。
 */
@Repository
public class WorkflowDefinitionRepository {

    /** 流程定义 SQL。 */
    private final WorkflowDefinitionJdbc definitionJdbc;

    /** 内置模板只读 SQL。 */
    private final WorkflowDefinitionTemplateJdbc templateJdbc;

    /**
     * 创建仓储。
     *
     * @param definitionJdbc 流程定义 SQL
     * @param templateJdbc 内置模板只读 SQL
     */
    public WorkflowDefinitionRepository(
            WorkflowDefinitionJdbc definitionJdbc, WorkflowDefinitionTemplateJdbc templateJdbc) {
        this.definitionJdbc = Objects.requireNonNull(definitionJdbc, "definitionJdbc must not be null");
        this.templateJdbc = Objects.requireNonNull(templateJdbc, "templateJdbc must not be null");
    }

    /**
     * 插入新草稿。
     *
     * @param tenantId 当前租户
     * @param creation 新建命令
     */
    public void insert(TenantId tenantId, WorkflowDefinitionCreation creation) {
        definitionJdbc.insert(
                tenantId,
                new WorkflowDefinitionInsert(
                        creation.id(),
                        creation.name(),
                        creation.description(),
                        creation.ownerUserId(),
                        creation.graph(),
                        creation.sourceTemplateId(),
                        creation.sourceTemplateType(),
                        creation.sourceTemplateVersion(),
                        creation.actorId(),
                        creation.now()));
    }

    /**
     * 按 ID 读取流程定义。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 流程快照
     */
    public Optional<WorkflowDefinitionSnapshot> findById(TenantId tenantId, UUID workflowId) {
        return definitionJdbc.findById(tenantId, workflowId).map(WorkflowDefinitionRepository::toSnapshot);
    }

    /**
     * 分页查询可见流程定义。
     *
     * @param tenantId 当前租户
     * @param view 列表视图
     * @param status 可选状态过滤
     * @param userId 当前账号
     * @param departmentId 当前账号主部门
     * @param tenantAdmin 是否租户管理员
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 流程快照
     */
    public List<WorkflowDefinitionSnapshot> list(
            TenantId tenantId,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            UUID userId,
            UUID departmentId,
            boolean tenantAdmin,
            int limit,
            int offset) {
        return definitionJdbc.list(tenantId, view, status, userId, departmentId, tenantAdmin, limit, offset).stream()
                .map(WorkflowDefinitionRepository::toSnapshot)
                .toList();
    }

    /**
     * 统计可见流程定义。
     *
     * @param tenantId 当前租户
     * @param view 列表视图
     * @param status 可选状态过滤
     * @param userId 当前账号
     * @param departmentId 当前账号主部门
     * @param tenantAdmin 是否租户管理员
     * @return 总数
     */
    public long count(
            TenantId tenantId,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            UUID userId,
            UUID departmentId,
            boolean tenantAdmin) {
        return definitionJdbc.count(tenantId, view, status, userId, departmentId, tenantAdmin);
    }

    /**
     * 按乐观锁更新草稿。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param expectedVersion 客户端持有的版本
     * @param name 新名称；为空表示不修改
     * @param description 新说明
     * @param descriptionPresent 是否显式提交说明
     * @param graph 新草稿图
     * @param updatedBy 修改人
     * @param now 修改时间
     * @return 受影响行数；0 表示冲突
     */
    public int updateDraft(
            TenantId tenantId,
            UUID workflowId,
            int expectedVersion,
            String name,
            String description,
            boolean descriptionPresent,
            WorkflowGraph graph,
            UUID updatedBy,
            Instant now) {
        return definitionJdbc.updateDraft(
                tenantId, workflowId, expectedVersion, name, description, descriptionPresent, graph, updatedBy, now);
    }

    /**
     * 读取发布版本摘要。
     *
     * @param tenantId 当前租户
     * @param versionId 版本 ID
     * @return 发布版本快照
     */
    public Optional<WorkflowPublishedVersionSnapshot> findPublishedVersion(TenantId tenantId, UUID versionId) {
        return definitionJdbc
                .findPublishedVersion(tenantId, versionId)
                .map(row -> new WorkflowPublishedVersionSnapshot(
                        row.id(),
                        row.workflowId(),
                        row.versionNo(),
                        row.runScope(),
                        row.departmentId(),
                        row.graphSha256(),
                        row.nodeCount(),
                        row.publishedBy(),
                        row.publishedAt()));
    }

    /**
     * 读取可复制的内置模板。
     *
     * @param templateId 模板 ID
     * @return 模板快照
     */
    public Optional<WorkflowTemplateSnapshot> findBuiltInTemplate(UUID templateId) {
        return templateJdbc
                .findBuiltIn(templateId)
                .map(row -> new WorkflowTemplateSnapshot(
                        row.id(),
                        row.name(),
                        row.description(),
                        WorkflowGraphJson.parse(row.graphJson()),
                        row.version(),
                        WorkflowDefinitionSourceType.BUILT_IN));
    }

    /**
     * 把持久化行转换为业务快照。
     *
     * @param row 持久化行
     * @return 业务快照
     */
    private static WorkflowDefinitionSnapshot toSnapshot(WorkflowDefinitionRow row) {
        return new WorkflowDefinitionSnapshot(
                row.id(),
                row.name(),
                row.description(),
                row.ownerUserId(),
                row.draftVersion(),
                row.draftGraph(),
                row.status(),
                row.publishedVersionId(),
                row.publishedVersionNo(),
                row.publishedGraphSha256(),
                row.sourceTemplateId(),
                row.sourceTemplateType(),
                row.sourceTemplateVersion(),
                row.createdBy(),
                row.updatedBy(),
                row.createdAt(),
                row.updatedAt());
    }
}
