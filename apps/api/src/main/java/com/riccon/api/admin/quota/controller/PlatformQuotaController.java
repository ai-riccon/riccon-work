package com.riccon.api.admin.quota.controller;

import com.riccon.api.admin.quota.dto.PlatformQuotaPage;
import com.riccon.api.admin.quota.service.PlatformQuotaService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.quota.dto.QuotaLimitUpdate;
import com.riccon.api.quota.dto.TenantQuota;
import com.riccon.api.quota.dto.UserQuota;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 平台管理员配额接口。
 *
 * <p>列表只返回租户聚合（无用户明细）；写接口需要 {@code platform.quota.manage}，租户管理员与员工都会被
 * PermissionInterceptor 拒绝。路径 {@code tenantId} 是平台显式指定的目标租户，不是请求租户来源。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformQuotaController {

    /** 租户代码格式，与 OpenAPI TenantId 一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 平台配额服务。 */
    private final PlatformQuotaService platformQuotaService;

    /**
     * 创建平台配额控制器。
     *
     * @param platformQuotaService 平台配额服务
     */
    public PlatformQuotaController(PlatformQuotaService platformQuotaService) {
        this.platformQuotaService = platformQuotaService;
    }

    /**
     * 平台租户配额聚合分页。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 租户聚合页
     */
    @GetMapping("/quotas")
    @RequirePermission(PermissionCodes.PLATFORM_QUOTA_MANAGE)
    public PlatformQuotaPage listTenantQuotas(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return platformQuotaService.listTenants(PageQuery.of(page, size));
    }

    /**
     * 整体替换租户级配额。
     *
     * @param tenantId 目标租户
     * @param body 限额请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的租户配额
     */
    @PutMapping("/tenants/{tenantId}/quotas")
    @RequirePermission(PermissionCodes.PLATFORM_QUOTA_MANAGE)
    public TenantQuota updateTenantQuota(
            @PathVariable String tenantId,
            @RequestBody(required = false) JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        QuotaLimitUpdate update = QuotaLimitUpdate.parse(body);
        return platformQuotaService.updateTenantQuota(actor, targetTenant(tenantId), update, meta(request));
    }

    /**
     * 整体替换目标用户级配额。
     *
     * @param tenantId 目标租户
     * @param userId 目标用户
     * @param body 限额请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的用户配额
     */
    @PutMapping("/tenants/{tenantId}/users/{userId}/quotas")
    @RequirePermission(PermissionCodes.PLATFORM_QUOTA_MANAGE)
    public UserQuota updateUserQuota(
            @PathVariable String tenantId,
            @PathVariable UUID userId,
            @RequestBody(required = false) JsonNode body,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        QuotaLimitUpdate update = QuotaLimitUpdate.parse(body);
        return platformQuotaService.updateUserQuota(actor, targetTenant(tenantId), userId, update, meta(request));
    }

    /**
     * 校验路径租户代码。
     *
     * @param tenantId 路径参数
     * @return 合法租户代码
     */
    private String targetTenant(String tenantId) {
        return Validation.requirePattern(tenantId, "tenantId", TENANT_CODE, 64);
    }

    /**
     * 组装审计元数据；管理事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
