package com.riccon.worker.mcp.persistence;

import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.platform.domain.mcp.McpTransport;
import com.riccon.worker.mcp.domain.McpServerDescriptor;
import com.riccon.worker.mcp.domain.McpToolDescriptor;
import com.riccon.worker.persistence.UuidBinary;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 全局目录只读访问。
 *
 * <p>{@code mcp_server} 与 {@code mcp_tool} 是平台内置白名单（ADR-0012 例外），不带
 * {@code tenant_id}，因此使用 {@link JdbcClient}；本类只读取目录，不读取租户授权或调用数据。
 */
@Component
public class McpCatalogJdbc {

    /** server 查询。 */
    private static final String SELECT_SERVER = """
            SELECT id, code, name, transport, endpoint, command, credential_ref, status
              FROM mcp_server
             WHERE id = :id
            """;

    /** tool 查询。 */
    private static final String SELECT_TOOL = """
            SELECT id, server_id, name, description, input_schema, risk_level, requires_approval, status
              FROM mcp_tool
             WHERE id = :id
            """;

    /** 全局 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 MCP 目录访问。
     *
     * @param jdbcClient JDBC 客户端
     * @param objectMapper JSON 解析器
     */
    public McpCatalogJdbc(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 按 ID 读取 server。
     *
     * @param id server ID
     * @return server 快照
     */
    public Optional<McpServerDescriptor> findServer(UUID id) {
        return jdbcClient
                .sql(SELECT_SERVER)
                .param("id", UuidBinary.toBytes(id))
                .query((rs, rowNum) -> new McpServerDescriptor(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        McpTransport.valueOf(rs.getString("transport")),
                        rs.getString("endpoint") == null ? null : URI.create(rs.getString("endpoint")),
                        rs.getString("command"),
                        rs.getString("credential_ref"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }

    /**
     * 按 ID 读取 tool。
     *
     * @param id tool ID
     * @return tool 快照
     */
    public Optional<McpToolDescriptor> findTool(UUID id) {
        return jdbcClient
                .sql(SELECT_TOOL)
                .param("id", UuidBinary.toBytes(id))
                .query((rs, rowNum) -> new McpToolDescriptor(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("server_id")),
                        rs.getString("name"),
                        rs.getString("description"),
                        parseSchema(rs.getString("input_schema")),
                        McpToolRisk.valueOf(rs.getString("risk_level")),
                        rs.getBoolean("requires_approval"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }

    /**
     * 解析 input_schema JSON；缺失时返回通用 object schema。
     *
     * @param json JSON 文本
     * @return schema 映射
     */
    private Map<String, Object> parseSchema(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("type", "object");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null || parsed.isEmpty() ? Map.of("type", "object") : parsed;
        } catch (JacksonException ex) {
            // 平台目录由迁移/管理员写入，非法 JSON 属于配置错误；直接失败而不是发送空 schema。
            throw new IllegalStateException("mcp_tool.input_schema is not valid JSON", ex);
        }
    }
}
