package com.riccon.api.workflow.persistence;

import com.riccon.api.persistence.BinaryUuids;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 全局内置模板只读访问。
 *
 * <p>{@code workflow_template} 是 ADR-0012 §4 全局表例外清单的 M06 扩展（ADR-0024 §3.3.7），不带
 * {@code tenant_id}，因此不使用 {@link com.riccon.api.persistence.TenantJdbc}。本类只读取
 * {@code BUILT_IN} 且启用的模板；用户模板在 M06b 启用，M06 不提供读写路径。
 *
 * <p>复制草稿只复制图与名称，不修改源模板；模板升级由 M06b 处理（AC-10/AC-25）。
 */
@Component
public class WorkflowDefinitionTemplateJdbc {

    /** JDBC 客户端；全局表无租户守卫。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建模板只读访问。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public WorkflowDefinitionTemplateJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 按 ID 读取启用的内置模板。
     *
     * @param templateId 模板 ID
     * @return 模板行
     */
    public Optional<WorkflowDefinitionTemplateRow> findBuiltIn(UUID templateId) {
        return jdbcClient
                .sql("""
                        SELECT id, name, description, graph_json, version
                          FROM workflow_template
                         WHERE id = :templateId
                           AND visibility = :visibility
                           AND status = :status
                        """)
                .param("templateId", BinaryUuids.toBytes(templateId))
                .param("visibility", "BUILT_IN")
                .param("status", "ACTIVE")
                .query((rs, rowNum) -> new WorkflowDefinitionTemplateRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("graph_json"),
                        rs.getInt("version")))
                .optional();
    }
}
