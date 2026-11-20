package com.riccon.api.admin.model.provider.service;

import com.riccon.api.admin.model.provider.domain.ProviderCatalogRow;
import com.riccon.api.admin.model.provider.dto.ModelProviderPageResponse;
import com.riccon.api.admin.model.provider.dto.ModelProviderResponse;
import com.riccon.api.admin.model.provider.repository.ModelProviderRepository;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.platform.common.model.EndpointEnvironmentLabel;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台供应商目录服务。
 *
 * <p>标签与允许状态全部由部署允许列表派生：{@code allowed=false} 表示 code 不在列表、baseUrl 与配置不一致
 * 或演示端点在 PRODUCTION 下不可路由；DB 行不会被自动改写（ADR-0021 §2.1/§2.3）。
 */
@Service
public class ModelProviderService {

    /** 供应商仓储。 */
    private final ModelProviderRepository providerRepository;

    /** 部署端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry;

    /**
     * 创建供应商目录服务。
     *
     * @param providerRepository 供应商仓储
     * @param endpointRegistry 部署端点允许列表
     */
    public ModelProviderService(ModelProviderRepository providerRepository, ManagedEndpointRegistry endpointRegistry) {
        this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    }

    /**
     * 分页列出供应商目录。
     *
     * @param page 分页参数
     * @return 供应商分页
     */
    @Transactional(readOnly = true)
    public ModelProviderPageResponse listProviders(PageQuery page) {
        List<ModelProviderResponse> all =
                providerRepository.findAll().stream().map(this::toResponse).toList();
        int total = all.size();
        int from = Math.min(page.offset(), total);
        int to = Math.min(from + page.size(), total);
        return new ModelProviderPageResponse(
                page.page(), page.size(), total, page.totalPages(total), List.copyOf(all.subList(from, to)));
    }

    /**
     * 修改供应商展示名。
     *
     * <p>只更新 {@code name}；部署字段由控制器先行拒绝，服务层不提供写入口。未配置端点的目录行不会被自动
     * 建立或删除，新增端点必须走部署配置 + 种子脚本（ADR-0021 §2.4/§10.2）。
     *
     * @param providerId 供应商 ID
     * @param name 新展示名
     * @return 更新后的供应商
     * @throws NotFoundException 供应商不存在
     */
    @Transactional
    public ModelProviderResponse renameProvider(UUID providerId, String name) {
        providerRepository.findById(providerId).orElseThrow(() -> new NotFoundException("model provider"));
        providerRepository.updateName(providerId, name);
        ProviderCatalogRow updated =
                providerRepository.findById(providerId).orElseThrow(() -> new NotFoundException("model provider"));
        return toResponse(updated);
    }

    /**
     * 映射目录行。
     *
     * @param row 目录行
     * @return 供应商响应
     */
    private ModelProviderResponse toResponse(ProviderCatalogRow row) {
        // 未配置端点的历史目录行没有部署标签；用安全默认 PRODUCTION + allowed=false 表达“不可路由”，
        // 不伪造演示标签，也不因为目录残留而让运行时放行。
        String label = endpointRegistry
                .labelFor(row.code())
                .map(EndpointEnvironmentLabel::name)
                .orElse(EndpointEnvironmentLabel.PRODUCTION.name());
        boolean allowed =
                "OPENAI_COMPATIBLE".equals(row.protocol()) && endpointRegistry.isAllowed(row.code(), row.baseUrl());
        return new ModelProviderResponse(
                row.id().toString(),
                row.code(),
                row.name(),
                row.protocol(),
                row.baseUrl(),
                label,
                allowed,
                row.activeCredentialCount(),
                row.modelCount(),
                row.status());
    }
}
