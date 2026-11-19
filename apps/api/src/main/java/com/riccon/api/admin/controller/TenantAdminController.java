package com.riccon.api.admin.controller;

import com.riccon.api.admin.service.TenantAdminService;
import com.riccon.api.admin.service.TenantUserAdminService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.OidcBindingRequest;
import com.riccon.api.identity.dto.OidcUnbindRequest;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.identity.dto.TenantCreateRequest;
import com.riccon.api.identity.dto.TenantPageResponse;
import com.riccon.api.identity.dto.TenantSummaryResponse;
import com.riccon.api.identity.dto.TenantUpdateRequest;
import com.riccon.api.identity.dto.UserCreateRequest;
import com.riccon.api.identity.dto.UserPageResponse;
import com.riccon.api.identity.dto.UserRolesRequest;
import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员接口：租户目录与目标租户账号管理。
 *
 * <p>租户管理员不能访问这些路径（TEN-011）；所有操作要求 {@code platform.tenant.manage}，并写平台级审计。
 * 路径中的 {@code tenantId} 是平台管理员显式指定的目标租户，不是请求租户来源。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantAdminController {

    /** 租户代码格式，与 OpenAPI TenantId/ TenantCreateRequest 一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 租户管理服务。 */
    private final TenantAdminService tenantAdminService;

    /** 账号管理服务。 */
    private final TenantUserAdminService tenantUserAdminService;

    /**
     * 创建平台管理控制器。
     *
     * @param tenantAdminService 租户管理服务
     * @param tenantUserAdminService 账号管理服务
     */
    public TenantAdminController(TenantAdminService tenantAdminService, TenantUserAdminService tenantUserAdminService) {
        this.tenantAdminService = tenantAdminService;
        this.tenantUserAdminService = tenantUserAdminService;
    }

    /**
     * 列出平台租户。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 租户分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public TenantPageResponse listTenants(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return tenantAdminService.listTenants(PageQuery.of(page, size));
    }

    /**
     * 创建租户。
     *
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新租户
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public TenantSummaryResponse createTenant(
            @RequestBody TenantCreateRequest body, Authentication authentication, HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantAdminService.createTenant(body.id(), body.name(), actor, meta(request));
    }

    /**
     * 更新租户状态或保留策略。
     *
     * @param tenantId 目标租户
     * @param body 更新请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的租户
     */
    @PatchMapping("/{tenantId}")
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public TenantSummaryResponse updateTenant(
            @PathVariable String tenantId,
            @RequestBody TenantUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantAdminService.updateTenant(tenantId, body, actor, meta(request));
    }

    /**
     * 平台管理员列出目标租户账号。
     *
     * @param tenantId 目标租户
     * @param page 页码
     * @param size 每页大小
     * @return 账号分页
     */
    @GetMapping("/{tenantId}/users")
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public UserPageResponse listTenantUsers(
            @PathVariable String tenantId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return tenantUserAdminService.listUsers(targetTenant(tenantId), PageQuery.of(page, size));
    }

    /**
     * 平台管理员在目标租户创建账号。
     *
     * @param tenantId 目标租户
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新账号
     */
    @PostMapping("/{tenantId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public UserSummaryResponse createTenantUser(
            @PathVariable String tenantId,
            @RequestBody UserCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.createUser(TenantId.of(tenantId), body, actor, true, meta(request));
    }

    /**
     * 平台管理员替换目标租户账号角色。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param body 角色请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的账号
     */
    @PutMapping("/{tenantId}/users/{userId}/roles")
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public UserSummaryResponse replaceTenantUserRoles(
            @PathVariable String tenantId,
            @PathVariable UUID userId,
            @RequestBody UserRolesRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.replaceRoles(
                targetTenant(tenantId), userId, body.roles(), actor, true, meta(request));
    }

    /**
     * 平台管理员绑定目标租户账号到 OIDC 身份。
     *
     * <p>语义同租户端：issuer 必须命中目标租户配置，删除本地凭据并写审计；目标租户/账号不存在统一 404。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param body 绑定请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的账号
     */
    @PutMapping("/{tenantId}/users/{userId}/oidc-binding")
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public UserSummaryResponse bindTenantUserOidcIdentity(
            @PathVariable String tenantId,
            @PathVariable UUID userId,
            @RequestBody OidcBindingRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.bindOidcIdentity(targetTenant(tenantId), userId, body, actor, meta(request));
    }

    /**
     * 平台管理员解除目标租户账号的 OIDC 绑定并恢复本地密码。
     *
     * @param tenantId 目标租户
     * @param userId 账号 ID
     * @param body 解绑请求；必须包含新密码
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的账号
     */
    @PostMapping("/{tenantId}/users/{userId}/oidc-binding/remove")
    @RequirePermission(PermissionCodes.PLATFORM_TENANT_MANAGE)
    public UserSummaryResponse unbindTenantUserOidcIdentity(
            @PathVariable String tenantId,
            @PathVariable UUID userId,
            @RequestBody OidcUnbindRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.unbindOidcIdentity(targetTenant(tenantId), userId, body, actor, meta(request));
    }

    /**
     * 校验路径租户代码并转换为强类型标识。
     *
     * @param tenantId 路径参数
     * @return 合法租户标识
     */
    private TenantId targetTenant(String tenantId) {
        return TenantId.of(Validation.requirePattern(tenantId, "tenantId", TENANT_CODE, 64));
    }

    /**
     * 组装审计请求元数据；管理事件不记录来源 IP。
     *
     * @param request HTTP 请求
     * @return 元数据
     */
    private RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
