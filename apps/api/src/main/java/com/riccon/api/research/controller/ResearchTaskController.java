package com.riccon.api.research.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.research.dto.ResearchTaskCreateRequest;
import com.riccon.api.research.dto.ResearchTaskDetailResponse;
import com.riccon.api.research.dto.ResearchTaskPageResponse;
import com.riccon.api.research.dto.ResearchTaskResponse;
import com.riccon.api.research.service.ResearchTaskService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.research.ResearchTaskStatus;
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

/**
 * 报告任务接口。
 *
 * <p>读取要求 {@code report.read}，创建要求 {@code report.create}；创建必须带 {@code Idempotency-Key}。
 * 所有写操作由 Spring Security 校验 CSRF；跨租户或不可见工作空间统一 404（ADR-0014 第 2 节）。
 */
@RestController
@RequestMapping("/api/v1")
public class ResearchTaskController {

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 报告任务服务。 */
    private final ResearchTaskService researchTaskService;

    /**
     * 创建控制器。
     *
     * @param researchTaskService 报告任务服务
     */
    public ResearchTaskController(ResearchTaskService researchTaskService) {
        this.researchTaskService = researchTaskService;
    }

    /**
     * 列出工作空间报告任务。
     *
     * @param workspaceId 工作空间 ID
     * @param status 可选状态过滤
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 任务分页
     */
    @GetMapping("/workspaces/{workspaceId}/research-tasks")
    @RequirePermission(PermissionCodes.REPORT_READ)
    public ResearchTaskPageResponse list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) ResearchTaskStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return researchTaskService.list(actor, workspaceId, status, PageQuery.of(page, size));
    }

    /**
     * 创建报告任务并入队。
     *
     * @param workspaceId 工作空间 ID
     * @param idempotencyKey 幂等键
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 已创建或既有的任务
     */
    @PostMapping("/workspaces/{workspaceId}/research-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.REPORT_CREATE)
    public ResearchTaskResponse create(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody ResearchTaskCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return researchTaskService.create(
                actor, workspaceId, body, key, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 读取任务详情与时间线。
     *
     * @param researchTaskId 任务 ID
     * @param authentication 当前认证
     * @return 任务详情
     */
    @GetMapping("/research-tasks/{researchTaskId}")
    @RequirePermission(PermissionCodes.REPORT_READ)
    public ResearchTaskDetailResponse get(@PathVariable UUID researchTaskId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return researchTaskService.get(actor, researchTaskId);
    }

    /**
     * 校验必需的幂等键。
     *
     * @param idempotencyKey 请求头
     * @return 合法幂等键
     */
    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new BadRequestException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH + " and "
                    + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }
}
