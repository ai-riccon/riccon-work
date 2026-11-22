package com.riccon.api.admin.model.catalog.persistence;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.persistence.BinaryUuids;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台模型目录访问。
 *
 * <p>目录是全局表；只读取价格快照与目录元数据，不读取任何凭据或调用明细。状态更新只允许
 * {@code ACTIVE|DISABLED}，由服务层校验后写入（ADR-0021 §7.1）。
 */
@Component
public class PlatformModelJdbc {

    /** 目录查询；顺序固定，便于稳定分页。 */
    private static final String SELECT_ALL = """
            SELECT m.id,
                   m.provider_id,
                   m.model_key,
                   m.display_name,
                   m.context_window,
                   m.max_output_tokens,
                   m.capabilities,
                   m.input_price_per_1m,
                   m.output_price_per_1m,
                   m.status AS model_status,
                   p.code AS provider_code,
                   p.protocol AS provider_protocol,
                   p.base_url AS provider_base_url,
                   p.status AS provider_status
              FROM model m
              JOIN model_provider p ON p.id = m.provider_id
             ORDER BY p.code, m.model_key
            """;

    /** 单模型查询。 */
    private static final String SELECT_ONE = """
            SELECT m.id,
                   m.provider_id,
                   m.model_key,
                   m.display_name,
                   m.context_window,
                   m.max_output_tokens,
                   m.capabilities,
                   m.input_price_per_1m,
                   m.output_price_per_1m,
                   m.status AS model_status,
                   p.code AS provider_code,
                   p.protocol AS provider_protocol,
                   p.base_url AS provider_base_url,
                   p.status AS provider_status
              FROM model m
              JOIN model_provider p ON p.id = m.provider_id
             WHERE m.id = :id
            """;

    /** 平台管理全局表入口。 */
    private final PlatformJdbc platformJdbc;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建平台模型目录访问。
     *
     * @param platformJdbc 平台管理全局表入口
     * @param objectMapper JSON 解析器
     */
    public PlatformModelJdbc(PlatformJdbc platformJdbc, ObjectMapper objectMapper) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取全部模型。
     *
     * @return 目录行
     */
    public List<AdminModelRow> findAll() {
        return platformJdbc.queryGlobal(SELECT_ALL, Map.of(), this::mapRow);
    }

    /**
     * 按 ID 读取模型。
     *
     * @param modelId 模型 ID
     * @return 目录行
     */
    public Optional<AdminModelRow> findById(UUID modelId) {
        return platformJdbc.queryOneGlobal(SELECT_ONE, Map.of("id", BinaryUuids.toBytes(modelId)), this::mapRow);
    }

    /**
     * 更新模型启停状态。
     *
     * @param modelId 模型 ID
     * @param status ACTIVE 或 DISABLED
     * @return 受影响行数
     */
    public int updateStatus(UUID modelId, String status) {
        return platformJdbc.updateGlobal(
                "UPDATE model SET status = :status WHERE id = :id",
                Map.of("id", BinaryUuids.toBytes(modelId), "status", status));
    }

    /**
     * 映射目录行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 目录行
     * @throws java.sql.SQLException 读取失败
     */
    private AdminModelRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String capabilities = rs.getString("capabilities");
        JsonNode capabilitiesJson =
                capabilities == null || capabilities.isBlank() ? null : objectMapper.readTree(capabilities);
        return new AdminModelRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("provider_id")),
                rs.getString("model_key"),
                rs.getString("display_name"),
                rs.getInt("context_window"),
                rs.getInt("max_output_tokens"),
                capabilitiesJson,
                rs.getBigDecimal("input_price_per_1m"),
                rs.getBigDecimal("output_price_per_1m"),
                rs.getString("model_status"),
                rs.getString("provider_code"),
                rs.getString("provider_protocol"),
                rs.getString("provider_base_url"),
                rs.getString("provider_status"));
    }
}
