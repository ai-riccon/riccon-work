package com.riccon.api.workflow.validation;

import com.riccon.platform.common.tenant.TenantId;
import java.util.Objects;
import java.util.UUID;

/**
 * 图校验请求上下文。
 *
 * <p>{@code tenantId} 用于模型/工具授权与审批人解析；{@code actingUserId} 是当前操作者，发布校验用它排除
 * 自批（ADR-0024 §5.2）。校验器只读这些信息，不写审计、不改变草稿版本。
 *
 * @param tenantId 当前租户
 * @param actingUserId 保存/发布/运行发起人；用于排除自批
 * @param scope 校验阶段
 */
public record WorkflowValidationRequest(TenantId tenantId, UUID actingUserId, WorkflowValidationScope scope) {

    /** 校验必填字段。 */
    public WorkflowValidationRequest {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actingUserId, "actingUserId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
    }

    /**
     * 构造保存阶段请求。
     *
     * @param tenantId 当前租户
     * @param actingUserId 保存人
     * @return 校验请求
     */
    public static WorkflowValidationRequest save(TenantId tenantId, UUID actingUserId) {
        return new WorkflowValidationRequest(tenantId, actingUserId, WorkflowValidationScope.SAVE);
    }

    /**
     * 构造发布阶段请求。
     *
     * @param tenantId 当前租户
     * @param publisherUserId 发布人；审批人解析时排除
     * @return 校验请求
     */
    public static WorkflowValidationRequest publish(TenantId tenantId, UUID publisherUserId) {
        return new WorkflowValidationRequest(tenantId, publisherUserId, WorkflowValidationScope.PUBLISH);
    }

    /**
     * 构造运行前复检请求。
     *
     * @param tenantId 当前租户
     * @param requesterUserId 运行发起人
     * @return 校验请求
     */
    public static WorkflowValidationRequest runtime(TenantId tenantId, UUID requesterUserId) {
        return new WorkflowValidationRequest(tenantId, requesterUserId, WorkflowValidationScope.RUNTIME);
    }
}
