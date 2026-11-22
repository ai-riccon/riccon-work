package com.riccon.api.model.catalog.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.dto.EmployeeModelPageResponse;
import com.riccon.api.model.catalog.service.ModelCatalogService;
import com.riccon.platform.domain.identity.PermissionCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工模型目录接口。
 *
 * <p>{@code GET /api/v1/models} 使用 {@code model.read}；响应为独立 {@code EmployeeModel} 视图，
 * 不返回 provider、{@code baseUrl}、key 或价格（ADR-0021 §7.1/§7.5）。
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelCatalogController {

    /** 员工目录服务。 */
    private final ModelCatalogService modelCatalogService;

    /**
     * 创建员工模型目录控制器。
     *
     * @param modelCatalogService 员工目录服务
     */
    public ModelCatalogController(ModelCatalogService modelCatalogService) {
        this.modelCatalogService = modelCatalogService;
    }

    /**
     * 分页列出当前部署可路由的模型。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，1–100
     * @return 员工模型分页
     */
    @GetMapping
    @RequirePermission(PermissionCodes.MODEL_READ)
    public EmployeeModelPageResponse listModels(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return modelCatalogService.listModels(PageQuery.of(page, size));
    }
}
