package com.riccon.api.admin.model.catalog.repository;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.model.catalog.persistence.PlatformModelJdbc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台模型目录仓储。
 *
 * <p>只暴露全局目录只读查询与启停更新；模型创建、价格编辑与删除不在 M04 范围内
 * （ADR-0021 §7.1、ADR-0022 §2.1）。
 */
@Repository
public class PlatformModelRepository {

    /** 目录访问。 */
    private final PlatformModelJdbc modelJdbc;

    /**
     * 创建平台模型仓储。
     *
     * @param modelJdbc 目录访问
     */
    public PlatformModelRepository(PlatformModelJdbc modelJdbc) {
        this.modelJdbc = Objects.requireNonNull(modelJdbc, "modelJdbc must not be null");
    }

    /**
     * 读取全部模型。
     *
     * @return 目录行
     */
    public List<AdminModelRow> findAll() {
        return modelJdbc.findAll();
    }

    /**
     * 按 ID 读取模型。
     *
     * @param modelId 模型 ID
     * @return 目录行
     */
    public Optional<AdminModelRow> findById(UUID modelId) {
        return modelJdbc.findById(modelId);
    }

    /**
     * 更新模型状态。
     *
     * @param modelId 模型 ID
     * @param status ACTIVE 或 DISABLED
     * @return 受影响行数
     */
    public int updateStatus(UUID modelId, String status) {
        return modelJdbc.updateStatus(modelId, status);
    }
}
