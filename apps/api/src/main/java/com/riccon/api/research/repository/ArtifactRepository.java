package com.riccon.api.research.repository;

import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.api.research.persistence.ArtifactJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 产物仓储。
 *
 * <p>产物与版本由 Worker 写入，API 只读；仓储不提供更新/删除入口，软删除状态由查询过滤。
 */
@Repository
public class ArtifactRepository {

    /** 产物 SQL。 */
    private final ArtifactJdbc artifactJdbc;

    /**
     * 创建仓储。
     *
     * @param artifactJdbc 产物 SQL
     */
    public ArtifactRepository(ArtifactJdbc artifactJdbc) {
        this.artifactJdbc = Objects.requireNonNull(artifactJdbc, "artifactJdbc must not be null");
    }

    /**
     * 统计工作空间产物。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 产物数
     */
    public long count(TenantId tenantId, UUID workspaceId) {
        return artifactJdbc.count(tenantId, workspaceId);
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
        return artifactJdbc.list(tenantId, workspaceId, limit, offset);
    }

    /**
     * 按 ID 读取产物。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @return 产物
     */
    public Optional<ArtifactSnapshot> find(TenantId tenantId, UUID artifactId) {
        return artifactJdbc.find(tenantId, artifactId);
    }

    /**
     * 按报告任务读取产物。
     *
     * @param tenantId 当前租户
     * @param taskId 报告任务 ID
     * @return 产物
     */
    public Optional<ArtifactSnapshot> findByTask(TenantId tenantId, UUID taskId) {
        return artifactJdbc.findByTask(tenantId, taskId);
    }

    /**
     * 统计版本数。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @return 版本数
     */
    public long countVersions(TenantId tenantId, UUID artifactId) {
        return artifactJdbc.countVersions(tenantId, artifactId);
    }

    /**
     * 分页列出版本（不含引用）。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 版本列表
     */
    public List<ArtifactVersionSnapshot> listVersions(TenantId tenantId, UUID artifactId, int limit, int offset) {
        return artifactJdbc.listVersions(tenantId, artifactId, limit, offset);
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
        return artifactJdbc.findVersion(tenantId, artifactId, version);
    }

    /**
     * 批量读取引用清单。
     *
     * @param tenantId 当前租户
     * @param versionIds 版本 ID 列表
     * @return 版本 ID 到引用列表的映射
     */
    public Map<UUID, List<ArtifactCitationSnapshot>> citationsByVersionIds(TenantId tenantId, List<UUID> versionIds) {
        return artifactJdbc.citationsByVersionIds(tenantId, versionIds);
    }
}
