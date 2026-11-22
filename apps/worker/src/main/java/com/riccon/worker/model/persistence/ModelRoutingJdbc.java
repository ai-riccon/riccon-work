package com.riccon.worker.model.persistence;

import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.model.domain.ModelDescriptor;
import com.riccon.worker.model.domain.ProviderDescriptor;
import com.riccon.worker.model.domain.RoutingChainEntry;
import com.riccon.worker.persistence.UuidBinary;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 模型路由链读取（全局表）。
 *
 * <p>`model_routing` / `model_routing_entry` 是全局表（ADR-0012 例外），不带 `tenant_id`；
 * 这里只读取链与目录元数据，不读取凭据。启停与协议过滤在 SQL 与 resolver 双重执行，
 * 保证停用模型即使仍在链中也永远不会被发起请求。
 */
@Component
public class ModelRoutingJdbc {

    /** 按 position 读取指定用途的链；只取可路由的模型/供应商。 */
    private static final String SELECT_ENTRIES = """
            SELECT e.position,
                   m.id, m.provider_id, m.model_key, m.display_name, m.context_window, m.max_output_tokens,
                   p.code AS provider_code, p.protocol, p.base_url
              FROM model_routing_entry e
              JOIN model m ON m.id = e.model_id
              JOIN model_provider p ON p.id = m.provider_id
             WHERE e.purpose = :purpose
               AND m.status = 'ACTIVE'
               AND p.status = 'ACTIVE'
               AND p.protocol = 'OPENAI_COMPATIBLE'
             ORDER BY e.position
            """;

    /** 全局表只读客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建路由链访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public ModelRoutingJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取指定用途的可路由链条目。
     *
     * @param purpose 用途
     * @return 按 position 排序的链；无链时为空列表
     */
    public List<RoutingChainEntry> findEntries(ModelPurpose purpose) {
        return jdbcClient
                .sql(SELECT_ENTRIES)
                .param("purpose", purpose.name())
                .query((rs, rowNum) -> {
                    ModelDescriptor model = new ModelDescriptor(
                            UuidBinary.fromBytes(rs.getBytes("id")),
                            UuidBinary.fromBytes(rs.getBytes("provider_id")),
                            rs.getString("model_key"),
                            rs.getString("display_name"),
                            rs.getInt("max_output_tokens"),
                            rs.getInt("context_window"));
                    ProviderDescriptor provider = new ProviderDescriptor(
                            model.providerId(),
                            rs.getString("provider_code"),
                            rs.getString("protocol"),
                            URI.create(rs.getString("base_url")));
                    return new RoutingChainEntry(rs.getInt("position"), model, provider);
                })
                .list();
    }
}
