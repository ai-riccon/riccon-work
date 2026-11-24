package com.riccon.api.workflow.validation;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 发布/校验共用的运行范围校验器（ADR-0024 §7.4，F-002）。
 *
 * <p>PUBLISH 与 VALIDATE 必须同口径：租户管理员只能选择 {@code ONLY_SELF}/{@code TENANT}；流程编辑者
 * 只能选择 {@code ONLY_SELF}/{@code DEPARTMENT}，且部门必须是本人当前负责的启用部门。负责人关系只
 * 限制可发布范围，不授予编辑能力（ADR-0024 §6.2，AC-15）。
 *
 * <p>本类不写库、不写审计、不改变草稿版本；只通过 {@link WorkflowValidationSupport} 读取账号当前
 * 负责的启用部门，因此可被发布服务与校验服务复用，且不引入 service → service 的分层依赖。
 */
@Component
public class WorkflowPublishScopeValidator {

    /** 只读部门/角色查询端口。 */
    private final WorkflowValidationSupport support;

    /**
     * 创建范围校验器。
     *
     * @param support 只读部门查询端口
     */
    public WorkflowPublishScopeValidator(WorkflowValidationSupport support) {
        this.support = Objects.requireNonNull(support, "support must not be null");
    }

    /**
     * 校验范围组合并解析最终部门范围。
     *
     * <p>{@code runScope} 为空表示调用方省略范围（仅 VALIDATE 允许），此时不得携带
     * {@code departmentId}，也不查询部门；发布请求的 {@code runScope} 必填由请求解析器保证。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @param tenantAdmin 是否租户管理员
     * @param runScope 运行范围；可为空表示省略
     * @param departmentId 请求部门 ID；非 DEPARTMENT 时必须为空
     * @return 解析后的部门范围 ID；非 DEPARTMENT 或省略范围时为空
     * @throws WorkflowScopeValidationException 范围组合、角色或部门不满足发布条件
     */
    public UUID resolve(
            TenantId tenantId, UUID userId, boolean tenantAdmin, WorkflowRunScope runScope, UUID departmentId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        // 非 DEPARTMENT 范围携带部门属于参数组合非法；省略范围时同样不允许（F-002）。
        if (departmentId != null && runScope != WorkflowRunScope.DEPARTMENT) {
            throw new WorkflowScopeValidationException("departmentId is only allowed for DEPARTMENT scope");
        }
        if (runScope == null) {
            return null;
        }
        return switch (runScope) {
            case ONLY_SELF -> null;
            case TENANT -> {
                if (!tenantAdmin) {
                    throw new WorkflowScopeValidationException("TENANT scope requires tenant admin");
                }
                yield null;
            }
            case DEPARTMENT -> {
                if (tenantAdmin) {
                    throw new WorkflowScopeValidationException(
                            "tenant admin can only publish ONLY_SELF or TENANT scope");
                }
                if (departmentId == null) {
                    throw new WorkflowScopeValidationException("departmentId is required for DEPARTMENT scope");
                }
                UUID managedDepartmentId =
                        support.findActiveManagedDepartmentId(tenantId, userId).orElse(null);
                if (!departmentId.equals(managedDepartmentId)) {
                    throw new WorkflowScopeValidationException(
                            "departmentId must be an active department managed by the publisher");
                }
                yield departmentId;
            }
        };
    }
}
