package com.riccon.api.admin.department.controller;

import com.riccon.api.admin.department.service.DepartmentAdminService;
import com.riccon.api.department.dto.DepartmentCreateRequest;
import com.riccon.api.department.dto.DepartmentManagerRequest;
import com.riccon.api.department.dto.DepartmentMemberPageResponse;
import com.riccon.api.department.dto.DepartmentMemberRequest;
import com.riccon.api.department.dto.DepartmentMemberResponse;
import com.riccon.api.department.dto.DepartmentPageResponse;
import com.riccon.api.department.dto.DepartmentResponse;
import com.riccon.api.department.dto.DepartmentUpdateRequest;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.organization.DepartmentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 平台管理员跨租户部门管理接口。
 *
 * <p>复用 M02 的 {@code /api/v1/admin/tenants/{tenantId}} 路径族，要求 {@code platform.department.manage}；
 * 租户管理员与普通用户调用统一 403。目标租户只从路径参数读取，不存在统一 404、已暂停 409
 * （ADR-0018 第 1.4/7.1/7.3 节）。本接口只操作组织元数据，不读取目标租户业务内容。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/departments")
public class DepartmentAdminController {

    /** 租户代码格式，与 M02 平台管理接口一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 部门管理服务。 */
    private final DepartmentAdminService departmentAdminService;

    /**
     * 创建平台部门管理控制器。
     *
     * @param departmentAdminService 部门管理服务
     */
    public DepartmentAdminController(DepartmentAdminService departmentAdminService) {
        this.departmentAdminService = departmentAdminService;
    }

    /**
     * 列出目标租户部门。
     *
     * @param tenantId 目标租户
     * @param status 可选状态过滤
     * @param page 页码
     * @param size 每页大小
     * @return 部门分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentPageResponse listDepartments(
            @PathVariable String tenantId,
            @RequestParam(required = false) DepartmentStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return departmentAdminService.listDepartments(targetTenant(tenantId), status, PageQuery.of(page, size));
    }

    /**
     * 在目标租户创建部门。
     *
     * @param tenantId 目标租户
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新部门
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentResponse createDepartment(
            @PathVariable String tenantId,
            @RequestBody DepartmentCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.createDepartment(targetTenant(tenantId), body.name(), actor, meta(request));
    }

    /**
     * 重命名目标租户部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param body 更新请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的部门
     */
    @PatchMapping("/{departmentId}")
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentResponse renameDepartment(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            @RequestBody DepartmentUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.renameDepartment(
                targetTenant(tenantId), departmentId, body.name(), actor, meta(request));
    }

    /**
     * 停用目标租户部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的部门
     */
    @PostMapping("/{departmentId}/disable")
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentResponse disableDepartment(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.disableDepartment(targetTenant(tenantId), departmentId, actor, meta(request));
    }

    /**
     * 启用目标租户部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的部门
     */
    @PostMapping("/{departmentId}/enable")
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentResponse enableDepartment(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.enableDepartment(targetTenant(tenantId), departmentId, actor, meta(request));
    }

    /**
     * 列出目标租户部门成员。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param page 页码
     * @param size 每页大小
     * @return 成员分页
     */
    @GetMapping("/{departmentId}/members")
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentMemberPageResponse listMembers(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return departmentAdminService.listMembers(targetTenant(tenantId), departmentId, PageQuery.of(page, size));
    }

    /**
     * 把目标租户账号加入部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param body 成员请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 加入后的成员
     */
    @PostMapping("/{departmentId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentMemberResponse addMember(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            @RequestBody DepartmentMemberRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        UUID userId = Validation.requireNonNull(body.userId(), "userId");
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.addMember(targetTenant(tenantId), departmentId, userId, actor, meta(request));
    }

    /**
     * 把账号移出部门。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @DeleteMapping("/{departmentId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public void removeMember(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            @PathVariable UUID userId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        departmentAdminService.removeMember(targetTenant(tenantId), departmentId, userId, actor, meta(request));
    }

    /**
     * 任命或移交部门负责人。
     *
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param body 负责人请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的部门
     */
    @PutMapping("/{departmentId}/manager")
    @RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)
    public DepartmentResponse transferManager(
            @PathVariable String tenantId,
            @PathVariable UUID departmentId,
            @RequestBody DepartmentManagerRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        UUID userId = Validation.requireNonNull(body.userId(), "userId");
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentAdminService.transferManager(
                targetTenant(tenantId), departmentId, userId, actor, meta(request));
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
