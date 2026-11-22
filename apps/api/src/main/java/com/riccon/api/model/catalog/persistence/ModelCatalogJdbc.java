package com.riccon.api.model.catalog.persistence;

import com.riccon.api.model.catalog.domain.CatalogModelRow;
import com.riccon.api.persistence.BinaryUuids;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型目录只读访问（全局表）。
 *
 * <p>{@code model} 与 {@code model_provider} 是 ADR-0012 记录的全局目录例外，不带 {@code tenant_id}，
 * 因此不使用 {@code TenantJdbc}；本类只读目录字段，不读取凭据、价格明细或调用数据。
 * 前端可见性裁剪在服务层按端点允许列表完成。
 */
@Component
public class ModelCatalogJdbc {

    /**
     * 读取全部模型与供应商目录；顺序固定为 provider code + model key，便于稳定分页。
     */
    private static final String SELECT_ALL = """
            SELECT m.id,
                   m.provider_id,
                   m.model_key,
                   m.display_name,
                   m.context_window,
                   m.max_output_tokens,
                   m.capabilities,
                   m.status AS model_status,
                   p.code AS provider_code,
                   p.protocol AS provider_protocol,
                   p.base_url AS provider_base_url,
                   p.status AS provider_status
              FROM model m
              JOIN model_provider p ON p.id = m.provider_id
             ORDER BY p.code, m.model_key
            """;

    /** JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建目录访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     * @param objectMapper JSON 解析器
     */
    public ModelCatalogJdbc(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取全部目录行。
     *
     * @return 目录行；目录为空返回空列表
     */
    public List<CatalogModelRow> findAll() {
        return jdbcClient
                .sql(SELECT_ALL)
                .query((rs, rowNum) -> new CatalogModelRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        BinaryUuids.fromBytes(rs.getBytes("provider_id")),
                        rs.getString("model_key"),
                        rs.getString("display_name"),
                        rs.getInt("context_window"),
                        rs.getInt("max_output_tokens"),
                        readJson(rs.getString("capabilities")),
                        rs.getString("model_status"),
                        rs.getString("provider_code"),
                        rs.getString("provider_protocol"),
                        rs.getString("provider_base_url"),
                        rs.getString("provider_status")))
                .list();
    }

    /**
     * 解析能力 JSON；空值返回 {@code null}。
     *
     * @param raw JSON 文本
     * @return JSON 树或 null
     */
    private JsonNode readJson(String raw) {
        return raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
    }
}
