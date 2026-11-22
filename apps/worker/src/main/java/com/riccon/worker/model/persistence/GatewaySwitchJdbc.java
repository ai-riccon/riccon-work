package com.riccon.worker.model.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 模型网关运行时开关读取（全局表）。
 *
 * <p>`model_gateway_switch` 是 ADR-0012 记录的全局例外表，不带 `tenant_id`，因此不能走
 * {@code TenantJdbc}。每次调用读取一次，不做跨请求缓存，保证“配置变更下一次请求生效”。
 */
@Component
public class GatewaySwitchJdbc {

    /** 全表读取；表只有 3 行。 */
    private static final String SELECT_SWITCHES = """
            SELECT switch_key, switch_value
              FROM model_gateway_switch
            """;

    /** 全局表只读客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建开关访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public GatewaySwitchJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 读取全部开关键值。
     *
     * @return 键值映射；表为空时返回空映射
     */
    public Map<String, String> findAll() {
        Map<String, String> values = new LinkedHashMap<>();
        jdbcClient
                .sql(SELECT_SWITCHES)
                .query((rs, rowNum) -> Map.entry(rs.getString("switch_key"), rs.getString("switch_value")))
                .list()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }
}
