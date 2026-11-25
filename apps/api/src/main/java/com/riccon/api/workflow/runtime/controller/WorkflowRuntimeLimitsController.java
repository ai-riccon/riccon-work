package com.riccon.api.workflow.runtime.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.workflow.runtime.dto.WorkflowRuntimeLimitsResponse;
import com.riccon.api.workflow.runtime.service.WorkflowRuntimeLimitsService;
import com.riccon.platform.domain.identity.PermissionCodes;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排运行限制只读接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>路径 {@code GET /api/v1/workflows/runtime-limits} 是字面路径，优先于
 * {@code /api/v1/workflows/{workflowId}}；只要求 {@code workflow.read}，不要求编辑/发布/运行权限。
 * 平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器之前短路（ADR-0024 §6.1）。
 *
 * <p>契约只定义 GET：租户管理员只能查看部署配置，不能通过本端点修改限制（OQ-M06-16）。
 */
@RestController
public class WorkflowRuntimeLimitsController {

    /** 运行限制服务。 */
    private final WorkflowRuntimeLimitsService service;

    /**
     * 创建控制器。
     *
     * @param service 运行限制服务
     */
    public WorkflowRuntimeLimitsController(WorkflowRuntimeLimitsService service) {
        this.service = service;
    }

    /**
     * 读取当前租户的编排运行限制。
     *
     * @param authentication 当前认证
     * @return 只读限制
     */
    @GetMapping("/api/v1/workflows/runtime-limits")
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowRuntimeLimitsResponse get(Authentication authentication) {
        RicconPrincipal principal = AuthenticationSupport.requirePrincipal(authentication);
        return service.current(principal.tenantId());
    }
}
