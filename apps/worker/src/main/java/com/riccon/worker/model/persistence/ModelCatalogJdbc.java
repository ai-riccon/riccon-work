package com.riccon.worker.model.persistence;

import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ProviderDescriptor;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 模型目录只读访问（全局表）。
 *
 * <p>{@code model_provider} 与 {@code model} 是部署级全局表，不带 {@code tenant_id}（ADR-0012 例外），
 * 因此不能走 {@code TenantJdbc}。本类位于 {@code ..persistence..}，只允许读取目录与端点，
 * 不读取任何租户数据或凭据。
 */
@Component
public class ModelCatalogJdbc {

    /** 只返回可用的 OpenAI 兼容模型；按创建顺序稳定选取，M02 只有一项。 */
    private static final String SELECT_ACTIVE = """
            SELECT m.id, m.provider_id, m.model_key, m.display_name, m.context_window, m.max_output_tokens,
                   p.code AS provider_code, p.protocol, p.base_url
              FROM model m
              JOIN model_provider p ON p.id = m.provider_id
             WHERE m.status = 'ACTIVE'
               AND p.status = 'ACTIVE'
               AND p.protocol = 'OPENAI_COMPATIBLE'
             ORDER BY m.created_at, m.id
             LIMIT 1
            """;

    /** 全局目录 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建模型目录访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public ModelCatalogJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取第一个可用模型及其供应商。
     *
     * @return 模型与供应商；目录为空或都不可用时为空
     */
    public Optional<ModelCatalogEntry> findActive() {
        List<ModelCatalogEntry> rows = jdbcClient
                .sql(SELECT_ACTIVE)
                .query((rs, rowNum) -> {
                    ModelDescriptor model = new ModelDescriptor(
                            com.riccon.worker.persistence.UuidBinary.fromBytes(rs.getBytes("id")),
                            com.riccon.worker.persistence.UuidBinary.fromBytes(rs.getBytes("provider_id")),
                            rs.getString("model_key"),
                            rs.getString("display_name"),
                            rs.getInt("max_output_tokens"),
                            rs.getInt("context_window"));
                    ProviderDescriptor provider = new ProviderDescriptor(
                            model.providerId(),
                            rs.getString("provider_code"),
                            rs.getString("protocol"),
                            URI.create(rs.getString("base_url")));
                    return new ModelCatalogEntry(model, provider);
                })
                .list();
        return rows.stream().findFirst();
    }
}
