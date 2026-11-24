package com.riccon.api.workflow.run.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.run.dto.WorkflowRunResponse;
import com.riccon.api.workflow.run.service.WorkflowRunRequestParser;
import com.riccon.api.workflow.run.service.WorkflowRunService;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 工作流运行接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>端点：
 *
 * <ul>
 *   <li>{@code POST /api/v1/workflows/{workflowId}/runs}：创建运行，{@code Idempotency-Key} 必填；
 *   <li>{@code POST /api/v1/workflow-runs/{workflowRunId}/cancel}：取消运行，键可选、按状态幂等；
 *   <li>{@code POST /api/v1/workflow-runs/{workflowRunId}/retry}：重试失败节点，{@code Idempotency-Key} 必填。
 * </ul>
 *
 * <p>请求体使用 {@link JsonNode} 交给解析器执行字段白名单，确保 {@code additionalProperties: false}
 * 生效并返回 400 {@code RICCON-WF-400}，而不是被全局 Jackson 配置静默忽略。平台租户 404 由
 * {@code WorkflowPlatformTenantFilter} 在权限拦截器前短路（ADR-0024 §6.1）。
 */
@RestController
public class WorkflowRunController {

    /** 运行服务。 */
    private final WorkflowRunService service;

    /** 请求解析与白名单校验。 */
    private final WorkflowRunRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建运行控制器。
     *
     * @param service 运行服务
     * @param parser 请求解析器
     * @param authService 认证服务
     */
    public WorkflowRunController(
            WorkflowRunService service, WorkflowRunRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 创建运行。
     *
     * @param workflowId 流程 ID
     * @param body 创建请求
     * @param idempotencyKey 幂等键（必填）
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新建或同键既有的运行
     */
    @PostMapping("/api/v1/workflows/{workflowId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKFLOW_RUN)
    public WorkflowRunResponse create(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        WorkflowRunRequestParser.requireIdempotencyKey(idempotencyKey);
        return service.create(
                actor(authentication),
                workflowId,
                parser.parseCreate(body),
                idempotencyKey,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 取消运行。
     *
     * <p>不要求幂等键；已取消按状态幂等返回 202，关闭编排开关后仍可取消（ADR-0024 §4.8）。
     *
     * @param workflowRunId 运行 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.WORKFLOW_RUN)
    public void cancel(
            @PathVariable UUID workflowRunId, Authentication authentication, HttpServletRequest request) {
        service.cancel(
                actor(authentication),
                workflowRunId,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 重试失败节点。
     *
     * @param workflowRunId 运行 ID
     * @param body 重试请求（{@code nodeId} 必填）
     * @param idempotencyKey 幂等键（必填）
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission(PermissionCodes.WORKFLOW_RUN)
    public void retry(
            @PathVariable UUID workflowRunId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        WorkflowRunRequestParser.requireIdempotencyKey(idempotencyKey);
        service.retry(
                actor(authentication),
                workflowRunId,
                parser.parseRetry(body),
                idempotencyKey,
                RequestContext.traceId(request),
                RequestContext.requestId(request));
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
