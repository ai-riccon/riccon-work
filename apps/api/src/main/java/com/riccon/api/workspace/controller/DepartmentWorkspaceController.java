package com.riccon.api.workspace.controller;

import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.dto.DepartmentWorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspacePageResponse;
import com.riccon.api.workspace.dto.WorkspaceResponse;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.domain.identity.PermissionCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门空间管理接口。
 *
 * <p>路径携带部门，避免部门负责人伪造 {@code departmentId}；只有租户管理员或本部门负责人可读写，
 * 部门必须启用，其他部门统一 404（ADR-0018 第 2.3/7.1 节）。这里只操作空间元数据，不返回业务内容。
 */
@RestController
@RequestMapping("/api/v1/departments/{departmentId}/workspaces")
public class DepartmentWorkspaceController {

    /** 工作空间服务。 */
    private final WorkspaceService workspaceService;

    /**
     * 创建部门空间管理控制器。
     *
     * @param workspaceService 工作空间服务
     */
    public DepartmentWorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 列出部门空间元数据。
     *
     * @param departmentId 部门 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 空间分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspacePageResponse list(
            @PathVariable UUID departmentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.listDepartmentWorkspaces(actor, departmentId, PageQuery.of(page, size));
    }

    /**
     * 在部门下创建空间。
     *
     * @param departmentId 部门 ID
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新工作空间
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspaceResponse create(
            @PathVariable UUID departmentId,
            @RequestBody DepartmentWorkspaceCreateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.createTeamInDepartment(actor, departmentId, body, meta(request));
    }

    /**
     * 组装审计所需的请求元数据。
     *
     * @param request HTTP 请求
     * @return 请求元数据
     */
    private static RequestMeta meta(HttpServletRequest request) {
        return new RequestMeta(RequestContext.traceId(request), RequestContext.requestId(request), null);
    }
}
