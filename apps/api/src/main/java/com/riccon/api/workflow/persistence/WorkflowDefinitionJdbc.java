package com.riccon.api.workflow.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSourceType;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionView;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code workflow_definition} 与只读发布版本摘要的租户 SQL。
 *
 * <p>所有业务查询经 {@link TenantJdbc} 强制 {@code tenant_id} 条件与线程租户一致；跨租户或不存在统一由
 * 服务层映射 404。草稿保存使用 {@code draft_version} 条件更新实现乐观锁，受影响行数为 0 即冲突。
 *
 * <p>列表 JOIN {@code workflow_version} 只读取 {@code run_scope}/{@code department_id}/{@code graph_sha256}，
 * 用于范围可见性与草稿落后判定；不读取 {@code graph_json}，不提供发布写入路径（T04）。
 */
@Component
public class WorkflowDefinitionJdbc {

    /** 流程定义查询列；发布哈希来自 LEFT JOIN 的发布版本。 */
    private static final String ROW_COLUMNS = """
            d.id, d.name, d.description, d.owner_user_id, d.draft_version, d.draft_graph, d.status,
            d.published_version_id, d.published_version_no, d.source_template_id, d.source_template_type,
            d.source_template_version, d.created_by, d.updated_by, d.created_at, d.updated_at,
            v.graph_sha256 AS published_graph_sha256
            """;

    /** 流程定义 FROM 与发布版本 JOIN。 */
    private static final String ROW_FROM = """
             FROM workflow_definition d
             LEFT JOIN workflow_version v
                ON v.tenant_id = d.tenant_id AND v.id = d.published_version_id
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建流程定义 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowDefinitionJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入新草稿。
     *
     * @param tenantId 当前租户
     * @param insert 写入参数
     */
    public void insert(TenantId tenantId, WorkflowDefinitionInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(insert.id()));
        params.put("name", insert.name());
        params.put("description", insert.description());
        params.put("ownerUserId", BinaryUuids.toBytes(insert.ownerUserId()));
        params.put("draftGraph", WorkflowGraphJson.toJson(insert.draftGraph()));
        params.put("status", WorkflowDefinitionStatus.ACTIVE.name());
        params.put("sourceTemplateId", toBytes(insert.sourceTemplateId()));
        params.put("sourceTemplateType", toDatabaseSourceType(insert.sourceTemplateType()));
        params.put("sourceTemplateVersion", insert.sourceTemplateVersion());
        params.put("actorId", BinaryUuids.toBytes(insert.actorId()));
        params.put("now", SqlTimestamps.toDatabase(insert.now()));
        tenantJdbc.insert(tenantId, """
                INSERT INTO workflow_definition (
                    tenant_id, id, name, description, owner_user_id, draft_version, draft_graph, status,
                    published_version_id, published_version_no, source_template_id, source_template_type,
                    source_template_version, created_by, updated_by, created_at, updated_at
                ) VALUES (
                    :tenantId, :id, :name, :description, :ownerUserId, 1, :draftGraph, :status,
                    NULL, NULL, :sourceTemplateId, :sourceTemplateType,
                    :sourceTemplateVersion, :actorId, :actorId, :now, :now
                )
                """, params);
    }

    /**
     * 按 ID 读取流程定义（含草稿图与发布哈希）。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 流程定义行
     */
    public Optional<WorkflowDefinitionRow> findById(TenantId tenantId, UUID workflowId) {
        return tenantJdbc.queryOne(
                tenantId,
                "SELECT " + ROW_COLUMNS + ROW_FROM + " WHERE d.tenant_id = :tenantId AND d.id = :workflowId",
                Map.of("workflowId", BinaryUuids.toBytes(workflowId)),
                this::mapRow);
    }

    /**
     * 分页查询可见流程定义。
     *
     * @param tenantId 当前租户
     * @param view 列表视图
     * @param status 可选状态过滤
     * @param userId 当前账号
     * @param departmentId 当前账号主部门；无部门时为空
     * @param tenantAdmin 是否租户管理员
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 流程定义行
     */
    public List<WorkflowDefinitionRow> list(
            TenantId tenantId,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            UUID userId,
            UUID departmentId,
            boolean tenantAdmin,
            int limit,
            int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(ROW_COLUMNS).append(ROW_FROM);
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(sql, params, view, status, userId, departmentId, tenantAdmin);
        sql.append(" ORDER BY d.updated_at DESC, d.id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, sql.toString(), params, this::mapRow);
    }

    /**
     * 统计可见流程定义。
     *
     * @param tenantId 当前租户
     * @param view 列表视图
     * @param status 可选状态过滤
     * @param userId 当前账号
     * @param departmentId 当前账号主部门；无部门时为空
     * @param tenantAdmin 是否租户管理员
     * @return 总数
     */
    public long count(
            TenantId tenantId,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            UUID userId,
            UUID departmentId,
            boolean tenantAdmin) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(ROW_FROM);
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(sql, params, view, status, userId, departmentId, tenantAdmin);
        return tenantJdbc.count(tenantId, sql.toString(), params);
    }

    /**
     * 按乐观锁版本更新草稿。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param expectedVersion 客户端持有的草稿版本
     * @param name 新名称；为空表示不修改
     * @param description 新说明
     * @param descriptionPresent 是否显式提交说明（用于区分“不修改”与“清空”）
     * @param graph 新草稿图
     * @param updatedBy 修改人
     * @param now 修改时间
     * @return 受影响行数；0 表示版本冲突
     */
    public int updateDraft(
            TenantId tenantId,
            UUID workflowId,
            int expectedVersion,
            String name,
            String description,
            boolean descriptionPresent,
            WorkflowGraph graph,
            UUID updatedBy,
            Instant now) {
        StringBuilder sql = new StringBuilder("""
                UPDATE workflow_definition
                   SET draft_graph = :draftGraph,
                       draft_version = draft_version + 1,
                       updated_by = :updatedBy,
                       updated_at = :now
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", BinaryUuids.toBytes(workflowId));
        params.put("expectedVersion", expectedVersion);
        if (name != null) {
            sql.append(", name = :name");
            params.put("name", name);
        }
        if (descriptionPresent) {
            sql.append(", description = :description");
            params.put("description", description);
        }
        sql.append(" WHERE tenant_id = :tenantId AND id = :workflowId AND draft_version = :expectedVersion");
        params.put("draftGraph", WorkflowGraphJson.toJson(graph));
        params.put("updatedBy", BinaryUuids.toBytes(updatedBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, sql.toString(), params);
    }

    /**
     * 按版本 ID 读取发布版本摘要。
     *
     * @param tenantId 当前租户
     * @param versionId 版本 ID
     * @return 发布版本摘要
     */
    public Optional<WorkflowDefinitionPublishedVersionRow> findPublishedVersion(TenantId tenantId, UUID versionId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT v.id, v.workflow_id, v.version_no, v.run_scope, v.department_id,
                       v.graph_sha256, v.node_count, v.published_by, v.published_at
                  FROM workflow_version v
                 WHERE v.tenant_id = :tenantId AND v.id = :versionId
                """,
                Map.of("versionId", BinaryUuids.toBytes(versionId)),
                WorkflowDefinitionJdbc::mapPublishedVersion);
    }

    /**
     * 追加列表视图过滤条件。
     *
     * <p>{@code MINE} 对普通用户包含本人草稿与按范围可见的已发布流程；{@code RUNNABLE} 只包含已发布、
     * 启用且可见的流程；未发布草稿绝不进入 {@code RUNNABLE}（ADR-0024 §6.2，AC-01）。
     *
     * @param sql SQL 构建器
     * @param params 参数
     * @param view 视图
     * @param status 可选状态
     * @param userId 当前账号
     * @param departmentId 当前部门
     * @param tenantAdmin 是否租户管理员
     */
    private static void appendFilters(
            StringBuilder sql,
            Map<String, Object> params,
            WorkflowDefinitionView view,
            WorkflowDefinitionStatus status,
            UUID userId,
            UUID departmentId,
            boolean tenantAdmin) {
        sql.append(" WHERE d.tenant_id = :tenantId");
        if (status != null) {
            sql.append(" AND d.status = :status");
            params.put("status", status.name());
        }
        params.put("userId", BinaryUuids.toBytes(userId));
        if (view == WorkflowDefinitionView.RUNNABLE) {
            sql.append(" AND d.published_version_id IS NOT NULL AND d.status = :activeStatus");
            params.put("activeStatus", WorkflowDefinitionStatus.ACTIVE.name());
            if (!tenantAdmin) {
                sql.append(" AND (");
                appendScopePredicate(sql, params, departmentId);
                sql.append(")");
            }
            return;
        }
        if (view == WorkflowDefinitionView.MINE && !tenantAdmin) {
            sql.append(" AND (d.owner_user_id = :userId OR (d.published_version_id IS NOT NULL AND (");
            appendScopePredicate(sql, params, departmentId);
            sql.append(")))");
        }
        // ALL 仅租户管理员可达，服务层已拒绝其他主体；MINE + 租户管理员返回本租户全部。
    }

    /**
     * 追加发布版本范围谓词；条件为 AND 内的一个括号表达式。
     *
     * @param sql SQL 构建器
     * @param params 参数
     * @param departmentId 当前部门
     */
    private static void appendScopePredicate(StringBuilder sql, Map<String, Object> params, UUID departmentId) {
        sql.append("v.run_scope = :tenantScope").append(" OR (v.run_scope = :selfScope AND d.owner_user_id = :userId)");
        params.put("tenantScope", WorkflowRunScope.TENANT.name());
        params.put("selfScope", WorkflowRunScope.ONLY_SELF.name());
        if (departmentId != null) {
            sql.append(" OR (v.run_scope = :departmentScope AND v.department_id = :departmentId)");
            params.put("departmentScope", WorkflowRunScope.DEPARTMENT.name());
            params.put("departmentId", BinaryUuids.toBytes(departmentId));
        }
    }

    /**
     * 映射流程定义行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 流程定义行
     * @throws SQLException 读取失败
     */
    private WorkflowDefinitionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowDefinitionRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                rs.getString("description"),
                BinaryUuids.fromBytes(rs.getBytes("owner_user_id")),
                rs.getInt("draft_version"),
                parseGraph(rs.getString("draft_graph")),
                WorkflowDefinitionStatus.valueOf(rs.getString("status")),
                BinaryUuids.fromBytes(rs.getBytes("published_version_id")),
                rs.getObject("published_version_no", Integer.class),
                rs.getString("published_graph_sha256"),
                BinaryUuids.fromBytes(rs.getBytes("source_template_id")),
                fromDatabaseSourceType(rs.getString("source_template_type")),
                rs.getObject("source_template_version", Integer.class),
                BinaryUuids.fromBytes(rs.getBytes("created_by")),
                BinaryUuids.fromBytes(rs.getBytes("updated_by")),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    /**
     * 映射发布版本摘要行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 发布版本摘要
     * @throws SQLException 读取失败
     */
    private static WorkflowDefinitionPublishedVersionRow mapPublishedVersion(ResultSet rs, int rowNum)
            throws SQLException {
        return new WorkflowDefinitionPublishedVersionRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workflow_id")),
                rs.getInt("version_no"),
                WorkflowRunScope.valueOf(rs.getString("run_scope")),
                BinaryUuids.fromBytes(rs.getBytes("department_id")),
                rs.getString("graph_sha256"),
                rs.getInt("node_count"),
                BinaryUuids.fromBytes(rs.getBytes("published_by")),
                SqlTimestamps.toInstant(rs.getObject("published_at", LocalDateTime.class)));
    }

    /**
     * 解析数据库草稿图；损坏 JSON 视为数据错误而不是空图。
     *
     * @param json 图 JSON
     * @return 图
     */
    private static WorkflowGraph parseGraph(String json) {
        try {
            return WorkflowGraphJson.parse(json);
        } catch (WorkflowGraphFormatException exception) {
            throw new IllegalStateException("invalid workflow_definition.draft_graph", exception);
        }
    }

    /**
     * 空安全的 UUID 字节转换。
     *
     * @param uuid UUID；可为空
     * @return 16 字节数组或 {@code null}
     */
    private static byte[] toBytes(UUID uuid) {
        return uuid == null ? null : BinaryUuids.toBytes(uuid);
    }

    /**
     * 将内部来源类型转换为数据库值。
     *
     * @param sourceType 来源类型；可为空
     * @return 数据库值或 {@code null}
     */
    private static String toDatabaseSourceType(WorkflowDefinitionSourceType sourceType) {
        return sourceType == null ? null : sourceType.auditValue();
    }

    /**
     * 将数据库来源类型转换为内部枚举。
     *
     * @param value 数据库值；可为空
     * @return 内部枚举或 {@code null}
     */
    private static WorkflowDefinitionSourceType fromDatabaseSourceType(String value) {
        return value == null ? null : WorkflowDefinitionSourceType.fromDatabase(value);
    }
}
