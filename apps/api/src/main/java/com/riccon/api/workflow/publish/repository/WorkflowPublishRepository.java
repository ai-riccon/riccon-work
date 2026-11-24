package com.riccon.api.workflow.publish.repository;

import com.riccon.api.workflow.publish.domain.WorkflowPublishLockRow;
import com.riccon.api.workflow.publish.domain.WorkflowVersionInsert;
import com.riccon.api.workflow.publish.persistence.WorkflowPublishJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 发布写入仓储。
 *
 * <p>把发布事务需要的行锁、版本号计算、不可变版本插入与定义发布指针更新封装在仓储层，服务层不接触
 * {@code TenantJdbc} 或 SQL（ADR-0015）。读取草稿与发布摘要仍复用
 * {@link com.riccon.api.workflow.definition.repository.WorkflowDefinitionRepository}，避免复制映射逻辑。
 */
@Repository
public class WorkflowPublishRepository {

    /** 发布写入 SQL。 */
    private final WorkflowPublishJdbc publishJdbc;

    /**
     * 创建发布仓储。
     *
     * @param publishJdbc 发布写入 SQL
     */
    public WorkflowPublishRepository(WorkflowPublishJdbc publishJdbc) {
        this.publishJdbc = Objects.requireNonNull(publishJdbc, "publishJdbc must not be null");
    }

    /**
     * 对流程定义行加锁并读取发布指针。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 行锁快照；流程不存在时为空
     */
    public Optional<WorkflowPublishLockRow> lockDefinition(TenantId tenantId, UUID workflowId) {
        return publishJdbc.lockDefinition(tenantId, workflowId);
    }

    /**
     * 计算下一个发布版本号。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 下一个版本号
     */
    public int nextVersionNo(TenantId tenantId, UUID workflowId) {
        return publishJdbc.nextVersionNo(tenantId, workflowId);
    }

    /**
     * 插入不可变发布版本。
     *
     * @param tenantId 当前租户
     * @param insert 写入参数
     */
    public void insertVersion(TenantId tenantId, WorkflowVersionInsert insert) {
        publishJdbc.insertVersion(tenantId, insert);
    }

    /**
     * 更新当前发布版本指针。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param versionId 新发布版本 ID
     * @param versionNo 新发布版本号
     * @param updatedBy 发布人
     * @param now 更新时间
     * @return 受影响行数
     */
    public int markPublished(
            TenantId tenantId, UUID workflowId, UUID versionId, int versionNo, UUID updatedBy, Instant now) {
        return publishJdbc.markPublished(tenantId, workflowId, versionId, versionNo, updatedBy, now);
    }
}
