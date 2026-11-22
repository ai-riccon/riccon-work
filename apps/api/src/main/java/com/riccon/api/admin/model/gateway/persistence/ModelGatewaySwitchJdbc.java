package com.riccon.api.admin.model.gateway.persistence;

import com.riccon.api.admin.model.gateway.domain.GatewaySwitchRow;
import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 网关开关全局表读写（{@code model_gateway_switch}）。
 *
 * <p>该表是 ADR-0012/ADR-0021 记录的全局表，不带 {@code tenant_id}，因此经 {@link PlatformJdbc} 访问；
 * 每次读取都查数据库、不做跨请求缓存，保证保存成功后下一次请求立即生效。条件更新
 * {@code WHERE version = :version} 是乐观锁最终防线，影响 0 行表示并发冲突（ADR-0021 §3.4）。
 */
@Component
public class ModelGatewaySwitchJdbc {

    /** 读取全部开关；固定键顺序便于日志与断言稳定。 */
    private static final String SELECT_SWITCHES = """
            SELECT switch_key, switch_value, version, updated_by, updated_at
              FROM model_gateway_switch
             ORDER BY switch_key
            """;

    /** 按期望版本更新单个开关；更新人与时间由服务层统一写入，三行保持同一版本与时间。 */
    private static final String UPDATE_VALUE = """
            UPDATE model_gateway_switch
               SET switch_value = :switchValue,
                   version = version + 1,
                   updated_by = :updatedBy,
                   updated_at = :updatedAt
             WHERE switch_key = :switchKey
               AND version = :version
            """;

    /** 平台全局表入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建网关开关读写组件。
     *
     * @param platformJdbc 平台全局表入口
     */
    public ModelGatewaySwitchJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 读取全部开关。
     *
     * @return 开关行；迁移未种子化时为空列表
     */
    public List<GatewaySwitchRow> findAll() {
        return platformJdbc.queryGlobal(SELECT_SWITCHES, Map.of(), ModelGatewaySwitchJdbc::mapRow);
    }

    /**
     * 按期望版本更新单个开关。
     *
     * @param key 开关键
     * @param expectedVersion 客户端读到的整组版本
     * @param value 新值
     * @param updatedBy 平台管理员账号 ID
     * @param updatedAt 修改时间（UTC）
     * @return 受影响行数；0 表示版本冲突或该行不存在
     */
    public int updateValue(String key, int expectedVersion, String value, UUID updatedBy, java.time.Instant updatedAt) {
        return platformJdbc.updateGlobal(
                UPDATE_VALUE,
                Map.of(
                        "switchKey", key,
                        "version", expectedVersion,
                        "switchValue", value,
                        "updatedBy", BinaryUuids.toBytes(updatedBy),
                        "updatedAt", SqlTimestamps.toDatabase(updatedAt)));
    }

    /**
     * 映射开关行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 开关行
     * @throws java.sql.SQLException 读取失败
     */
    private static GatewaySwitchRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        byte[] updatedBy = rs.getBytes("updated_by");
        LocalDateTime updatedAt = rs.getObject("updated_at", LocalDateTime.class);
        return new GatewaySwitchRow(
                rs.getString("switch_key"),
                rs.getString("switch_value"),
                rs.getInt("version"),
                updatedBy == null ? null : BinaryUuids.fromBytes(updatedBy),
                SqlTimestamps.toInstant(updatedAt));
    }
}
