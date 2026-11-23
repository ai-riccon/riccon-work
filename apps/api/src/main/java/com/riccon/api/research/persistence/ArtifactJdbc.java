package com.riccon.api.research.persistence;

import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.research.ArtifactStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 产物、版本与引用清单 SQL。
 *
 * <p>API 对产物域只读；正文写入与版本递增由 Worker 完成。所有查询排除 {@code DELETED}（软删除），
 * 仍必须带租户条件。引用清单批量按版本 ID 读取，避免列表页 N+1。
 */
@Component
public class ArtifactJdbc {

    /** 产物公开列。 */
    private static final String ARTIFACT_COLUMNS = """
            id, workspace_id, research_task_id, kind, title, status, current_version,
            created_by, created_at, updated_at
            """;

    /** 版本公开列；storage_key 只在服务端使用，不进入响应。 */
    private static final String VERSION_COLUMNS = """
            id, artifact_id, version, storage_key, content_type, size_bytes, sha256,
            summary, created_at
            """;

    /** 引用清单列；cell_range 不在 OpenAPI ArtifactCitation 中，故不查询。 */
    private static final String CITATION_COLUMNS = """
            artifact_version_id, ordinal, source_type, file_id, knowledge_document_id,
            mcp_invocation_id, title_snapshot, snippet, locator_type, page_no,
            sheet_name, cell_range, paragraph_no, source_version
            """;

    /** 工作空间产物列表过滤。 */
    private static final String LIST_FILTER = """
             FROM artifact
            WHERE tenant_id = :tenantId
              AND workspace_id = :workspaceId
              AND status <> 'DELETED'
            """;

    /** 统计工作空间产物。 */
    private static final String COUNT = "SELECT COUNT(*) " + LIST_FILTER;

    /** 分页列表。 */
    private static final String LIST = "SELECT " + ARTIFACT_COLUMNS + LIST_FILTER
            + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 按主键读取未删除产物。 */
    private static final String FIND = "SELECT " + ARTIFACT_COLUMNS
            + " FROM artifact WHERE tenant_id = :tenantId AND id = :artifactId AND status <> 'DELETED'";

    /** 按报告任务读取未删除产物；唯一键保证最多一条。 */
    private static final String FIND_BY_TASK = "SELECT " + ARTIFACT_COLUMNS
            + " FROM artifact WHERE tenant_id = :tenantId AND research_task_id = :taskId AND status <> 'DELETED'"
            + " ORDER BY created_at DESC LIMIT 1";

    /** 统计版本。 */
    private static final String COUNT_VERSIONS =
            "SELECT COUNT(*) FROM artifact_version WHERE tenant_id = :tenantId AND artifact_id = :artifactId";

    /** 分页列出版本。 */
    private static final String LIST_VERSIONS = "SELECT " + VERSION_COLUMNS
            + " FROM artifact_version WHERE tenant_id = :tenantId AND artifact_id = :artifactId"
            + " ORDER BY version DESC LIMIT :limit OFFSET :offset";

    /** 读取指定版本。 */
    private static final String FIND_VERSION = "SELECT " + VERSION_COLUMNS
            + " FROM artifact_version"
            + " WHERE tenant_id = :tenantId AND artifact_id = :artifactId AND version = :version";

    /** 批量读取引用清单。 */
    private static final String LIST_CITATIONS = "SELECT " + CITATION_COLUMNS
            + " FROM artifact_citation"
            + " WHERE tenant_id = :tenantId AND artifact_version_id IN (:versionIds)"
            + " ORDER BY artifact_version_id ASC, ordinal ASC";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建产物 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public ArtifactJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 统计工作空间产物。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 产物数
     */
    public long count(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.count(tenantId, COUNT, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)));
    }

    /**
     * 分页列出工作空间产物。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 产物列表
     */
    public List<ArtifactSnapshot> list(TenantId tenantId, UUID workspaceId, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, LIST, params, ArtifactJdbc::mapArtifact);
    }

    /**
     * 按 ID 读取未删除产物。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @return 产物
     */
    public Optional<ArtifactSnapshot> find(TenantId tenantId, UUID artifactId) {
        return tenantJdbc.queryOne(
                tenantId, FIND, Map.of("artifactId", BinaryUuids.toBytes(artifactId)), ArtifactJdbc::mapArtifact);
    }

    /**
     * 按报告任务读取未删除产物。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 产物
     */
    public Optional<ArtifactSnapshot> findByTask(TenantId tenantId, UUID taskId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_BY_TASK, Map.of("taskId", BinaryUuids.toBytes(taskId)), ArtifactJdbc::mapArtifact);
    }

    /**
     * 统计产物版本数。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @return 版本数
     */
    public long countVersions(TenantId tenantId, UUID artifactId) {
        return tenantJdbc.count(tenantId, COUNT_VERSIONS, Map.of("artifactId", BinaryUuids.toBytes(artifactId)));
    }

    /**
     * 分页列出版本（不含引用），引用由调用方批量补齐。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 版本列表
     */
    public List<ArtifactVersionSnapshot> listVersions(TenantId tenantId, UUID artifactId, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("artifactId", BinaryUuids.toBytes(artifactId));
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, LIST_VERSIONS, params, ArtifactJdbc::mapVersion);
    }

    /**
     * 读取指定版本（不含引用）。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 版本
     */
    public Optional<ArtifactVersionSnapshot> findVersion(TenantId tenantId, UUID artifactId, int version) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("artifactId", BinaryUuids.toBytes(artifactId));
        params.put("version", version);
        return tenantJdbc.queryOne(tenantId, FIND_VERSION, params, ArtifactJdbc::mapVersion);
    }

    /**
     * 批量读取版本的引用清单。
     *
     * @param tenantId 当前租户
     * @param versionIds 版本 ID 列表
     * @return 版本 ID 到引用列表的映射；无引用的版本不出现
     */
    public Map<UUID, List<ArtifactCitationSnapshot>> citationsByVersionIds(TenantId tenantId, List<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        List<byte[]> binaryIds = versionIds.stream().map(BinaryUuids::toBytes).toList();
        List<CitationRow> rows =
                tenantJdbc.query(tenantId, LIST_CITATIONS, Map.of("versionIds", binaryIds), ArtifactJdbc::mapCitation);
        Map<UUID, List<ArtifactCitationSnapshot>> grouped = new LinkedHashMap<>();
        for (CitationRow row : rows) {
            grouped.computeIfAbsent(row.versionId(), key -> new ArrayList<>()).add(row.citation());
        }
        return grouped;
    }

    /**
     * 映射产物行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 产物快照
     * @throws SQLException 列读取失败
     */
    private static ArtifactSnapshot mapArtifact(ResultSet rs, int rowNum) throws SQLException {
        return new ArtifactSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workspace_id")),
                rs.getBytes("research_task_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("research_task_id")),
                rs.getString("kind"),
                rs.getString("title"),
                ArtifactStatus.valueOf(rs.getString("status")),
                rs.getInt("current_version"),
                BinaryUuids.fromBytes(rs.getBytes("created_by")),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)),
                SqlTimestamps.toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    /**
     * 映射版本行；引用由调用方补齐。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 版本快照
     * @throws SQLException 列读取失败
     */
    private static ArtifactVersionSnapshot mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new ArtifactVersionSnapshot(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("artifact_id")),
                rs.getInt("version"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("summary"),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)),
                List.of());
    }

    /**
     * 映射引用行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 带版本 ID 的引用
     * @throws SQLException 列读取失败
     */
    private static CitationRow mapCitation(ResultSet rs, int rowNum) throws SQLException {
        String locator = rs.getString("locator_type");
        ArtifactCitationSnapshot citation = new ArtifactCitationSnapshot(
                rs.getInt("ordinal"),
                ArtifactSourceType.valueOf(rs.getString("source_type")),
                rs.getBytes("file_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("file_id")),
                rs.getBytes("knowledge_document_id") == null
                        ? null
                        : BinaryUuids.fromBytes(rs.getBytes("knowledge_document_id")),
                rs.getBytes("mcp_invocation_id") == null
                        ? null
                        : BinaryUuids.fromBytes(rs.getBytes("mcp_invocation_id")),
                rs.getString("title_snapshot"),
                rs.getString("snippet"),
                locator == null ? null : LocatorType.valueOf(locator),
                rs.getObject("page_no", Integer.class),
                rs.getString("sheet_name"),
                rs.getString("cell_range"),
                rs.getObject("paragraph_no", Integer.class),
                rs.getString("source_version"),
                CitationSourceStatus.AVAILABLE);
        return new CitationRow(BinaryUuids.fromBytes(rs.getBytes("artifact_version_id")), citation);
    }

    /**
     * 引用行内部组合，用于按版本分组。
     *
     * @param versionId 版本 ID
     * @param citation 引用
     */
    private record CitationRow(UUID versionId, ArtifactCitationSnapshot citation) {}
}
