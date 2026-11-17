package com.riccon.api.research.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.config.StorageProperties;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.dto.DownloadUrlResponse;
import com.riccon.api.file.storage.FileStorage;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.api.research.dto.ArtifactDetailResponse;
import com.riccon.api.research.dto.ArtifactPageResponse;
import com.riccon.api.research.dto.ArtifactResponse;
import com.riccon.api.research.dto.ArtifactVersionPageResponse;
import com.riccon.api.research.dto.ArtifactVersionResponse;
import com.riccon.api.research.repository.ArtifactRepository;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产物读取与下载地址。
 *
 * <p>API 对产物域只读：产物与版本由 Worker 在生成成功后写入。列表与读取都先校验工作空间可见性，
 * 跨租户或不可见统一 404；软删除产物不出现在任何列表或详情中。下载只返回短期预签名 URL（TTL ≤ 300 秒），
 * 客户端无法传入对象 key（ADR-0014 第 2 节）。
 */
@Service
public class ArtifactService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactService.class);

    /** 产物仓储。 */
    private final ArtifactRepository artifactRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /** 对象存储。 */
    private final FileStorage fileStorage;

    /** 存储配置，提供下载地址 TTL。 */
    private final StorageProperties storageProperties;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建产物服务。
     *
     * @param artifactRepository 产物仓储
     * @param workspaceService 工作空间可见性
     * @param fileStorage 对象存储
     * @param storageProperties 存储配置
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ArtifactService(
            ArtifactRepository artifactRepository,
            WorkspaceService workspaceService,
            FileStorage fileStorage,
            StorageProperties storageProperties,
            AuditService auditService,
            Clock clock) {
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.fileStorage = Objects.requireNonNull(fileStorage, "fileStorage");
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 列出工作空间产物。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param page 分页参数
     * @return 产物分页
     */
    @Transactional(readOnly = true)
    public ArtifactPageResponse list(RicconPrincipal actor, UUID workspaceId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        long total = artifactRepository.count(tenantId, workspaceId);
        List<ArtifactResponse> items =
                artifactRepository.list(tenantId, workspaceId, page.size(), page.offset()).stream()
                        .map(ArtifactResponse::from)
                        .toList();
        return new ArtifactPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取产物与当前版本。
     *
     * @param actor 当前员工
     * @param artifactId 产物 ID
     * @return 产物详情
     */
    @Transactional(readOnly = true)
    public ArtifactDetailResponse get(RicconPrincipal actor, UUID artifactId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ArtifactSnapshot artifact = requireVisible(tenantId, artifactId, actor.userId());
        return detail(tenantId, artifact);
    }

    /**
     * 列出版本。
     *
     * @param actor 当前员工
     * @param artifactId 产物 ID
     * @param page 分页参数
     * @return 版本分页
     */
    @Transactional(readOnly = true)
    public ArtifactVersionPageResponse listVersions(RicconPrincipal actor, UUID artifactId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ArtifactSnapshot artifact = requireVisible(tenantId, artifactId, actor.userId());
        long total = artifactRepository.countVersions(tenantId, artifact.id());
        List<ArtifactVersionSnapshot> versions =
                artifactRepository.listVersions(tenantId, artifact.id(), page.size(), page.offset());
        Map<UUID, List<ArtifactCitationSnapshot>> citations = artifactRepository.citationsByVersionIds(
                tenantId, versions.stream().map(ArtifactVersionSnapshot::id).toList());
        List<ArtifactVersionResponse> items = versions.stream()
                .map(version -> withCitations(version, citations))
                .map(ArtifactVersionResponse::from)
                .toList();
        return new ArtifactVersionPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取指定版本与引用清单。
     *
     * @param actor 当前员工
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 版本
     */
    @Transactional(readOnly = true)
    public ArtifactVersionResponse getVersion(RicconPrincipal actor, UUID artifactId, int version) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ArtifactSnapshot artifact = requireVisible(tenantId, artifactId, actor.userId());
        ArtifactVersionSnapshot versionSnapshot = requireVersion(tenantId, artifact.id(), version);
        return ArtifactVersionResponse.from(withCitations(
                versionSnapshot, artifactRepository.citationsByVersionIds(tenantId, List.of(versionSnapshot.id()))));
    }

    /**
     * 获取产物下载地址并写审计。
     *
     * @param actor 当前员工
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 预签名地址与过期时间
     */
    @Transactional
    public DownloadUrlResponse downloadUrl(
            RicconPrincipal actor, UUID artifactId, int version, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ArtifactSnapshot artifact = requireVisible(tenantId, artifactId, actor.userId());
        ArtifactVersionSnapshot versionSnapshot = requireVersion(tenantId, artifact.id(), version);
        Duration ttl = storageProperties.downloadUrlTtl();
        URI url = fileStorage.presignGet(actor.tenantId(), versionSnapshot.storageKey(), ttl);
        Instant now = clock.instant();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactId", artifact.id().toString());
        details.put("version", versionSnapshot.version());
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AdditionalAuditActions.ARTIFACT_DOWNLOADED,
                AuditTargetType.ARTIFACT,
                artifact.id().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details));
        LOG.info(
                "artifact.download.url tenantId={} artifactId={} version={}",
                actor.tenantId(),
                artifact.id(),
                versionSnapshot.version());
        return new DownloadUrlResponse(url.toString(), now.plus(ttl));
    }

    /**
     * 读取产物并校验工作空间可见性。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @param userId 当前员工
     * @return 产物
     */
    private ArtifactSnapshot requireVisible(TenantId tenantId, UUID artifactId, UUID userId) {
        ArtifactSnapshot artifact =
                artifactRepository.find(tenantId, artifactId).orElseThrow(() -> new NotFoundException("artifact"));
        workspaceService.requireAccessible(tenantId, artifact.workspaceId(), userId);
        return artifact;
    }

    /**
     * 读取版本并校验版本号。
     *
     * @param tenantId 当前租户
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 版本
     */
    private ArtifactVersionSnapshot requireVersion(TenantId tenantId, UUID artifactId, int version) {
        if (version < 1) {
            throw new BadRequestException("version must be >= 1");
        }
        return artifactRepository
                .findVersion(tenantId, artifactId, version)
                .orElseThrow(() -> new NotFoundException("artifact version"));
    }

    /**
     * 读取产物详情并补齐当前版本引用。
     *
     * @param tenantId 当前租户
     * @param artifact 产物
     * @return 详情
     */
    private ArtifactDetailResponse detail(TenantId tenantId, ArtifactSnapshot artifact) {
        ArtifactVersionSnapshot current = null;
        if (artifact.currentVersion() >= 1) {
            current = artifactRepository
                    .findVersion(tenantId, artifact.id(), artifact.currentVersion())
                    .map(version -> withCitations(
                            version, artifactRepository.citationsByVersionIds(tenantId, List.of(version.id()))))
                    .orElse(null);
        }
        return ArtifactDetailResponse.from(artifact, current);
    }

    /**
     * 为版本补齐引用。
     *
     * @param version 版本
     * @param citations 版本 ID 到引用列表的映射
     * @return 带引用的版本
     */
    private static ArtifactVersionSnapshot withCitations(
            ArtifactVersionSnapshot version, Map<UUID, List<ArtifactCitationSnapshot>> citations) {
        return new ArtifactVersionSnapshot(
                version.id(),
                version.artifactId(),
                version.version(),
                version.storageKey(),
                version.contentType(),
                version.sizeBytes(),
                version.sha256(),
                version.summary(),
                version.createdAt(),
                citations.getOrDefault(version.id(), List.of()));
    }
}
