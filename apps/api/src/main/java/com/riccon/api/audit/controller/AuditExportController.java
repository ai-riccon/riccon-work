package com.riccon.api.audit.controller;

import com.riccon.api.audit.service.AuditDownload;
import com.riccon.api.audit.service.AuditExportPageResponse;
import com.riccon.api.audit.service.AuditExportService;
import com.riccon.api.audit.service.AuditExportView;
import com.riccon.api.audit.service.AuditFilterRequest;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 审计导出接口（ADR-0020 第 2/7 节）。
 *
 * <p>租户端点只服务租户管理员：申请、列表、详情与下载需要 {@code audit.read}，审批需要
 * {@code audit.export.approve}。平台租户自身导出在 M10 不支持（403）；平台管理员下载租户导出按 404 处理。
 * CSV 不落盘、不写对象存储，以下载时流式生成。
 */
@RestController
@RequestMapping("/api/v1/audit-exports")
public class AuditExportController {

    /** 下载响应的媒体类型。 */
    private static final MediaType TEXT_CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=utf-8");

    /** 导出服务。 */
    private final AuditExportService auditExportService;

    /**
     * 创建审计导出控制器。
     *
     * @param auditExportService 导出服务
     */
    public AuditExportController(AuditExportService auditExportService) {
        this.auditExportService = auditExportService;
    }

    /**
     * 发起导出申请。
     *
     * @param body 申请体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新建导出请求
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AuditExportView create(
            @RequestBody AuditExportCreateRequest body, Authentication authentication, HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        AuditFilterRequest filter = body.filter();
        return auditExportService.create(TenantContext.require(), filter, actor, meta(request));
    }

    /**
     * 列出本租户导出请求。
     *
     * @param status 状态过滤，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @return 导出请求分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AuditExportPageResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return auditExportService.list(TenantContext.require(), status, page, size);
    }

    /**
     * 读取导出请求详情。
     *
     * @param exportId 导出请求 ID
     * @return 导出请求
     */
    @GetMapping("/{exportId}")
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AuditExportView get(@PathVariable UUID exportId) {
        return auditExportService.get(TenantContext.require(), exportId);
    }

    /**
     * 审批导出请求。
     *
     * @param exportId 导出请求 ID
     * @param body 审批体
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 审批后的导出请求
     */
    @PostMapping("/{exportId}/decision")
    @RequirePermission(PermissionCodes.AUDIT_EXPORT_APPROVE)
    public AuditExportView decide(
            @PathVariable UUID exportId,
            @RequestBody AuditExportDecisionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return auditExportService.decideTenant(
                TenantContext.require(), exportId, body.decision(), body.comment(), actor, meta(request));
    }

    /**
     * 下载导出的 CSV 流。
     *
     * <p>{@code data.exported} 在返回流之前写入；流在容器异步线程生成，租户作用域由服务层显式打开。
     *
     * @param exportId 导出请求 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return CSV 流
     */
    @GetMapping("/{exportId}/download")
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID exportId, Authentication authentication, HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        TenantId tenantId = TenantContext.require();
        AuditDownload download = auditExportService.prepareDownload(tenantId, exportId, actor, meta(request));
        StreamingResponseBody body = outputStream -> auditExportService.writeCsv(download, tenantId, outputStream);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"audit-export-" + download.exportId() + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * 组装审计请求元数据；导出事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
