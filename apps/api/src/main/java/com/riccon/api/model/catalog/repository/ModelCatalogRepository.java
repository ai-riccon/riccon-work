package com.riccon.api.model.catalog.repository;

import com.riccon.api.model.catalog.domain.CatalogModelRow;
import com.riccon.api.model.catalog.persistence.ModelCatalogJdbc;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 模型目录仓储。
 *
 * <p>只暴露全局目录的只读查询；目录由部署种子/运维维护，M04 不提供模型创建或价格编辑接口
 * （ADR-0021 §7.1、ADR-0022 §2.1）。
 */
@Repository
public class ModelCatalogRepository {

    /** 目录访问。 */
    private final ModelCatalogJdbc catalogJdbc;

    /**
     * 创建目录仓储。
     *
     * @param catalogJdbc 目录访问
     */
    public ModelCatalogRepository(ModelCatalogJdbc catalogJdbc) {
        this.catalogJdbc = Objects.requireNonNull(catalogJdbc, "catalogJdbc must not be null");
    }

    /**
     * 读取全部模型目录行。
     *
     * @return 目录行
     */
    public List<CatalogModelRow> findAll() {
        return catalogJdbc.findAll();
    }
}
