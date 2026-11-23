package com.riccon.api.chat.persistence;

import com.riccon.api.chat.domain.CitationSourceState;
import com.riccon.api.knowledge.domain.KnowledgeVisibilityScope;
import com.riccon.api.knowledge.persistence.KnowledgeAccessJdbc;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 引用源文档状态批量查询。
 *
 * <p>一次查询覆盖一页引用涉及的全部 {@code knowledge_document}，按文档 ID 返回 ACL 可见性、软删除标记与当前
 * checksum，供聊天消息引用与产物引用在同一请求内复用（ADR-0023 第 5.4 节禁止逐条 N+1）。ACL 谓词直接复用
 * 知识库模块的唯一实现 {@link KnowledgeAccessJdbc#appendVisibilityPredicate}，不在引用侧复制权限逻辑。
 *
 * <p>查询不过滤 {@code deleted_at} 与不可见行：删除与不可访问的优先级由上层状态计算决定，避免把
 * “已删除”误判为“不可访问”。跨租户或不存在文档不会出现在结果中，上层按 {@code NO_ACCESS} 处理，不泄露存在性。
 */
@Component
public class CitationStatusJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** 知识库文档可见性谓词。 */
    private final KnowledgeAccessJdbc accessJdbc;

    /**
     * 创建引用状态查询。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param accessJdbc 知识库可见性谓词
     */
    public CitationStatusJdbc(TenantJdbc tenantJdbc, KnowledgeAccessJdbc accessJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.accessJdbc = Objects.requireNonNull(accessJdbc, "accessJdbc must not be null");
    }

    /**
     * 批量读取文档状态。
     *
     * @param tenantId 当前租户
     * @param scope 当前账号的可见性上下文
     * @param documentIds 引用涉及的文档 ID
     * @return 文档 ID 到状态的映射；不可见或跨租户文档不返回
     */
    public Map<UUID, CitationSourceState> findStates(
            TenantId tenantId, KnowledgeVisibilityScope scope, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT d.id, d.deleted_at, d.checksum, CASE WHEN 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        // ACL 谓词以 AND (...) 形式追加；放在 CASE 中表达“可见性”，而不是过滤整行。
        accessJdbc.appendVisibilityPredicate(sql, params, scope, "d");
        sql.append(" THEN 1 ELSE 0 END AS acl_visible FROM knowledge_document d"
                + " WHERE d.tenant_id = :tenantId AND d.id IN (");
        int index = 0;
        for (UUID documentId : documentIds) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "documentId" + index;
            sql.append(':').append(name);
            params.put(name, BinaryUuids.toBytes(documentId));
            index++;
        }
        sql.append(')');
        List<DocumentStateRow> rows = tenantJdbc.query(tenantId, sql.toString(), params, CitationStatusJdbc::mapState);
        Map<UUID, CitationSourceState> states = new LinkedHashMap<>(rows.size());
        for (DocumentStateRow row : rows) {
            states.put(row.documentId(), new CitationSourceState(row.visible(), row.deleted(), row.checksum()));
        }
        return states;
    }

    /**
     * 映射文档状态行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 状态行
     * @throws SQLException 列读取失败
     */
    private static DocumentStateRow mapState(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentStateRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getInt("acl_visible") == 1,
                rs.getObject("deleted_at") != null,
                rs.getString("checksum"));
    }

    /**
     * 文档状态原始行。
     *
     * @param documentId 文档 ID
     * @param visible ACL 谓词是否通过
     * @param deleted 是否软删除
     * @param checksum 当前 checksum
     */
    private record DocumentStateRow(UUID documentId, boolean visible, boolean deleted, String checksum) {}
}
