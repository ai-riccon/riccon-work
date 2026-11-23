package com.riccon.api.knowledge.persistence;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 知识库保留任务的跨租户目录查询。
 *
 * <p>跨租户读取只允许管理性维护任务使用；本类不读取任何租户业务内容，只读取 {@code tenant} 的启用状态与
 * 保留天数。业务表读写仍由 {@link KnowledgeJdbc} 在显式租户上下文中完成（ADR-0009）。
 */
@Component
public class KnowledgeRetentionJdbc {

    /** 平台目录查询；只用于跨租户维护任务。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建保留目录查询。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public KnowledgeRetentionJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 查询全部启用租户。
     *
     * <p>保留任务只按租户上下文执行；历史 NULL 行固定 180 天，不读取当前租户策略。
     *
     * @return 租户代码
     */
    public List<String> findActiveTenants() {
        return jdbcClient.sql("""
                        SELECT id FROM tenant
                         WHERE status = 'ACTIVE'
                         ORDER BY id ASC
                        """).query(String.class).list();
    }
}
