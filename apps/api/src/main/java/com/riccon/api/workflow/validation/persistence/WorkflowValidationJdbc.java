package com.riccon.api.workflow.validation.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpToolRisk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 工作流校验只读 SQL。
 *
 * <p>模型、provider、MCP 工具与 server 是 ADR-0012 的全局目录例外，直接使用 {@link JdbcClient} 读取；
 * 工具授权与审批人属于租户数据，必须经 {@link TenantJdbc} 绑定 {@code tenant_id}。本类只读目录/授权/
 * 账号状态，不读取凭据、提示词或文档内容，也不写审计。
 */
@Component
public class WorkflowValidationJdbc {

    /** 指定模型在 REPORT 链的可用性元数据；不返回凭据。 */
    private static final String SELECT_REPORT_MODELS = """
            SELECT m.id,
                   m.status AS model_status,
                   p.code AS provider_code,
                   p.protocol AS provider_protocol,
                   p.base_url AS provider_base_url,
                   p.status AS provider_status,
                   EXISTS(
                       SELECT 1 FROM model_routing_entry e
                        WHERE e.model_id = m.id AND e.purpose = 'REPORT'
                   ) AS in_report_chain
              FROM model m
              JOIN model_provider p ON p.id = m.provider_id
             WHERE m.id IN (:ids)
            """;

    /** 指定工具的目录状态。 */
    private static final String SELECT_TOOLS = """
            SELECT t.id,
                   t.status AS tool_status,
                   t.risk_level,
                   t.requires_approval,
                   s.status AS server_status
              FROM mcp_tool t
              JOIN mcp_server s ON s.id = t.server_id
             WHERE t.id IN (:ids)
            """;

    /** 租户工具授权。 */
    private static final String SELECT_TOOL_GRANTS = """
            SELECT mcp_tool_id, enabled
              FROM mcp_tool_grant
             WHERE tenant_id = :tenantId
               AND mcp_tool_id IN (:ids)
            """;

    /** 统计指定账号中启用的数量，排除当前操作者。 */
    private static final String COUNT_ACTIVE_USERS = """
            SELECT COUNT(*)
              FROM app_user u
             WHERE u.tenant_id = :tenantId
               AND u.status = 'ACTIVE'
               AND u.id <> :excludedUserId
               AND u.id IN (:ids)
            """;

    /** 统计指定角色中启用的数量，排除当前操作者。 */
    private static final String COUNT_ACTIVE_ROLE_USERS = """
            SELECT COUNT(DISTINCT u.id)
              FROM app_user u
              JOIN user_role ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
              JOIN role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
             WHERE u.tenant_id = :tenantId
               AND u.status = 'ACTIVE'
               AND r.code = :roleCode
               AND u.id <> :excludedUserId
            """;

    /**
     * 当前账号负责的启用部门。
     *
     * <p>与发布范围规则一致：账号启用、部门是其主部门且启用、负责人是本人；只读取 ID，不返回成员或
     * 展示名。
     */
    private static final String SELECT_ACTIVE_MANAGED_DEPARTMENT = """
            SELECT d.id
              FROM app_user u
              JOIN department d ON d.tenant_id = u.tenant_id AND d.id = u.department_id
             WHERE u.tenant_id = :tenantId
               AND u.id = :userId
               AND u.status = 'ACTIVE'
               AND d.status = 'ACTIVE'
               AND d.manager_user_id = :userId
            """;

    /** 全局目录客户端。 */
    private final JdbcClient jdbcClient;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建校验 SQL 组件。
     *
     * @param jdbcClient 全局目录客户端
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowValidationJdbc(JdbcClient jdbcClient, TenantJdbc tenantJdbc) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取模型目录与 REPORT 链成员关系。
     *
     * @param modelIds 模型 ID；不得为空
     * @return 目录行；不存在的 ID 不返回
     */
    public List<ReportModelRow> findReportModels(Set<UUID> modelIds) {
        return jdbcClient
                .sql(SELECT_REPORT_MODELS)
                .params(Map.of(
                        "ids", modelIds.stream().map(BinaryUuids::toBytes).toList()))
                .query((rs, rowNum) -> new ReportModelRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("model_status"),
                        rs.getString("provider_code"),
                        rs.getString("provider_protocol"),
                        rs.getString("provider_base_url"),
                        rs.getString("provider_status"),
                        rs.getBoolean("in_report_chain")))
                .list();
    }

    /**
     * 读取 MCP 工具与 server 状态。
     *
     * @param toolIds 工具 ID；不得为空
     * @return 工具目录行；不存在的 ID 不返回
     */
    public List<ToolRow> findTools(Set<UUID> toolIds) {
        return jdbcClient
                .sql(SELECT_TOOLS)
                .params(Map.of("ids", toolIds.stream().map(BinaryUuids::toBytes).toList()))
                .query((rs, rowNum) -> new ToolRow(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        rs.getString("tool_status"),
                        McpToolRisk.valueOf(rs.getString("risk_level")),
                        rs.getBoolean("requires_approval"),
                        rs.getString("server_status")))
                .list();
    }

    /**
     * 读取租户工具授权开关。
     *
     * @param tenantId 当前租户
     * @param toolIds 工具 ID；不得为空
     * @return 工具 ID 到授权开关的映射；未授权不返回
     */
    public Map<UUID, Boolean> findToolGrants(TenantId tenantId, Set<UUID> toolIds) {
        Map<UUID, Boolean> grants = new LinkedHashMap<>();
        tenantJdbc
                .query(
                        tenantId,
                        SELECT_TOOL_GRANTS,
                        Map.of("ids", toolIds.stream().map(BinaryUuids::toBytes).toList()),
                        (rs, rowNum) -> new ToolGrantRow(
                                BinaryUuids.fromBytes(rs.getBytes("mcp_tool_id")), rs.getBoolean("enabled")))
                .forEach(row -> grants.put(row.toolId(), row.enabled()));
        return grants;
    }

    /**
     * 统计指定账号中启用的数量，排除当前操作者。
     *
     * @param tenantId 当前租户
     * @param userIds 账号 ID；不得为空
     * @param excludedUserId 需要排除的账号
     * @return 可用数量
     */
    public int countActiveUsers(TenantId tenantId, List<UUID> userIds, UUID excludedUserId) {
        long count = tenantJdbc.count(
                tenantId,
                COUNT_ACTIVE_USERS,
                Map.of(
                        "ids", userIds.stream().map(BinaryUuids::toBytes).toList(),
                        "excludedUserId", BinaryUuids.toBytes(excludedUserId)));
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * 统计指定角色中启用的数量，排除当前操作者。
     *
     * @param tenantId 当前租户
     * @param roleCode 角色码
     * @param excludedUserId 需要排除的账号
     * @return 可用数量
     */
    public int countActiveRoleUsers(TenantId tenantId, String roleCode, UUID excludedUserId) {
        long count = tenantJdbc.count(
                tenantId,
                COUNT_ACTIVE_ROLE_USERS,
                Map.of("roleCode", roleCode, "excludedUserId", BinaryUuids.toBytes(excludedUserId)));
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * 查询账号当前负责的启用部门。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @return 部门 ID；账号停用、无部门、部门停用或负责人不是本人时为空
     */
    public Optional<UUID> findActiveManagedDepartmentId(TenantId tenantId, UUID userId) {
        return tenantJdbc.queryOne(
                tenantId,
                SELECT_ACTIVE_MANAGED_DEPARTMENT,
                Map.of("userId", BinaryUuids.toBytes(userId)),
                (rs, rowNum) -> BinaryUuids.fromBytes(rs.getBytes("id")));
    }

    /**
     * 模型目录行。
     *
     * @param modelId 模型 ID
     * @param modelStatus 模型状态
     * @param providerCode provider 代码
     * @param providerProtocol provider 协议
     * @param providerBaseUrl provider 基础 URL
     * @param providerStatus provider 状态
     * @param inReportChain 是否位于 REPORT 路由链
     */
    public record ReportModelRow(
            UUID modelId,
            String modelStatus,
            String providerCode,
            String providerProtocol,
            String providerBaseUrl,
            String providerStatus,
            boolean inReportChain) {}

    /**
     * 工具目录行。
     *
     * @param toolId 工具 ID
     * @param toolStatus 工具状态
     * @param riskLevel 风险级别
     * @param requiresApproval 是否要求审批
     * @param serverStatus MCP server 状态
     */
    public record ToolRow(
            UUID toolId, String toolStatus, McpToolRisk riskLevel, boolean requiresApproval, String serverStatus) {}

    /**
     * 租户工具授权行。
     *
     * @param toolId 工具 ID
     * @param enabled 是否启用
     */
    private record ToolGrantRow(UUID toolId, boolean enabled) {}
}
