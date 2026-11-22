package com.riccon.api.model.catalog.service;

import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.model.catalog.domain.CatalogModelRow;
import com.riccon.api.model.catalog.dto.EmployeeModelPageResponse;
import com.riccon.api.model.catalog.dto.EmployeeModelResponse;
import com.riccon.api.model.catalog.repository.ModelCatalogRepository;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 员工模型目录服务。
 *
 * <p>只返回“当前部署模式下可路由”的模型：provider 在部署允许列表、标签与部署模式兼容、模型与 provider
 * 均 ACTIVE、协议为 {@code OPENAI_COMPATIBLE}。不可路由模型不返回，避免员工看到永远不可用的条目
 * （ADR-0021 §7.5）。
 */
@Service
public class ModelCatalogService {

    /** 目录仓储。 */
    private final ModelCatalogRepository catalogRepository;

    /** 部署端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /**
     * 创建员工目录服务。
     *
     * @param catalogRepository 目录仓储
     * @param endpointRegistry 部署端点允许列表
     */
    public ModelCatalogService(ModelCatalogRepository catalogRepository, ManagedEndpointRegistry endpointRegistry) {
        this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    }

    /**
     * 分页列出员工可见模型。
     *
     * <p>目录是部署级小表，先过滤再在内存分页，避免为不同过滤组合维护多套 SQL；排序已由 SQL 固定，
     * 分页结果稳定可复现。
     *
     * @param page 分页参数
     * @return 员工模型分页
     */
    @Transactional(readOnly = true)
    public EmployeeModelPageResponse listModels(PageQuery page) {
        List<EmployeeModelResponse> routable = catalogRepository.findAll().stream()
                .filter(this::isRoutable)
                .map(ModelCatalogService::toResponse)
                .toList();
        int total = routable.size();
        int from = Math.min(page.offset(), total);
        int to = Math.min(from + page.size(), total);
        return new EmployeeModelPageResponse(
                page.page(), page.size(), total, page.totalPages(total), List.copyOf(routable.subList(from, to)));
    }

    /**
     * 判断目录行是否可路由。
     *
     * @param row 目录行
     * @return 可路由返回 true
     */
    private boolean isRoutable(CatalogModelRow row) {
        return "ACTIVE".equals(row.modelStatus())
                && "ACTIVE".equals(row.providerStatus())
                && "OPENAI_COMPATIBLE".equals(row.providerProtocol())
                && endpointRegistry.isAllowed(row.providerCode(), row.providerBaseUrl());
    }

    /**
     * 转换为员工模型响应；只保留合同允许的字段，不带 provider/baseUrl/价格。
     *
     * @param row 目录行
     * @return 员工模型
     */
    private static EmployeeModelResponse toResponse(CatalogModelRow row) {
        return new EmployeeModelResponse(
                row.modelId().toString(),
                row.modelKey(),
                row.displayName(),
                row.contextWindow(),
                row.maxOutputTokens(),
                row.capabilities(),
                row.modelStatus());
    }
}
