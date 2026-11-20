package com.riccon.api.cost.controller;

import com.riccon.api.cost.domain.CostQuery;
import com.riccon.api.cost.dto.CostInvocationPage;
import com.riccon.api.cost.dto.TenantCostReport;
import com.riccon.api.cost.service.CostQueryService;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户端成本只读接口。
 *
 * <p>只允许 {@code cost.read}（租户管理员）；员工没有该权限，由 PermissionInterceptor 返回 403。
 * 租户从会话主体派生，路径中没有租户参数，避免租户管理员跨租户读取；平台管理员使用
 * {@code /api/v1/admin/costs}。
 */
@RestController
@RequestMapping("/api/v1/costs")
public class TenantCostController {

    /** 成本查询服务。 */
    private final CostQueryService costQueryService;

    /**
     * 创建租户成本控制器。
     *
     * @param costQueryService 成本查询服务
     */
    public TenantCostController(CostQueryService costQueryService) {
        this.costQueryService = costQueryService;
    }

    /**
     * 本租户成本聚合。
     *
     * @param from 起始日，可为空
     * @param to 结束日，可为空
     * @param groupBy 维度，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @param authentication 当前认证
     * @return 租户成本报告
     */
    @GetMapping
    @RequirePermission(PermissionCodes.COST_READ)
    public TenantCostReport report(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return costQueryService.report(
                TenantId.of(actor.tenantId()), new CostQuery(from, to, groupBy, page, size, null, null));
    }

    /**
     * 本租户调用成本明细。
     *
     * @param from 起始日，可为空
     * @param to 结束日，可为空
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @param authentication 当前认证
     * @return 明细分页
     */
    @GetMapping("/invocations")
    @RequirePermission(PermissionCodes.COST_READ)
    public CostInvocationPage invocations(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID modelId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return costQueryService.invocations(
                TenantId.of(actor.tenantId()), new CostQuery(from, to, null, page, size, userId, modelId));
    }
}
