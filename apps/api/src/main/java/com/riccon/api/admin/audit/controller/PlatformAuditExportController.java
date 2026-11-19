package com.riccon.api.admin.audit.controller;

import com.riccon.api.admin.audit.service.PlatformAuditExportService;
import com.riccon.api.audit.service.AuditExportPageResponse;
import com.riccon.api.audit.service.AuditExportView;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
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

/**
 * 平台管理员审计导出审批接口（ADR-0020 第 2/3/7 节）。
 *
 * <p>仅 {@code platform.audit.export.approve} 可访问；只返回/决定请求元数据，不返回审计内容、不提供下载。
 * 目标租户有多个启用的 {@code TENANT_ADMIN} 时列表与决策均返回 {@code RICCON-AUDIT-409}。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/audit-exports")
public class PlatformAuditExportController {

    /** 平台审计导出审批服务。 */
    private final PlatformAuditExportService platformAuditExportService;

    /**
     * 创建平台审计导出审批控制器。
     *
     * @param platformAuditExportService 平台审计导出审批服务
     */
    public PlatformAuditExportController(PlatformAuditExportService platformAuditExportService) {
        this.platformAuditExportService = platformAuditExportService;
    }

    /**
     * 列出目标租户导出请求元数据。
     *
     * @param tenantId 目标租户代码
     * @param status 状态过滤，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @return 导出请求分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE)
    public AuditExportPageResponse list(
            @PathVariable String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return platformAuditExportService.list(tenantId, status, page, size);
    }

    /**
     * 审批目标租户导出请求；平台管理员不会成为申请人。
     *
     * @param tenantId 目标租户代码
     * @param exportId 导出请求 ID
     * @param body 审批体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 审批后的导出请求
     */
    @PostMapping("/{exportId}/decision")
    @RequirePermission(PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE)
    public AuditExportView decide(
            @PathVariable String tenantId,
            @PathVariable UUID exportId,
            @RequestBody PlatformAuditExportDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return platformAuditExportService.decide(
                tenantId, exportId, body.decision(), body.comment(), actor, meta(request));
    }

    /**
     * 组装审计请求元数据；平台审批不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
