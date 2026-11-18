package com.riccon.api.department.controller;

import com.riccon.api.department.dto.DepartmentMemberPageResponse;
import com.riccon.api.department.dto.DepartmentPageResponse;
import com.riccon.api.department.service.DepartmentQueryService;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户端部门只读接口。
 *
 * <p>只保留读取本人部门与成员列表；创建/改名/停用/成员/负责人全部在
 * {@code /api/v1/admin/tenants/{tenantId}/departments} 由平台管理员执行（DEC-0015、ADR-0018 第 7.1 节）。
 * 租户管理员可读本租户全部部门，但不因此获得任何部门写入能力。
 */
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    /** 部门只读服务。 */
    private final DepartmentQueryService departmentQueryService;

    /**
     * 创建部门只读控制器。
     *
     * @param departmentQueryService 部门只读服务
     */
    public DepartmentController(DepartmentQueryService departmentQueryService) {
        this.departmentQueryService = departmentQueryService;
    }

    /**
     * 读取本人所属部门；租户管理员读取本租户全部部门。
     *
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 部门分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.DEPARTMENT_READ)
    public DepartmentPageResponse listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentQueryService.listMine(actor, PageQuery.of(page, size));
    }

    /**
     * 列出部门成员；普通成员只能访问本人部门。
     *
     * @param departmentId 部门 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 成员分页
     */
    @GetMapping("/{departmentId}/members")
    @RequirePermission(PermissionCodes.DEPARTMENT_READ)
    public DepartmentMemberPageResponse listMembers(
            @PathVariable UUID departmentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return departmentQueryService.listMembers(actor, departmentId, PageQuery.of(page, size));
    }
}
