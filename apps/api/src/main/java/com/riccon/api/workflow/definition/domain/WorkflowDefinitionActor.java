package com.riccon.api.workflow.definition.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前操作者的流程权限快照。
 *
 * <p>由控制器在每次请求从数据库加载的 {@code CurrentUser} 构造，只在单次请求内使用。资源级判定
 * （所有者、租户管理员、发布范围）在服务层完成；{@code tenantAdmin} 以 {@code workflow.manage}
 * 权限为判据（ADR-0024 §6.1：只有 {@code TENANT_ADMIN} 持有该权限）。
 *
 * @param userId 账号 ID
 * @param tenantId 租户代码
 * @param tenantAdmin 是否租户管理员
 * @param canEdit 是否持有 {@code workflow.edit}
 * @param canPublish 是否持有 {@code workflow.publish}
 * @param canRun 是否持有 {@code workflow.run}
 */
public record WorkflowDefinitionActor(
        UUID userId, String tenantId, boolean tenantAdmin, boolean canEdit, boolean canPublish, boolean canRun) {

    /** 校验主体必填字段。 */
    public WorkflowDefinitionActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
