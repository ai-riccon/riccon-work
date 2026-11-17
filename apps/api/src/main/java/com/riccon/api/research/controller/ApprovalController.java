package com.riccon.api.research.controller;

import com.riccon.api.error.BadRequestException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.research.dto.ApprovalDecisionRequest;
import com.riccon.api.research.dto.ApprovalPageResponse;
import com.riccon.api.research.dto.ApprovalResponse;
import com.riccon.api.research.service.ApprovalService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.domain.contract.HttpHeaders;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.research.ApprovalStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批接口。
 *
 * <p>列表与决定都需要 {@code report.approve}；决定必须带 {@code Idempotency-Key}。审批详情通过列表或
 * 报告任务详情返回——OpenAPI 未定义单审批 GET，新增端点必须先改契约，因此这里不提供。
 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    /** Idempotency-Key 最小长度，与 OpenAPI 一致。 */
    private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度，与 OpenAPI 一致。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 审批服务。 */
    private final ApprovalService approvalService;

    /**
     * 创建控制器。
     *
     * @param approvalService 审批服务
     */
    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 列出审批。
     *
     * @param status 可选状态过滤
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 审批分页
     */
    @GetMapping("/approvals")
    @RequirePermission(PermissionCodes.REPORT_APPROVE)
    public ApprovalPageResponse list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return approvalService.list(actor, status, PageQuery.of(page, size));
    }

    /**
     * 批准或拒绝外部工具调用。
     *
     * @param approvalId 审批 ID
     * @param idempotencyKey 幂等键
     * @param body 决定请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 审批结果
     */
    @PostMapping("/approvals/{approvalId}/decision")
    @RequirePermission(PermissionCodes.REPORT_APPROVE)
    public ApprovalResponse decide(
            @PathVariable UUID approvalId,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody ApprovalDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        requireIdempotencyKey(idempotencyKey);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return approvalService.decide(
                actor, approvalId, body, RequestContext.traceId(request), RequestContext.requestId(request));
    }

    /**
     * 校验必需的幂等键。
     *
     * @param idempotencyKey 请求头
     */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new BadRequestException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH + " and "
                    + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }
}
