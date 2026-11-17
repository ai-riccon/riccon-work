package com.riccon.worker.research.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.research.domain.ArtifactCitationRecord;
import com.riccon.worker.research.domain.ArtifactRecord;
import com.riccon.worker.research.domain.ArtifactVersionRecord;
import com.riccon.worker.research.domain.ReportCitation;
import com.riccon.worker.research.persistence.ArtifactJdbc;
import com.riccon.worker.research.persistence.TenantRetentionJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 产物仓储。
 *
 * <p>封装 {@link ArtifactJdbc} 与租户保留策略；所有方法都要求当前租户，不提供跨租户查询。
 */
@Component
public class ArtifactRepository {

    /** 产物访问。 */
    private final ArtifactJdbc artifactJdbc;

    /** 租户保留策略。 */
    private final TenantRetentionJdbc retentionJdbc;

    /**
     * 创建产物仓储。
     *
     * @param artifactJdbc 产物访问
     * @param retentionJdbc 租户保留策略
     */
    public ArtifactRepository(ArtifactJdbc artifactJdbc, TenantRetentionJdbc retentionJdbc) {
        this.artifactJdbc = Objects.requireNonNull(artifactJdbc, "artifactJdbc must not be null");
        this.retentionJdbc = Objects.requireNonNull(retentionJdbc, "retentionJdbc must not be null");
    }

    /**
     * 按报告任务读取产物。
     *
     * @param tenantId 租户
     * @param researchTaskId 报告任务 ID
     * @return 产物
     */
    public Optional<ArtifactRecord> findByTask(TenantId tenantId, UUID researchTaskId) {
        return artifactJdbc.findByTask(tenantId, researchTaskId);
    }

    /**
     * 按 ID 读取产物。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @return 产物
     */
    public Optional<ArtifactRecord> findById(TenantId tenantId, UUID artifactId) {
        return artifactJdbc.findById(tenantId, artifactId);
    }

    /**
     * 读取当前租户保留天数；租户行缺失时使用默认 180 天。
     *
     * @param tenantId 租户
     * @return 保留天数
     */
    public int retentionDays(TenantId tenantId) {
        return retentionJdbc.findRetentionDays(tenantId).orElse(TenantRetentionJdbc.DEFAULT_RETENTION_DAYS);
    }

    /**
     * 创建产物行。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param workspaceId 工作空间
     * @param researchTaskId 报告任务
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
            Instant retentionExpiresAt) {
        artifactJdbc.insertArtifact(
                tenantId, artifactId, workspaceId, researchTaskId, title, createdBy, retentionExpiresAt);
    }

    /**
     * 条件推进当前版本号。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param expected 期望版本
     * @param next 新版本
     * @return 受影响行数
     */
    public int updateCurrentVersion(TenantId tenantId, UUID artifactId, int expected, int next) {
        return artifactJdbc.updateCurrentVersion(tenantId, artifactId, expected, next);
    }

    /**
     * 按内容摘要查找版本。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param sha256 正文摘要
     * @return 版本
     */
    public Optional<ArtifactVersionRecord> findVersionBySha(TenantId tenantId, UUID artifactId, String sha256) {
        return artifactJdbc.findVersionBySha(tenantId, artifactId, sha256);
    }

    /**
     * 读取指定版本。
     *
     * @param tenantId 租户
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 版本
     */
    public Optional<ArtifactVersionRecord> findVersion(TenantId tenantId, UUID artifactId, int version) {
        return artifactJdbc.findVersion(tenantId, artifactId, version);
    }

    /**
     * 写入版本。
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
        artifactJdbc.insertVersion(
                tenantId,
                versionId,
                artifactId,
                version,
                storageKey,
                contentType,
                sizeBytes,
                sha256,
                summary,
                createdBy);
    }

    /**
     * 写入引用。
     *
     * @param tenantId 租户
     * @param versionId 版本 ID
     * @param citation 引用
     */
    public void insertCitation(TenantId tenantId, UUID versionId, ReportCitation citation) {
        artifactJdbc.insertCitation(tenantId, versionId, citation);
    }

    /**
     * 读取版本引用。
     *
     * @param tenantId 租户
     * @param versionId 版本 ID
     * @return 引用清单
     */
    public List<ArtifactCitationRecord> findCitations(TenantId tenantId, UUID versionId) {
        return artifactJdbc.findCitations(tenantId, versionId);
    }
}
