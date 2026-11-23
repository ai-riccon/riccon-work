package com.riccon.api.knowledge.persistence;

import com.riccon.api.knowledge.domain.KnowledgeVisibilityScope;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 知识库文档可见性判定（单一谓词）。
 *
 * <p>ADR-0023 第 5.4 节要求列表、详情、删除、重索引、改 ACL 与下载共用同一谓词；本类是该谓词的唯一实现。
 * SQL 片段只使用 {@code knowledge_document} 与 {@code knowledge_document_acl}，不依赖 OpenSearch；
 * 部门状态按当前账号一次解析，避免逐行查询。
 *
 * <p>关闭 ACL 强制时谓词退化为租户级（等价 M03）；管理性读取（{@code TENANT_ADMIN}）不追加谓词，
 * 但调用方必须写 {@code knowledge.document.admin_read} 审计（ADR-0023 第 5.8、5.6 节）。
 */
@Component
public class KnowledgeAccessJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建可见性判定组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public KnowledgeAccessJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 解析当前账号的可见性上下文。
     *
     * @param tenantId 当前租户
     * @param userId 当前账号
     * @param unrestricted 是否管理性读取
     * @param enforceAcl 是否启用文档级 ACL
     * @return 可见性上下文
     */
    public KnowledgeVisibilityScope resolve(TenantId tenantId, UUID userId, boolean unrestricted, boolean enforceAcl) {
        if (!enforceAcl) {
            return new KnowledgeVisibilityScope(userId, null, false, false, false);
        }
        if (unrestricted) {
            return new KnowledgeVisibilityScope(userId, null, false, true, true);
        }
        Optional<DepartmentStatus> department = tenantJdbc.queryOne(
                tenantId, """
                SELECT u.department_id AS department_id, d.status AS department_status
                  FROM app_user u
                  LEFT JOIN department d
                    ON d.tenant_id = u.tenant_id
                   AND d.id = u.department_id
                 WHERE u.tenant_id = :tenantId
                   AND u.id = :userId
                   AND u.status = 'ACTIVE'
                """, Map.of("userId", BinaryUuids.toBytes(userId)), KnowledgeAccessJdbc::mapDepartmentStatus);
        return department
                .map(status ->
                        new KnowledgeVisibilityScope(userId, status.departmentId(), status.active(), false, true))
                // 找不到启用账号时按无部门处理：DEPT 分支恒假，列表/详情 fail-closed。
                .orElseGet(() -> new KnowledgeVisibilityScope(userId, null, false, false, true));
    }

    /**
     * 向 SQL 追加 ACL 谓词，并写入所需参数。
     *
     * <p>谓词语义：本租户 + （{@code TENANT} 全员，或同启用部门的 {@code DEPT}，或创建人，或
     * {@code knowledge_document_acl} 显式授权）。调用方必须同时带 {@code tenant_id} 与软删除条件。
     *
     * @param sql SQL 构造器
     * @param params 参数映射
     * @param scope 可见性上下文
     * @param alias 文档表别名
     */
    public void appendVisibilityPredicate(
            StringBuilder sql, Map<String, Object> params, KnowledgeVisibilityScope scope, String alias) {
        if (!scope.requiresAclPredicate()) {
            return;
        }
        Objects.requireNonNull(alias, "alias must not be null");
        sql.append(" AND (")
                .append(alias)
                .append(".visibility = '")
                .append(KnowledgeVisibility.TENANT.name())
                .append("'");
        if (scope.departmentId() != null && scope.departmentActive()) {
            sql.append(" OR (")
                    .append(alias)
                    .append(".visibility = '")
                    .append(KnowledgeVisibility.DEPT.name())
                    .append("' AND ")
                    .append(alias)
                    .append(".department_id = :aclDepartmentId)");
            params.put("aclDepartmentId", BinaryUuids.toBytes(scope.departmentId()));
        }
        sql.append(" OR ")
                .append(alias)
                .append(".created_by = :aclActorUserId")
                .append(" OR EXISTS (SELECT 1 FROM knowledge_document_acl acl WHERE acl.tenant_id = ")
                .append(alias)
                .append(".tenant_id AND acl.document_id = ")
                .append(alias)
                .append(".id AND acl.user_id = :aclActorUserId))");
        params.put("aclActorUserId", BinaryUuids.toBytes(scope.userId()));
    }

    /**
     * 映射账号部门与部门状态。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 部门状态
     * @throws SQLException 列读取失败
     */
    private static DepartmentStatus mapDepartmentStatus(ResultSet rs, int rowNum) throws SQLException {
        byte[] departmentId = rs.getBytes("department_id");
        return new DepartmentStatus(
                departmentId == null ? null : BinaryUuids.fromBytes(departmentId),
                "ACTIVE".equals(rs.getString("department_status")));
    }

    /**
     * 账号部门解析结果。
     *
     * @param departmentId 主部门；未分配为空
     * @param active 部门是否启用
     */
    private record DepartmentStatus(UUID departmentId, boolean active) {}
}
