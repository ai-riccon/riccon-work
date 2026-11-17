package com.riccon.api.admin.controller;

import com.riccon.api.admin.service.TenantUserAdminService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.identity.dto.UserCreateRequest;
import com.riccon.api.identity.dto.UserPageResponse;
import com.riccon.api.identity.dto.UserRolesRequest;
import com.riccon.api.identity.dto.UserStatusRequest;
import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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
 * 租户管理员接口：本租户账号与角色管理。
 *
 * <p>目标租户只能来自 {@link TenantContext}（会话主体），路径/请求体中的租户标识一律忽略；跨租户对象统一
 * 404（TEN-003/004）。权限注解额外要求 {@code tenant.user.manage} 或 {@code tenant.role.manage}。
 */
@RestController
@RequestMapping("/api/v1/users")
public class TenantUserController {

    /** 账号管理服务。 */
    private final TenantUserAdminService tenantUserAdminService;

    /**
     * 创建租户账号控制器。
     *
     * @param tenantUserAdminService 账号管理服务
     */
    public TenantUserController(TenantUserAdminService tenantUserAdminService) {
        this.tenantUserAdminService = tenantUserAdminService;
    }

    /**
     * 列出本租户账号。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 账号分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.TENANT_USER_MANAGE)
    public UserPageResponse listUsers(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return tenantUserAdminService.listUsers(TenantContext.require(), PageQuery.of(page, size));
    }

    /**
     * 创建本租户账号。
     *
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新账号
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.TENANT_USER_MANAGE)
    public UserSummaryResponse createUser(
            @RequestBody UserCreateRequest body, Authentication authentication, HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.createUser(TenantContext.require(), body, actor, false, meta(request));
    }

    /**
     * 启用或禁用本租户账号。
     *
     * @param userId 账号 ID
     * @param body 状态请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的账号
     */
    @PatchMapping("/{userId}")
    @RequirePermission(PermissionCodes.TENANT_USER_MANAGE)
    public UserSummaryResponse updateStatus(
            @PathVariable UUID userId,
            @RequestBody UserStatusRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.updateStatus(
                TenantContext.require(), userId, body.status(), actor, meta(request));
    }

    /**
     * 替换本租户账号角色。
     *
     * @param userId 账号 ID
     * @param body 角色请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的账号
     */
    @PutMapping("/{userId}/roles")
    @RequirePermission(PermissionCodes.TENANT_ROLE_MANAGE)
    public UserSummaryResponse replaceRoles(
            @PathVariable UUID userId,
            @RequestBody UserRolesRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return tenantUserAdminService.replaceRoles(
                TenantContext.require(), userId, body.roles(), actor, false, meta(request));
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
