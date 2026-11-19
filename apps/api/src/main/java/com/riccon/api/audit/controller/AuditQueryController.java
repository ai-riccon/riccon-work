package com.riccon.api.audit.controller;

import com.riccon.api.audit.service.AuditEventPageResponse;
import com.riccon.api.audit.service.AuditPolicyResponse;
import com.riccon.api.audit.service.AuditQueryService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询接口（ADR-0020 第 1/7 节）。
 *
 * <p>只读：没有创建/修改/删除路由；租户只从已认证主体派生，路径与参数不接受 {@code tenantId}。
 * 平台管理员的主体租户是 {@code platform}，因此只能读取平台租户自身事件。
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditQueryController {

    /** 审计查询服务。 */
    private final AuditQueryService auditQueryService;

    /**
     * 创建审计查询控制器。
     *
     * @param auditQueryService 审计查询服务
     */
    public AuditQueryController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    /**
     * 查询审计事件。
     *
     * @param occurredFrom 起始时间原文，可为空
     * @param occurredTo 结束时间原文，可为空
     * @param action 动作名，可为空
     * @param actorId 操作者 ID 原文，可为空
     * @param targetType 目标类型原文，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果原文，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 审计分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AuditEventPageResponse list(
            @RequestParam(required = false) String occurredFrom,
            @RequestParam(required = false) String occurredTo,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return auditQueryService.list(
                TenantContext.require(),
                occurredFrom,
                occurredTo,
                action,
                actorId,
                targetType,
                targetId,
                outcome,
                page,
                size,
                actor,
                meta(request));
    }

    /**
     * 返回审计保留与导出策略。
     *
     * @return 审计策略
     */
    @GetMapping("/policy")
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AuditPolicyResponse policy() {
        return auditQueryService.policy();
    }

    /**
     * 组装审计请求元数据；查询事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
