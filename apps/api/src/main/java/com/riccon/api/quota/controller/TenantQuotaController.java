package com.riccon.api.quota.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.quota.dto.TenantQuota;
import com.riccon.api.quota.dto.UserQuotaPage;
import com.riccon.api.quota.service.QuotaQueryService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户端配额只读接口。
 *
 * <p>只允许 {@code quota.read}（租户管理员）；员工没有该权限，由 PermissionInterceptor 返回 403。
 * 租户从会话主体派生，路径中没有租户参数，避免租户管理员跨租户读取。
 */
@RestController
@RequestMapping("/api/v1/quotas")
public class TenantQuotaController {

    /** 配额读取服务。 */
    private final QuotaQueryService quotaQueryService;

    /**
     * 创建租户端配额控制器。
     *
     * @param quotaQueryService 配额读取服务
     */
    public TenantQuotaController(QuotaQueryService quotaQueryService) {
        this.quotaQueryService = quotaQueryService;
    }

    /**
     * 读取本租户日/月配额与用量。
     *
     * @param authentication 当前认证
     * @return 本租户配额
     */
    @GetMapping
    @RequirePermission(PermissionCodes.QUOTA_READ)
    public TenantQuota getTenantQuota(Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return quotaQueryService.getTenantQuota(TenantId.of(actor.tenantId()));
    }

    /**
     * 分页读取本租户用户配额。
     *
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 用户配额分页
     */
    @GetMapping("/users")
    @RequirePermission(PermissionCodes.QUOTA_READ)
    public UserQuotaPage listUserQuotas(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return quotaQueryService.listUserQuotas(TenantId.of(actor.tenantId()), PageQuery.of(page, size));
    }
}
