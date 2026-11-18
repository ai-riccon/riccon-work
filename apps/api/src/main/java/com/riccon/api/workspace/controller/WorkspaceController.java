package com.riccon.api.workspace.controller;

import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import com.riccon.api.workspace.dto.WorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspaceMemberAddRequest;
import com.riccon.api.workspace.dto.WorkspaceMemberPageResponse;
import com.riccon.api.workspace.dto.WorkspaceMemberResponse;
import com.riccon.api.workspace.dto.WorkspacePageResponse;
import com.riccon.api.workspace.dto.WorkspaceResponse;
import com.riccon.api.workspace.dto.WorkspaceUpdateRequest;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workspace.WorkspaceKind;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作空间接口。
 *
 * <p>列表与详情只返回可访问空间；创建要求 {@code workspace.write} 且必须提供启用部门；修改、可见性与
 * CUSTOM 成员由服务层按“租户管理员或本部门负责人”判定。跨租户、跨部门与个人空间他人统一 404，
 * 可见但无管理能力 403（ADR-0018 第 3.1/7.1/7.3 节）。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    /** 工作空间服务。 */
    private final WorkspaceService workspaceService;

    /**
     * 创建控制器。
     *
     * @param workspaceService 工作空间服务
     */
    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 列出可访问工作空间。
     *
     * @param page 页码
     * @param size 每页大小
     * @param kind 可选类型过滤
     * @param authentication 当前认证
     * @return 工作空间分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspacePageResponse list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) WorkspaceKind kind,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.list(actor, PageQuery.of(page, size), kind);
    }

    /**
     * 创建部门工作空间。
     *
     * @param body 创建请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新工作空间
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKSPACE_WRITE)
    public WorkspaceResponse create(
            @RequestBody WorkspaceCreateRequest body, Authentication authentication, HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.createTeam(actor, body, meta(request));
    }

    /**
     * 读取工作空间。
     *
     * @param workspaceId 工作空间 ID
     * @param authentication 当前认证
     * @return 工作空间
     */
    @GetMapping("/{workspaceId}")
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspaceResponse get(@PathVariable UUID workspaceId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.get(actor, workspaceId);
    }

    /**
     * 修改空间名称、说明或可见性。
     *
     * @param workspaceId 工作空间 ID
     * @param body 更新请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 更新后的工作空间
     */
    @PatchMapping("/{workspaceId}")
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspaceResponse update(
            @PathVariable UUID workspaceId,
            @RequestBody WorkspaceUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.update(actor, workspaceId, body, meta(request));
    }

    /**
     * 列出 CUSTOM 空间显式成员。
     *
     * @param workspaceId 工作空间 ID
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 成员分页
     */
    @GetMapping("/{workspaceId}/members")
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspaceMemberPageResponse listMembers(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.listMembers(actor, workspaceId, PageQuery.of(page, size));
    }

    /**
     * 加入 CUSTOM 空间显式成员。
     *
     * @param workspaceId 工作空间 ID
     * @param body 成员请求
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 新成员
     */
    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public WorkspaceMemberResponse addMember(
            @PathVariable UUID workspaceId,
            @RequestBody WorkspaceMemberAddRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        Validation.requireBody(body);
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        UUID userId = Validation.requireNonNull(body.userId(), "userId");
        return workspaceService.addMember(actor, workspaceId, userId, meta(request));
    }

    /**
     * 移出 CUSTOM 空间显式成员。
     *
     * @param workspaceId 工作空间 ID
     * @param userId 目标账号 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     */
    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionCodes.WORKSPACE_READ)
    public void removeMember(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            Authentication authentication,
            HttpServletRequest request) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        workspaceService.removeMember(actor, workspaceId, userId, meta(request));
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
