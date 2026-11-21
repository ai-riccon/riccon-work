package com.riccon.api.admin.model.gateway.repository;

import com.riccon.api.admin.model.gateway.domain.GatewaySwitchRow;
import com.riccon.api.admin.model.gateway.persistence.ModelGatewaySwitchJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 网关开关仓储。
 *
 * <p>只暴露“读全部”和“按版本条件更新一行”；整组语义、审计与事务边界由服务层控制，避免把业务规则
 * 下沉到 SQL 层（ADR-0021 §3.4）。
 */
@Repository
public class ModelGatewaySwitchRepository {

    /** 开关读写组件。 */
    private final ModelGatewaySwitchJdbc switchJdbc;

    /**
     * 创建开关仓储。
     *
     * @param switchJdbc 开关读写组件
     */
    public ModelGatewaySwitchRepository(ModelGatewaySwitchJdbc switchJdbc) {
        this.switchJdbc = Objects.requireNonNull(switchJdbc, "switchJdbc must not be null");
    }

    /**
     * 读取全部开关。
     *
     * @return 开关行
     */
    public List<GatewaySwitchRow> findAll() {
        return switchJdbc.findAll();
    }

    /**
     * 按期望版本更新单个开关。
     *
     * @param key 开关键
     * @param expectedVersion 客户端读到的整组版本
     * @param value 新值
     * @param updatedBy 平台管理员账号 ID
     * @param updatedAt 修改时间（UTC）
     * @return 受影响行数；0 表示版本冲突
     */
    public int updateValue(String key, int expectedVersion, String value, UUID updatedBy, Instant updatedAt) {
        return switchJdbc.updateValue(key, expectedVersion, value, updatedBy, updatedAt);
    }
}
