package com.riccon.api.workflow.history.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.api.web.RequestContext;
import com.riccon.api.workflow.history.domain.WorkflowHistoryActor;
import com.riccon.api.workflow.history.domain.WorkflowHistoryFilter;
import com.riccon.api.workflow.history.dto.WorkflowRunDetailResponse;
import com.riccon.api.workflow.history.dto.WorkflowRunHistoryPageResponse;
import com.riccon.api.workflow.history.service.WorkflowHistoryRequestParser;
import com.riccon.api.workflow.history.service.WorkflowHistoryService;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流运行历史与详情接口（契约 {@code 1.6.0-m06}）。
 *
 * <p>端点：{@code GET /api/v1/workflow-runs}（历史，过滤/分页/倒序/审批聚合）与
 * {@code GET /api/v1/workflow-runs/{workflowRunId}}（详情）。两者都要求 {@code workflow.read}；
 * 资源级可见性由服务层判定：租户管理员全部、其他主体仅本人发起的运行与本人流程的运行元数据，
 * 详情仅发起人与租户管理员（ADR-0024 §6.2）。
 *
 * <p>平台租户 404 由 {@code WorkflowPlatformTenantFilter} 在权限拦截器前短路；跨租户与到期运行
 * 由服务层统一 404。列表不返回结果正文与输入原文，详情不返回提示词、完整参数、凭据或无 ACL 正文。
 */
@RestController
public class WorkflowRunHistoryController {

    /** 运行历史服务。 */
    private final WorkflowHistoryService service;

    /** 查询参数解析。 */
    private final WorkflowHistoryRequestParser parser;

    /** 认证服务，用于每次请求重新计算权限。 */
    private final AuthService authService;

    /**
     * 创建运行历史控制器。
     *
     * @param service 运行历史服务
     * @param parser 查询参数解析
     * @param authService 认证服务
     */
    public WorkflowRunHistoryController(
            WorkflowHistoryService service, WorkflowHistoryRequestParser parser, AuthService authService) {
        this.service = service;
        this.parser = parser;
        this.authService = authService;
    }

    /**
     * 读取运行历史。
     *
     * <p>时间参数必须带偏移（合同 date-time）；{@code from} 晚于 {@code to} 或 ID 非法返回 400
     * {@code RICCON-WF-400}。租户管理员每次列表写一条 {@code workflow.run.admin_read scope=LIST}。
     *
     * @param workflowId 流程 ID；可空
     * @param versionNo 版本号；可空
     * @param status 运行状态；可空
     * @param requesterUserId 发起人 ID；可空
     * @param from 创建时间起点；可空
     * @param to 创建时间终点；可空
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 运行分页
     */
    @GetMapping("/api/v1/workflow-runs")
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowRunHistoryPageResponse list(
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) WorkflowRunStatus status,
            @RequestParam(required = false) String requesterUserId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication,
            HttpServletRequest request) {
        WorkflowHistoryFilter filter = parser.parseFilter(workflowId, versionNo, status, requesterUserId, from, to);
        return service.list(
                actor(authentication),
                filter,
                PageQuery.of(page, size),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 读取运行详情。
     *
     * <p>节点时间线包含全部重试尝试；审批记录中的 {@code paramsPreview} 只对当前账号被指派的审批
     * 返回；到期/不可见统一 404。
     *
     * @param workflowRunId 运行 ID
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @return 运行详情
     */
    @GetMapping("/api/v1/workflow-runs/{workflowRunId}")
    @RequirePermission(PermissionCodes.WORKFLOW_READ)
    public WorkflowRunDetailResponse get(
            @PathVariable String workflowRunId, Authentication authentication, HttpServletRequest request) {
        return service.detail(
                actor(authentication),
                parser.parseRunId(workflowRunId),
                RequestContext.traceId(request),
                RequestContext.requestId(request));
    }

    /**
     * 构造当前主体的运行历史权限快照。
     *
     * @param authentication 当前认证
     * @return 权限快照
     */
    private WorkflowHistoryActor actor(Authentication authentication) {
        RicconPrincipal principal = AuthenticationSupport.requirePrincipal(authentication);
        CurrentUser current = authService.loadCurrentUser(principal);
        return new WorkflowHistoryActor(
                principal.userId(), principal.tenantId(), current.hasPermission(PermissionCodes.WORKFLOW_MANAGE));
    }
}
