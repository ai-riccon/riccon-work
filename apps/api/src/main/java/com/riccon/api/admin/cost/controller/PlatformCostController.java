package com.riccon.api.admin.cost.controller;

import com.riccon.api.admin.cost.dto.PlatformCostReport;
import com.riccon.api.admin.cost.service.PlatformCostService;
import com.riccon.api.cost.domain.CostQuery;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.platform.domain.identity.PermissionCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员跨租户成本接口。
 *
 * <p>需要 {@code platform.cost.read}；租户管理员与员工由 PermissionInterceptor 拒绝。
 * 只返回租户/模型/日聚合，不提供按用户明细；可选 {@code tenantId} 仅作过滤。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformCostController {

    /** 平台成本服务。 */
    private final PlatformCostService platformCostService;

    /**
     * 创建平台成本控制器。
     *
     * @param platformCostService 平台成本服务
     */
    public PlatformCostController(PlatformCostService platformCostService) {
        this.platformCostService = platformCostService;
    }

    /**
     * 跨租户成本聚合。
     *
     * @param from 起始日，可为空
     * @param to 结束日，可为空
     * @param groupBy 维度，可为空
     * @param tenantId 租户过滤，可为空
     * @param page 页码，可为空
     * @param size 每页大小，可为空
     * @return 平台成本报告
     */
    @GetMapping("/costs")
    @RequirePermission(PermissionCodes.PLATFORM_COST_READ)
    public PlatformCostReport report(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return platformCostService.report(new CostQuery(from, to, groupBy, page, size, null, null), tenantId);
    }
}
