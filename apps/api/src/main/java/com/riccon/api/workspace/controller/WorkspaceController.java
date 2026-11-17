package com.riccon.api.workspace.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.workspace.dto.WorkspaceCreateRequest;
import com.riccon.api.workspace.dto.WorkspacePageResponse;
import com.riccon.api.workspace.dto.WorkspaceResponse;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workspace.WorkspaceKind;
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
 * 工作空间接口。
 *
 * <p>列表与读取要求 {@code workspace.read}，创建团队空间要求 {@code workspace.write}；权限码与 OpenAPI 的
 * {@code x-riccon-permission} 一致。个人空间由列表首次调用自动创建。
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
     * 创建团队工作空间。
     *
     * @param body 创建请求
     * @param authentication 当前认证
     * @return 新工作空间
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(PermissionCodes.WORKSPACE_WRITE)
    public WorkspaceResponse create(@RequestBody WorkspaceCreateRequest body, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return workspaceService.createTeam(actor, body);
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
}
