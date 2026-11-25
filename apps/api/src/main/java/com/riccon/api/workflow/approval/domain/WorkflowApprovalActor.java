package com.riccon.api.workflow.approval.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前操作者的审批权限快照。
 *
 * <p>由控制器在每次请求从数据库加载的 {@code CurrentUser} 构造，只在单次请求内使用；{@code canApprove}
 * 以 {@code workflow.approve} 权限为判据。资源级判定（assignee 范围、自批）在服务层完成，不能仅凭该
 * 快照放行（ADR-0024 第 5.2/6.1 节）。
 *
 * @param userId 账号 ID
 * @param tenantId 租户代码
 * @param canApprove 是否持有 {@code workflow.approve}
 */
public record WorkflowApprovalActor(UUID userId, String tenantId, boolean canApprove) {

    /** 校验主体必填字段。 */
    public WorkflowApprovalActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
