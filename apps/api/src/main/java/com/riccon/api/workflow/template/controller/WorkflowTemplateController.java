package com.riccon.api.workflow.template.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionActor;
import com.riccon.api.workflow.definition.dto.WorkflowDefinitionDetailResponse;
import com.riccon.api.workflow.template.dto.WorkflowTemplatePageResponse;
import com.riccon.api.workflow.template.service.WorkflowTemplateRequestParser;
import com.riccon.api.workflow.template.service.WorkflowTemplateService;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 内置模板接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>路径与权限：列表要求 {@code workflow.read}，复制要求 {@code workflow.edit}；复制生成当前用户的个人
 * 草稿，模板本身不被修改（AC-10）。平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器
 * 之前短路（ADR-0024 §6.1，F-005）。M06 不提供用户模板写端点，共享/升级在 M06b（ADR-0024 §12）。
 *
 * <p>复制请求体使用 {@link JsonNode} 交给解析器做白名单校验，确保契约 {@code additionalProperties: false}
 * 生效并返回 400 {@code RICCON-WF-400}。{@code Idempotency-Key} 为可选参数：缺失不拒绝、有值只校验
 * 长度，M06 不持久化该键、不承诺键级重放（ADR-0024 §7.4、§16.1）。
 */
@RestController
@RequestMapping("/api/v1/workflow-templates")
public class WorkflowTemplateController {

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 模板目录服务。 */
    private final WorkflowTemplateService service;

    /** 复制请求解析与白名单校验。 */
    private final WorkflowTemplateRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建控制器。
     *
     * @param service 模板目录服务
     * @param parser 复制请求解析器
     * @param authService 认证服务
     */
    public WorkflowTemplateController(
            WorkflowTemplateService service, WorkflowTemplateRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 列出内置模板。
     *
     * @param scenario 可选场景过滤
     * @param name 可选名称过滤
     * @param page 页码
     * @param size 每页大小
     * @return 模板分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowTemplatePageResponse list(
            @RequestParam(required = false) String scenario,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(scenario, name, PageQuery.of(page, size));
    }

    /**
     * 从内置模板复制为当前用户的草稿。
     *
     * @param templateId 模板 ID
     * @param body 可选请求体，仅允许 {@code name}
     * @param idempotencyKey 可选幂等键；缺失不拒绝，提供时只校验 8..128
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已创建草稿
     */
    @PostMapping("/{templateId}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKFLOW_EDIT)
    public WorkflowDefinitionDetailResponse copy(
            @PathVariable UUID templateId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return service.copy(
                actor(authentication),
                templateId,
                parser.parseCopy(body),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 构造当前主体的流程权限快照。
     *
     * <p>与流程定义控制器同口径：每次请求从数据库加载权限，不信任会话中可能过期的权限快照。
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
     * <p>M06 复制不承诺键级重放：缺失直接通过；提供时只校验 8..128，避免把明显错误的键静默忽略。
     *
     * @param idempotencyKey 请求头；可为空
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
