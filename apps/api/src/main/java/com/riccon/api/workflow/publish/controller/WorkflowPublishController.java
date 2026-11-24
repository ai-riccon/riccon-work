package com.riccon.api.workflow.publish.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.dto.WorkflowVersionSummaryResponse;
import com.riccon.api.workflow.publish.service.WorkflowPublishBadRequestException;
import com.riccon.api.workflow.publish.service.WorkflowPublishRequestParser;
import com.riccon.api.workflow.publish.service.WorkflowPublishService;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 流程发布接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>{@code POST /api/v1/workflows/{workflowId}/publish} 要求 {@code workflow.publish}；
 * {@code Idempotency-Key} 为可选参数：缺失不拒绝，提供时只校验 8..128 长度，但 M06 不持久化该键、
 * 不做键级重放；重复发布由资源级幂等（图哈希 + 范围）返回既有版本（ADR-0024 §7.4、§16.1）。平台租户
 * 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器前短路（ADR-0024 §6.1）。
 *
 * <p>请求体使用 {@link JsonNode} 交给解析器做字段白名单，确保 {@code additionalProperties: false}
 * 生效并返回 400 {@code RICCON-WF-400}，而不是被全局 Jackson 配置静默忽略未知字段。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowPublishController {

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 发布服务。 */
    private final WorkflowPublishService service;

    /** 请求解析与白名单校验。 */
    private final WorkflowPublishRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建发布控制器。
     *
     * @param service 发布服务
     * @param parser 请求解析器
     * @param authService 认证服务
     */
    public WorkflowPublishController(
            WorkflowPublishService service, WorkflowPublishRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 发布当前草稿。
     *
     * @param workflowId 流程 ID
     * @param body 发布请求
     * @param idempotencyKey 可选幂等键；缺失不拒绝，提供时校验 8..128 长度
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新发布版本或同一草稿的既有版本
     */
    @PostMapping("/{workflowId}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKFLOW_PUBLISH)
    public WorkflowVersionSummaryResponse publish(
            @PathVariable UUID workflowId,
            @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return service.publish(
                actor(authentication),
                workflowId,
                parser.parsePublish(body),
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
     * 校验可选幂等键长度。
     *
     * <p>M06 发布不承诺键级重放：缺失直接通过；提供时只校验 8..128，避免把明显错误的键静默忽略。
     *
     * @param idempotencyKey 请求头；可为空
     * @throws WorkflowPublishBadRequestException 提供但长度不在 8..128
     */
    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new WorkflowPublishBadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH
                    + " and " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }
}
