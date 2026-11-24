package com.riccon.api.workflow.publish.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.workflow.publish.domain.WorkflowPublishLockRow;
import com.riccon.api.workflow.publish.domain.WorkflowVersionInsert;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 发布写入所需的租户 SQL。
 *
 * <p>本类只承担发布路径的“加锁 → 计算版本号 → 插入不可变版本 → 更新定义发布指针”。行锁保证同一流程的
 * 并发发布串行化；{@code UNIQUE (tenant_id, workflow_id, version_no)} 是最后一道唯一性约束。所有语句经
 * {@link TenantJdbc} 强制 {@code tenant_id} 条件，跨租户访问由服务层映射 404。
 *
 * <p>版本行没有 UPDATE/DELETE 语句：V15 触发器也会拒绝运行时修改或删除（ADR-0024 §3.3.2，TM-73）。
 */
@Component
public class WorkflowPublishJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建发布 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public WorkflowPublishJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 对流程定义行加排他锁并读取发布指针。
     *
     * <p>锁内的后续读取会看到最新草稿与发布指针；并发保存/移交/停用对同一行的更新会等待本事务结束，
     * 避免发布使用加锁前的旧草稿（AC-03）。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 行锁快照；流程不存在时为空
     */
    public Optional<WorkflowPublishLockRow> lockDefinition(TenantId tenantId, UUID workflowId) {
        return tenantJdbc.queryOne(
                tenantId, """
                SELECT d.id, d.published_version_id, d.published_version_no
                  FROM workflow_definition d
                 WHERE d.tenant_id = :tenantId AND d.id = :workflowId
                 FOR UPDATE
                """, Map.of("workflowId", BinaryUuids.toBytes(workflowId)), WorkflowPublishJdbc::mapLockRow);
    }

    /**
     * 计算下一个发布版本号。
     *
     * <p>调用方必须已持有同一流程定义行锁；否则两个发布可能读到相同 MAX 值。版本号只增不减，
     * 历史版本删除被触发器禁止。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @return 下一个版本号（首次发布为 1）
     */
    public int nextVersionNo(TenantId tenantId, UUID workflowId) {
        Integer next = tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT COALESCE(MAX(version_no), 0) + 1
                          FROM workflow_version
                         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                        """,
                        Map.of("workflowId", BinaryUuids.toBytes(workflowId)),
                        (rs, rowNum) -> rs.getInt(1))
                .orElse(1);
        return next;
    }

    /**
     * 插入不可变发布版本。
     *
     * @param tenantId 当前租户
     * @param insert 写入参数
     */
    public void insertVersion(TenantId tenantId, WorkflowVersionInsert insert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(insert.id()));
        params.put("workflowId", BinaryUuids.toBytes(insert.workflowId()));
        params.put("versionNo", insert.versionNo());
        params.put("graphJson", insert.graphJson());
        params.put("graphSha256", insert.graphSha256());
        params.put("nodeCount", insert.nodeCount());
        params.put("runScope", insert.runScope().name());
        params.put("departmentId", toBytes(insert.departmentId()));
        params.put("publishedBy", BinaryUuids.toBytes(insert.publishedBy()));
        params.put("publishedAt", SqlTimestamps.toDatabase(insert.publishedAt()));
        tenantJdbc.insert(tenantId, """
                INSERT INTO workflow_version (
                    tenant_id, id, workflow_id, version_no, graph_json, graph_sha256, node_count,
                    run_scope, department_id, published_by, published_at, created_at
                ) VALUES (
                    :tenantId, :id, :workflowId, :versionNo, :graphJson, :graphSha256, :nodeCount,
                    :runScope, :departmentId, :publishedBy, :publishedAt, :publishedAt
                )
                """, params);
    }

    /**
     * 更新流程定义的当前发布版本指针。
     *
     * @param tenantId 当前租户
     * @param workflowId 流程 ID
     * @param versionId 新发布版本 ID
     * @param versionNo 新发布版本号
     * @param updatedBy 发布人
     * @param now 更新时间
     * @return 受影响行数；0 表示流程在锁内消失（正常路径不应出现）
     */
    public int markPublished(
            TenantId tenantId, UUID workflowId, UUID versionId, int versionNo, UUID updatedBy, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflowId", BinaryUuids.toBytes(workflowId));
        params.put("versionId", BinaryUuids.toBytes(versionId));
        params.put("versionNo", versionNo);
        params.put("updatedBy", BinaryUuids.toBytes(updatedBy));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, """
                UPDATE workflow_definition
                   SET published_version_id = :versionId,
                       published_version_no = :versionNo,
                       updated_by = :updatedBy,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :workflowId
                """, params);
    }

    /**
     * 映射行锁快照。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 行锁快照
     * @throws SQLException 读取失败
     */
    private static WorkflowPublishLockRow mapLockRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowPublishLockRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("published_version_id")),
                rs.getObject("published_version_no", Integer.class));
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
}
