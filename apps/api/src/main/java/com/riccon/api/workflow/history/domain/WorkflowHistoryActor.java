package com.riccon.api.workflow.history.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 运行历史读取主体。
 *
 * <p>由控制器在每次请求从数据库加载的 {@code CurrentUser} 构造，只在单次请求内使用。{@code tenantAdmin}
 * 以 {@code workflow.manage} 权限为判据（ADR-0024 §6.1：只有 {@code TENANT_ADMIN} 持有该权限），用于
 * 决定“全部运行可见 + 管理性读取审计”还是“仅发起人/流程所有者可见”。
 *
 * @param userId 账号 ID
 * @param tenantId 租户代码
 * @param tenantAdmin 是否租户管理员
 */
public record WorkflowHistoryActor(UUID userId, String tenantId, boolean tenantAdmin) {

    /** 校验必填字段。 */
    public WorkflowHistoryActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
