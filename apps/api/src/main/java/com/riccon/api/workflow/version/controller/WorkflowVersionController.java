package com.riccon.api.workflow.version.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionDetailResponse;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionService;
import com.riccon.api.workflow.version.dto.WorkflowVersionPageResponse;
import com.riccon.api.workflow.version.service.WorkflowVersionRequestParser;
import com.riccon.api.workflow.version.service.WorkflowVersionService;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 流程版本与生命周期接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>路径与权限：版本列表要求 {@code workflow.read}；停用/启用要求 {@code workflow.publish}；移交要求
 * {@code workflow.manage}（只有 {@code TENANT_ADMIN} 持有）。平台租户 404 由
 * {@code WorkflowPlatformTenantFilter} 在权限拦截器前短路（ADR-0024 §6.1）。
 *
 * <p>停用/启用/移交成功后复用 {@link WorkflowDefinitionService#get} 组装 {@code WorkflowDefinitionDetail}，
 * 与详情端点保持一致的能力标记，避免两套响应逻辑漂移。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowVersionController {

    /** 版本与生命周期服务。 */
    private final WorkflowVersionService service;

    /** 移交请求解析器。 */
    private final WorkflowVersionRequestParser parser;

    /** 流程定义服务，用于成功后的详情响应。 */
    private final WorkflowDefinitionService definitionService;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建版本控制器。
     *
     * @param service 版本与生命周期服务
     * @param parser 移交请求解析器
     * @param definitionService 流程定义服务
     * @param authService 认证服务
     */
    public WorkflowVersionController(
            WorkflowVersionService service,
            WorkflowVersionRequestParser parser,
            WorkflowDefinitionService definitionService,
            AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.definitionService = definitionService;
        this.authService = authService;
    }

    /**
     * 列出版本。
     *
     * @param workflowId 流程 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 版本分页
     */
    @GetMapping("/{workflowId}/versions")
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowVersionPageResponse list(
            @PathVariable UUID workflowId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        return service.list(actor(authentication), workflowId, PageQuery.of(page, size));
    }

    /**
     * 停用流程。
     *
     * @param workflowId 流程 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 停用后的流程详情
     */
    @PostMapping("/{workflowId}/disable")
    @RequirePermission(PermissionCodes.WORKFLOW_PUBLISH)
    public WorkflowDefinitionDetailResponse disable(
            @PathVariable UUID workflowId, Authentication authentication, HttpServletRequest request) {
        WorkflowDefinitionActor actor = actor(authentication);
        service.disable(actor, workflowId, RequestContext.traceId(request), RequestContext.requestId(request));
        return definitionService.get(actor, workflowId);
    }

    /**
     * 重新启用流程。
     *
     * @param workflowId 流程 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 启用后的流程详情
     */
    @PostMapping("/{workflowId}/enable")
    @RequirePermission(PermissionCodes.WORKFLOW_PUBLISH)
    public WorkflowDefinitionDetailResponse enable(
            @PathVariable UUID workflowId, Authentication authentication, HttpServletRequest request) {
        WorkflowDefinitionActor actor = actor(authentication);
        service.enable(actor, workflowId, RequestContext.traceId(request), RequestContext.requestId(request));
        return definitionService.get(actor, workflowId);
    }

    /**
     * 移交流程所有权。
     *
     * @param workflowId 流程 ID
     * @param body 移交请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 移交后的流程详情
     */
    @PostMapping("/{workflowId}/transfer")
    @RequirePermission(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowDefinitionDetailResponse transfer(
            @PathVariable UUID workflowId,
            @RequestBody JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        WorkflowDefinitionActor actor = actor(authentication);
        service.transfer(
                actor,
                workflowId,
                parser.parseTransfer(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
        return definitionService.get(actor, workflowId);
    }

    /**
     * 构造当前主体的流程权限快照。
     *
     * @param authentication 当前认证
     * @return 权限快照
     */
    private WorkflowDefinitionActor actor(Authentication authentication) {
        RicconPrincipal principal = AuthenticationSupport.requirePrincipal(authentication);
        CurrentUser current = authService.loadCurrentUser(principal);
        return new WorkflowDefinitionActor(
                principal.userId(),
                principal.tenantId(),
                current.hasPermission(PermissionCodes.WORKFLOW_MANAGE),
                current.hasPermission(PermissionCodes.WORKFLOW_EDIT),
                current.hasPermission(PermissionCodes.WORKFLOW_PUBLISH),
                current.hasPermission(PermissionCodes.WORKFLOW_RUN));
    }
}
