package com.riccon.api.workflow.approval.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.approval.domain.WorkflowApprovalActor;
import com.riccon.api.workflow.approval.dto.WorkflowApprovalPageResponse;
import com.riccon.api.workflow.approval.dto.WorkflowApprovalResponse;
import com.riccon.api.workflow.approval.service.WorkflowApprovalRequestParser;
import com.riccon.api.workflow.approval.service.WorkflowApprovalService;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 工作流审批接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>端点：待办 {@code GET /api/v1/workflow-approvals}、详情
 * {@code GET /api/v1/workflow-approvals/{workflowApprovalId}}、决定
 * {@code POST /api/v1/workflow-approvals/{workflowApprovalId}/decision}。三者都要求
 * {@code workflow.approve}；服务层再做 assignee、自批与范围判定（ADR-0024 第 5.2/6.1 节）。
 *
 * <p>平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器前短路；跨租户 404 由租户作用域
 * 查询保证。决定请求体使用 {@link JsonNode} 交给解析器做字段白名单，确保契约
 * {@code additionalProperties: false} 生效并返回 400 {@code RICCON-WF-400}。
 *
 * <p>{@code Idempotency-Key} 必填（8–128 可打印 ASCII）。M06 不持久化该键；重复/并发决定由审批行条件
 * 更新返回 409，不重放既有响应（ADR-0024 第 5.4/7.4 节）。
 */
@RestController
public class WorkflowApprovalController {

    /** 审批服务。 */
    private final WorkflowApprovalService service;

    /** 决定请求解析与幂等键校验。 */
    private final WorkflowApprovalRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建审批控制器。
     *
     * @param service 审批服务
     * @param parser 决定请求解析器
     * @param authService 认证服务
     */
    public WorkflowApprovalController(
            WorkflowApprovalService service, WorkflowApprovalRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 读取当前用户的审批待办。
     *
     * @param status 可选状态过滤；只有 {@code PENDING} 会返回待办
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 审批分页
     */
    @GetMapping("/api/v1/workflow-approvals")
    @RequirePermission(PermissionCodes.WORKFLOW_APPROVE)
    public WorkflowApprovalPageResponse list(
            @RequestParam(required = false) WorkflowApprovalStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        return service.list(actor(authentication), status, PageQuery.of(page, size));
    }

    /**
     * 读取审批详情。
     *
     * @param workflowApprovalId 审批 ID
     * @param authentication 当前认证
     * @return 审批详情
     */
    @GetMapping("/api/v1/workflow-approvals/{workflowApprovalId}")
    @RequirePermission(PermissionCodes.WORKFLOW_APPROVE)
    public WorkflowApprovalResponse get(@PathVariable UUID workflowApprovalId, Authentication authentication) {
        return service.get(actor(authentication), workflowApprovalId);
    }

    /**
     * 批准或拒绝审批。
     *
     * @param workflowApprovalId 审批 ID
     * @param body 决定请求
     * @param idempotencyKey 幂等键（必填）
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已决定的审批
     */
    @PostMapping("/api/v1/workflow-approvals/{workflowApprovalId}/decision")
    @RequirePermission(PermissionCodes.WORKFLOW_APPROVE)
    public WorkflowApprovalResponse decide(
            @PathVariable UUID workflowApprovalId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        WorkflowApprovalRequestParser.requireIdempotencyKey(idempotencyKey);
        return service.decide(
                actor(authentication),
                workflowApprovalId,
                parser.parseDecision(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 构造当前主体的审批权限快照。
     *
     * @param authentication 当前认证
     * @return 权限快照
     */
    private WorkflowApprovalActor actor(Authentication authentication) {
        RicconPrincipal principal = AuthenticationSupport.requirePrincipal(authentication);
        CurrentUser current = authService.loadCurrentUser(principal);
        return new WorkflowApprovalActor(
                principal.userId(), principal.tenantId(), current.hasPermission(PermissionCodes.WORKFLOW_APPROVE));
    }
}
