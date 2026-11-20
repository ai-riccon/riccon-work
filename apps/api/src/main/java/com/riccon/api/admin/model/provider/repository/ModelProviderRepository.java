package com.riccon.api.admin.model.provider.repository;

import com.riccon.api.admin.model.provider.domain.ProviderCatalogRow;
import com.riccon.api.admin.model.provider.persistence.ModelProviderJdbc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台供应商目录仓储。
 *
 * <p>只提供目录读取与展示名更新；端点地址、标签与协议始终由部署配置决定，仓储层不暴露写入入口
 * （ADR-0021 §2.4）。
 */
@Repository
public class ModelProviderRepository {

    /** 目录访问。 */
    private final ModelProviderJdbc providerJdbc;

    /**
     * 创建供应商仓储。
     *
     * @param providerJdbc 目录访问
     */
    public ModelProviderRepository(ModelProviderJdbc providerJdbc) {
        this.providerJdbc = Objects.requireNonNull(providerJdbc, "providerJdbc must not be null");
    }

    /**
     * 读取全部供应商。
     *
     * @return 目录行
     */
    public List<ProviderCatalogRow> findAll() {
        return providerJdbc.findAll();
    }

    /**
     * 按 ID 读取供应商。
     *
     * @param providerId 供应商 ID
     * @return 目录行
     */
    public Optional<ProviderCatalogRow> findById(UUID providerId) {
        return providerJdbc.findById(providerId);
    }

    /**
     * 更新展示名。
     *
     * @param providerId 供应商 ID
     * @param name 新展示名
     * @return 受影响行数
     */
    public int updateName(UUID providerId, String name) {
        return providerJdbc.updateName(providerId, name);
    }
}
