package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.ArtifactCitationRecord;
import com.riccon.worker.research.domain.ArtifactRecord;
import com.riccon.worker.research.domain.ArtifactVersionRecord;
import com.riccon.worker.research.domain.ReportCitation;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 产物、版本与引用清单持久化。
 *
 * <p>所有语句经 {@link TenantJdbc} 强制租户条件；产物唯一键 {@code (tenant_id, research_task_id)}
 * 与版本唯一键 {@code (tenant_id, artifact_id, version)} 是重试幂等的最后防线。
 * 引用插入使用 {@code ON DUPLICATE KEY UPDATE} 保证同一序号重复写入不产生重复行。
 */
@Component
public class ArtifactJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建产物访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ArtifactJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 按报告任务读取产物。
     *
     * @param tenantId 租户
     * @param researchTaskId 报告任务 ID
     * @return 产物；不存在或已软删除时为空
     */
    public Optional<ArtifactRecord> findByTask(TenantId tenantId, UUID researchTaskId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, workspace_id, research_task_id, title, current_version
                  FROM artifact
                 WHERE tenant_id = :tenantId
                   AND research_task_id = :researchTaskId
                   AND status <> 'DELETED'
                 LIMIT 1
                """,
                Map.of("researchTaskId", UuidBinary.toBytes(researchTaskId)),
                (rs, rowNum) -> new ArtifactRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("workspace_id")),
                        UuidBinary.fromBytes(rs.getBytes("research_task_id")),
                        rs.getString("title"),
                        rs.getInt("current_version")));
    }

    /**
     * 按 ID 读取产物。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @return 产物；不存在或已软删除时为空
     */
    public Optional<ArtifactRecord> findById(TenantId tenantId, UUID artifactId) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, workspace_id, research_task_id, title, current_version
                  FROM artifact
                 WHERE tenant_id = :tenantId
                   AND id = :artifactId
                   AND status <> 'DELETED'
                """,
                Map.of("artifactId", UuidBinary.toBytes(artifactId)),
                (rs, rowNum) -> new ArtifactRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("workspace_id")),
                        UuidBinary.fromBytes(rs.getBytes("research_task_id")),
                        rs.getString("title"),
                        rs.getInt("current_version")));
    }

    /**
     * 创建产物行，初始版本号为 0。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param workspaceId 工作空间 ID
     * @param researchTaskId 报告任务 ID
     * @param title 标题
     * @param createdBy 创建人
     * @param retentionExpiresAt 保留到期时间
     */
    public void insertArtifact(
            TenantId tenantId,
            UUID artifactId,
            UUID workspaceId,
            UUID researchTaskId,
            String title,
            UUID createdBy,
            java.time.Instant retentionExpiresAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(artifactId));
        params.put("workspaceId", UuidBinary.toBytes(workspaceId));
        params.put("researchTaskId", UuidBinary.toBytes(researchTaskId));
        params.put("title", title);
        params.put("createdBy", UuidBinary.toBytes(createdBy));
        params.put("retentionExpiresAt", toDatabase(retentionExpiresAt));
        tenantJdbc.insert(tenantId, """
                INSERT INTO artifact
                    (tenant_id, id, workspace_id, research_task_id, kind, title, status,
                     current_version, created_by, retention_expires_at)
                VALUES
                    (:tenantId, :id, :workspaceId, :researchTaskId, 'REPORT', :title, 'SAVED',
                     0, :createdBy, :retentionExpiresAt)
                """, params);
    }

    /**
     * 条件推进产物当前版本号，防止并发写产生重复版本。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param expected 期望的当前版本
     * @param next 新版本号
     * @return 受影响行数；0 表示版本已被其他写入推进
     */
    public int updateCurrentVersion(TenantId tenantId, UUID artifactId, int expected, int next) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("artifactId", UuidBinary.toBytes(artifactId));
        params.put("expected", expected);
        params.put("next", next);
        return tenantJdbc.update(tenantId, """
                UPDATE artifact
                   SET current_version = :next
                 WHERE tenant_id = :tenantId
                   AND id = :artifactId
                   AND current_version = :expected
                """, params);
    }

    /**
     * 按内容摘要查找已有版本，用于重试幂等。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param sha256 正文摘要
     * @return 版本；不存在时为空
     */
    public Optional<ArtifactVersionRecord> findVersionBySha(TenantId tenantId, UUID artifactId, String sha256) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, artifact_id, version, storage_key, content_type, size_bytes, sha256, summary
                  FROM artifact_version
                 WHERE tenant_id = :tenantId
                   AND artifact_id = :artifactId
                   AND sha256 = :sha256
                 ORDER BY version DESC
                 LIMIT 1
                """,
                Map.of("artifactId", UuidBinary.toBytes(artifactId), "sha256", sha256),
                ArtifactJdbc::mapVersion);
    }

    /**
     * 读取指定版本。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 版本；不存在时为空
     */
    public Optional<ArtifactVersionRecord> findVersion(TenantId tenantId, UUID artifactId, int version) {
        return tenantJdbc.queryOne(
                tenantId,
                """
                SELECT id, artifact_id, version, storage_key, content_type, size_bytes, sha256, summary
                  FROM artifact_version
                 WHERE tenant_id = :tenantId
                   AND artifact_id = :artifactId
                   AND version = :version
                """,
                Map.of("artifactId", UuidBinary.toBytes(artifactId), "version", version),
                ArtifactJdbc::mapVersion);
    }

    /**
     * 写入版本元数据。
     *
     * @param tenantId 租户
     * @param versionId 版本 ID
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param storageKey 对象 key
     * @param contentType MIME
     * @param sizeBytes 字节数
     * @param sha256 摘要
     * @param summary 摘要文本
     * @param createdBy 创建人
     */
    public void insertVersion(
            TenantId tenantId,
            UUID versionId,
            UUID artifactId,
            int version,
            String storageKey,
            String contentType,
            long sizeBytes,
            String sha256,
            String summary,
            UUID createdBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(versionId));
        params.put("artifactId", UuidBinary.toBytes(artifactId));
        params.put("version", version);
        params.put("storageKey", storageKey);
        params.put("contentType", contentType);
        params.put("sizeBytes", sizeBytes);
        params.put("sha256", sha256);
        params.put("summary", summary);
        params.put("createdBy", UuidBinary.toBytes(createdBy));
        tenantJdbc.insert(tenantId, """
                INSERT INTO artifact_version
                    (tenant_id, id, artifact_id, version, storage_key, content_type,
                     size_bytes, sha256, summary, created_by)
                VALUES
                    (:tenantId, :id, :artifactId, :version, :storageKey, :contentType,
                     :sizeBytes, :sha256, :summary, :createdBy)
                """, params);
    }

    /**
     * 写入引用清单条目；同一版本同一序号重复写入保持幂等。
     *
     * @param tenantId 租户
     * @param versionId 版本 ID
     * @param citation 引用
     */
    public void insertCitation(TenantId tenantId, UUID versionId, ReportCitation citation) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(com.riccon.worker.common.UuidV7.generate()));
        params.put("artifactVersionId", UuidBinary.toBytes(versionId));
        params.put("ordinal", citation.ordinal());
        params.put("sourceType", citation.sourceType());
        params.put("fileId", citation.fileId() == null ? null : UuidBinary.toBytes(citation.fileId()));
        params.put(
                "knowledgeDocumentId",
                citation.knowledgeDocumentId() == null ? null : UuidBinary.toBytes(citation.knowledgeDocumentId()));
        params.put("chunkId", citation.chunkId() == null ? null : UuidBinary.toBytes(citation.chunkId()));
        params.put(
                "mcpInvocationId",
                citation.mcpInvocationId() == null ? null : UuidBinary.toBytes(citation.mcpInvocationId()));
        params.put("titleSnapshot", citation.title());
        params.put("snippet", citation.snippet());
        params.put(
                "locatorType",
                citation.locatorType() == null ? null : citation.locatorType().name());
        params.put("pageNo", citation.pageNo());
        params.put("sheetName", citation.sheetName());
        params.put("cellRange", citation.cellRange());
        params.put("paragraphNo", citation.paragraphNo());
        params.put("sourceVersion", citation.sourceVersion());
        tenantJdbc.insert(tenantId, """
                INSERT INTO artifact_citation
                    (tenant_id, id, artifact_version_id, ordinal, source_type, file_id,
                     knowledge_document_id, chunk_id, mcp_invocation_id, title_snapshot, snippet,
                     locator_type, page_no, sheet_name, cell_range, paragraph_no, source_version)
                VALUES
                    (:tenantId, :id, :artifactVersionId, :ordinal, :sourceType, :fileId,
                     :knowledgeDocumentId, :chunkId, :mcpInvocationId, :titleSnapshot, :snippet,
                     :locatorType, :pageNo, :sheetName, :cellRange, :paragraphNo, :sourceVersion)
                ON DUPLICATE KEY UPDATE id = id
                """, params);
    }

    /**
     * 读取版本引用清单。
     *
     * @param tenantId 租户
     * @param versionId 版本 ID
     * @return 按序号排序的引用
     */
    public List<ArtifactCitationRecord> findCitations(TenantId tenantId, UUID versionId) {
        return tenantJdbc.query(
                tenantId,
                """
                SELECT id, artifact_version_id, ordinal, source_type, file_id, knowledge_document_id,
                       chunk_id, mcp_invocation_id, title_snapshot, snippet, locator_type, page_no,
                       sheet_name, cell_range, paragraph_no, source_version
                  FROM artifact_citation
                 WHERE tenant_id = :tenantId
                   AND artifact_version_id = :versionId
                 ORDER BY ordinal
                """,
                Map.of("versionId", UuidBinary.toBytes(versionId)),
                (rs, rowNum) -> new ArtifactCitationRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("artifact_version_id")),
                        rs.getInt("ordinal"),
                        rs.getString("source_type"),
                        rs.getBytes("file_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("file_id")),
                        rs.getBytes("knowledge_document_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("knowledge_document_id")),
                        rs.getBytes("chunk_id") == null ? null : UuidBinary.fromBytes(rs.getBytes("chunk_id")),
                        rs.getBytes("mcp_invocation_id") == null
                                ? null
                                : UuidBinary.fromBytes(rs.getBytes("mcp_invocation_id")),
                        rs.getString("title_snapshot"),
                        rs.getString("snippet"),
                        rs.getString("locator_type"),
                        (Integer) rs.getObject("page_no"),
                        rs.getString("sheet_name"),
                        rs.getString("cell_range"),
                        (Integer) rs.getObject("paragraph_no"),
                        rs.getString("source_version")));
    }

    /**
     * 批量读取知识库文档当前 checksum。
     *
     * <p>产物引用落库前一次查询取齐引用涉及文档的版本，写入 {@code source_version} 快照；文档已被删除或跨租户时
     * 不返回版本，引用保持空版本，由读取时的删除/ACL 判定遮蔽（ADR-0023 第 5.1、5.4 节）。
     *
     * @param tenantId 租户
     * @param documentIds 知识库文档 ID 列表
     * @return 文档 ID 到 checksum 的映射
     */
    public Map<UUID, String> findKnowledgeChecksums(TenantId tenantId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, checksum FROM knowledge_document WHERE tenant_id = :tenantId AND id IN (");
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (UUID documentId : documentIds) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "documentId" + index;
            sql.append(':').append(name);
            params.put(name, UuidBinary.toBytes(documentId));
            index++;
        }
        sql.append(')');
        List<Map.Entry<UUID, String>> rows = tenantJdbc.query(
                tenantId,
                sql.toString(),
                params,
                (rs, rowNum) -> Map.entry(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("checksum")));
        Map<UUID, String> checksums = new LinkedHashMap<>(rows.size());
        for (Map.Entry<UUID, String> row : rows) {
            checksums.put(row.getKey(), row.getValue());
        }
        return checksums;
    }

    /**
     * 映射版本行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 版本记录
     * @throws java.sql.SQLException 读取失败
     */
    private static ArtifactVersionRecord mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ArtifactVersionRecord(
                UuidBinary.fromBytes(rs.getBytes("id")),
                UuidBinary.fromBytes(rs.getBytes("artifact_id")),
                rs.getInt("version"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("summary"));
    }

    /**
     * 把 Instant 转为 UTC DATETIME。
     *
     * @param value 时间
     * @return 数据库时间
     */
    private static LocalDateTime toDatabase(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
