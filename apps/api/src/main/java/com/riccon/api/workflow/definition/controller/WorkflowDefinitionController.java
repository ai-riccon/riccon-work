package com.riccon.api.workflow.definition.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionView;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionDetailResponse;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionPageResponse;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionRequestParser;
import com.riccon.api.workflow.definition.service.WorkflowDefinitionService;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 流程定义与草稿接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>路径与权限：列表/详情要求 {@code workflow.read}；创建、保存草稿与另存副本要求 {@code workflow.edit}。
 * 平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器之前短路（ADR-0024 §6.1，F-005）。
 *
 * <p>请求体使用 {@link JsonNode} 交给解析器做白名单校验，确保契约 {@code additionalProperties: false}
 * 生效并返回 400 {@code RICCON-WF-400}，而不是被全局 Jackson 配置静默忽略未知字段。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowDefinitionController {

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 流程定义服务。 */
    private final WorkflowDefinitionService service;

    /** 请求解析与白名单校验。 */
    private final WorkflowDefinitionRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建控制器。
     *
     * @param service 流程定义服务
     * @param parser 请求解析器
     * @param authService 认证服务
     */
    public WorkflowDefinitionController(
            WorkflowDefinitionService service, WorkflowDefinitionRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 列出可访问的流程。
     *
     * @param view 列表视图；默认 MINE
     * @param status 可选状态过滤
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 流程分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowDefinitionPageResponse list(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) WorkflowDefinitionStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        WorkflowDefinitionView resolvedView = parseView(view);
        PageQuery pageQuery = PageQuery.of(page, size);
        return service.list(actor(authentication), resolvedView, status, pageQuery);
    }

    /**
     * 创建流程草稿（空白或从模板复制）。
     *
     * @param body 请求体
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已创建草稿
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKFLOW_EDIT)
    public WorkflowDefinitionDetailResponse create(
            @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return service.create(
                actor(authentication),
                parser.parseCreate(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 读取流程详情。
     *
     * @param workflowId 流程 ID
     * @param authentication 当前认证
     * @return 流程详情
     */
    @GetMapping("/{workflowId}")
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowDefinitionDetailResponse get(@PathVariable UUID workflowId, Authentication authentication) {
        return service.get(actor(authentication), workflowId);
    }

    /**
     * 保存流程草稿。
     *
     * @param workflowId 流程 ID
     * @param body 请求体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 保存后的详情
     */
    @PutMapping("/{workflowId}/draft")
    @RequirePermission(PermissionCodes.WORKFLOW_EDIT)
    public WorkflowDefinitionDetailResponse saveDraft(
            @PathVariable UUID workflowId,
            @RequestBody JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        return service.saveDraft(
                actor(authentication),
                workflowId,
                parser.parseDraft(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 另存流程副本。
     *
     * @param workflowId 源流程 ID
     * @param body 可选请求体
     * @param idempotencyKey 可选幂等键
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 副本详情
     */
    @PostMapping("/{workflowId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKFLOW_EDIT)
    public WorkflowDefinitionDetailResponse duplicate(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return service.duplicate(
                actor(authentication),
                workflowId,
                parser.parseDuplicate(body),
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

    /**
     * 解析列表视图。
     *
     * @param value 查询参数
     * @return 视图；未提供时为 MINE
     */
    private static WorkflowDefinitionView parseView(String value) {
        if (value == null || value.isBlank()) {
            return WorkflowDefinitionView.MINE;
        }
        try {
            return WorkflowDefinitionView.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("view must be MINE, RUNNABLE or ALL");
        }
    }

    /**
     * 校验可选幂等键长度。
     *
     * @param idempotencyKey 请求头
     */
    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH + " and "
                    + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }
}
