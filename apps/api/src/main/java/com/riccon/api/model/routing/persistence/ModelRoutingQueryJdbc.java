package com.riccon.api.model.routing.persistence;

import com.riccon.api.model.routing.domain.RoutingChain;
import com.riccon.api.model.routing.domain.RoutingEntry;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 模型路由链只读访问。
 *
 * <p>{@code model_routing} 与 {@code model_routing_entry} 是 ADR-0012/ADR-0021 记录的全局表，不带
 * {@code tenant_id}，因此直接使用 {@link JdbcClient} 读取而不是 {@code TenantJdbc}；本类没有写方法，
 * 写入口只存在于平台管理员服务。
 *
 * <p><b>不做跨请求缓存</b>：每次调用都读数据库，保证保存成功后下一次模型请求立即看到新链
 * （ADR-0021 §4.3）。链只有 CHAT/REPORT 两行，两次查询不会造成 N+1。
 */
@Component
public class ModelRoutingQueryJdbc {

    /** 链头查询；purpose 排序固定为 CHAT、REPORT，便于响应稳定。 */
    private static final String SELECT_HEADERS = """
            SELECT purpose, version, updated_by, updated_at
              FROM model_routing
             ORDER BY purpose
            """;

    /** 条目查询；一次取回两条链的条目，按用途与位置排序。 */
    private static final String SELECT_ENTRIES = """
            SELECT e.purpose,
                   e.position,
                   e.model_id,
                   m.display_name,
                   m.status AS model_status,
                   p.code AS provider_code,
                   p.base_url AS provider_base_url,
                   p.protocol AS provider_protocol,
                   p.status AS provider_status
              FROM model_routing_entry e
              JOIN model m ON m.id = e.model_id
              JOIN model_provider p ON p.id = m.provider_id
             ORDER BY e.purpose, e.position
            """;

    /** JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建路由链只读访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public ModelRoutingQueryJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取全部路由链。
     *
     * @return 按 purpose 升序的链；链头缺失时返回空列表
     */
    public List<RoutingChain> findAll() {
        List<HeaderRow> headers = jdbcClient
                .sql(SELECT_HEADERS)
                .query(ModelRoutingQueryJdbc::mapHeader)
                .list();
        if (headers.isEmpty()) {
            return List.of();
        }
        // 条目查询同时返回用途，按链分组；避免为两条链各跑一次 SQL。
        Map<String, List<RoutingEntry>> entriesByPurpose = new LinkedHashMap<>();
        for (EntryRow row : jdbcClient
                .sql(SELECT_ENTRIES)
                .query(ModelRoutingQueryJdbc::mapEntry)
                .list()) {
            entriesByPurpose
                    .computeIfAbsent(row.purpose(), key -> new ArrayList<>())
                    .add(row.entry());
        }
        List<RoutingChain> chains = new ArrayList<>(headers.size());
        for (HeaderRow header : headers) {
            chains.add(new RoutingChain(
                    ModelPurpose.valueOf(header.purpose()),
                    header.version(),
                    header.updatedAt(),
                    header.updatedBy(),
                    entriesByPurpose.getOrDefault(header.purpose(), List.of())));
        }
        return List.copyOf(chains);
    }

    /**
     * 按用途读取单条链。
     *
     * <p>路由链是部署级小表，复用 {@link #findAll()} 避免维护第二套 SQL；调用方只在保存后读取刚写入的链。
     *
     * @param purpose 用途
     * @return 链；未种子化时返回空
     */
    public Optional<RoutingChain> find(ModelPurpose purpose) {
        return findAll().stream().filter(chain -> chain.purpose() == purpose).findFirst();
    }

    /**
     * 映射链头行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 链头
     * @throws java.sql.SQLException 读取失败
     */
    private static HeaderRow mapHeader(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        byte[] updatedBy = rs.getBytes("updated_by");
        LocalDateTime updatedAt = rs.getObject("updated_at", LocalDateTime.class);
        return new HeaderRow(
                rs.getString("purpose"),
                rs.getInt("version"),
                updatedBy == null ? null : BinaryUuids.fromBytes(updatedBy),
                SqlTimestamps.toInstant(updatedAt));
    }

    /**
     * 映射条目行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 带用途的条目
     * @throws java.sql.SQLException 读取失败
     */
    private static EntryRow mapEntry(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EntryRow(
                rs.getString("purpose"),
                new RoutingEntry(
                        rs.getInt("position"),
                        BinaryUuids.fromBytes(rs.getBytes("model_id")),
                        rs.getString("display_name"),
                        rs.getString("model_status"),
                        rs.getString("provider_code"),
                        rs.getString("provider_base_url"),
                        rs.getString("provider_protocol"),
                        rs.getString("provider_status")));
    }

    /**
     * 链头行。
     *
     * @param purpose 用途
     * @param version 版本
     * @param updatedBy 最近修改人
     * @param updatedAt 最近保存时间（UTC）
     */
    private record HeaderRow(String purpose, int version, UUID updatedBy, Instant updatedAt) {}

    /**
     * 带用途的条目行。
     *
     * @param purpose 用途
     * @param entry 条目
     */
    private record EntryRow(String purpose, RoutingEntry entry) {}
}
