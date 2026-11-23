package com.riccon.api.research.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.chat.domain.CitationSourceState;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.chat.repository.CitationRegionRepository;
import com.riccon.api.chat.repository.CitationStatusRepository;
import com.riccon.api.config.StorageProperties;
import com.riccon.api.error.ArtifactContentTooLargeException;
import com.riccon.api.error.ArtifactContentUnavailableException;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.dto.DownloadUrlResponse;
import com.riccon.api.file.storage.FileStorage;
import com.riccon.api.file.storage.StorageObject;
import com.riccon.api.file.storage.StorageObjectTooLargeException;
import com.riccon.api.file.storage.StorageReadException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.api.research.dto.ArtifactContent;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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

    /** 同源代理允许返回的产物正文上限：2 MiB。 */
    private static final long MAX_CONTENT_BYTES = 2L * 1024 * 1024;

    /** Markdown 响应类型；正文由 Worker 以 UTF-8 写入。 */
    private static final MediaType MARKDOWN_MEDIA_TYPE = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    /** 纯文本响应类型。 */
    private static final MediaType PLAIN_TEXT_MEDIA_TYPE = new MediaType("text", "plain", StandardCharsets.UTF_8);

    /** 产物仓储。 */
    private final ArtifactRepository artifactRepository;

    /** 引用源文档状态。 */
    private final CitationStatusRepository citationStatusRepository;

    /** 引用分块 OCR 元数据。 */
    private final CitationRegionRepository citationRegionRepository;

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
     * @param citationStatusRepository 引用源文档状态
     * @param citationRegionRepository 引用分块 OCR 元数据
     * @param workspaceService 工作空间可见性
     * @param fileStorage 对象存储
     * @param storageProperties 存储配置
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ArtifactService(
            ArtifactRepository artifactRepository,
            CitationStatusRepository citationStatusRepository,
            CitationRegionRepository citationRegionRepository,
            WorkspaceService workspaceService,
            FileStorage fileStorage,
            StorageProperties storageProperties,
            AuditService auditService,
            Clock clock) {
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.citationStatusRepository = Objects.requireNonNull(citationStatusRepository, "citationStatusRepository");
        this.citationRegionRepository = Objects.requireNonNull(citationRegionRepository, "citationRegionRepository");
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
        return detail(tenantId, artifact, actor.userId());
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
        List<ArtifactVersionResponse> items = withCitationsBatch(tenantId, actor.userId(), versions, citations).stream()
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
                tenantId,
                actor.userId(),
                versionSnapshot,
                artifactRepository.citationsByVersionIds(tenantId, List.of(versionSnapshot.id()))));
    }

    /**
     * 读取产物正文并通过 API 同源返回。
     *
     * <p>可见性与下载地址完全一致：先校验租户内产物与工作空间访问，再读取版本元数据；跨租户或不可见统一 404。
     * 正文上限 2 MiB，数据库元数据与对象实际大小都会校验；响应类型只允许 Markdown 或纯文本，避免把对象存储中
     * 的任意 MIME（例如 HTML）反射给同源浏览器。读取失败统一 502，不暴露对象 key、存储 URL 或凭据。
     *
     * @param actor 当前员工
     * @param artifactId 产物 ID
     * @param version 版本号
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 产物正文
     */
    @Transactional
    public ArtifactContent content(
            RicconPrincipal actor, UUID artifactId, int version, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        ArtifactSnapshot artifact = requireVisible(tenantId, artifactId, actor.userId());
        ArtifactVersionSnapshot versionSnapshot = requireVersion(tenantId, artifact.id(), version);
        // 先校验元数据可避免为明确超限的版本发起存储请求；对象实际大小再由存储端口二次兜底。
        if (versionSnapshot.sizeBytes() > MAX_CONTENT_BYTES) {
            throw new ArtifactContentTooLargeException();
        }
        StorageObject object;
        try {
            object = fileStorage.readObject(actor.tenantId(), versionSnapshot.storageKey(), MAX_CONTENT_BYTES);
        } catch (StorageObjectTooLargeException exception) {
            throw new ArtifactContentTooLargeException();
        } catch (StorageReadException exception) {
            // 日志只记录业务标识与异常；对象 key、存储 URL 与凭据不进入响应。
            LOG.warn(
                    "artifact.content.read_failed tenantId={} artifactId={} version={}",
                    actor.tenantId(),
                    artifact.id(),
                    versionSnapshot.version(),
                    exception);
            throw new ArtifactContentUnavailableException();
        }
        // 审计 action 沿用 ADR-0007 之外已落地的 artifact.downloaded：ADR 白名单暂无 artifact.viewed。
        auditService.append(AuditEvent.create(
                clock.instant(),
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
                artifactAuditDetails(artifact.id(), versionSnapshot.version())));
        LOG.info(
                "artifact.content.served tenantId={} artifactId={} version={} sizeBytes={}",
                actor.tenantId(),
                artifact.id(),
                versionSnapshot.version(),
                object.content().length);
        return new ArtifactContent(object.content(), canonicalContentType(versionSnapshot.contentType()));
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
        Map<String, Object> details = artifactAuditDetails(artifact.id(), versionSnapshot.version());
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
    private ArtifactDetailResponse detail(TenantId tenantId, ArtifactSnapshot artifact, UUID viewerId) {
        ArtifactVersionSnapshot current = null;
        if (artifact.currentVersion() >= 1) {
            current = artifactRepository
                    .findVersion(tenantId, artifact.id(), artifact.currentVersion())
                    .map(version -> withCitations(
                            tenantId,
                            viewerId,
                            version,
                            artifactRepository.citationsByVersionIds(tenantId, List.of(version.id()))))
                    .orElse(null);
        }
        return ArtifactDetailResponse.from(artifact, current);
    }

    /**
     * 为版本补齐引用并计算可用性。
     *
     * <p>引用状态按当前账号与文档 ACL 重新判定；一次请求内按文档 ID 批量查询，不逐条访问数据库
     * （ADR-0023 第 5.4 节）。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param version 版本
     * @param citations 版本 ID 到引用列表的映射
     * @return 带引用与可用性的版本
     */
    private ArtifactVersionSnapshot withCitations(
            TenantId tenantId,
            UUID viewerId,
            ArtifactVersionSnapshot version,
            Map<UUID, List<ArtifactCitationSnapshot>> citations) {
        return withCitations(version, withStatus(tenantId, viewerId, citations.getOrDefault(version.id(), List.of())));
    }

    /**
     * 为多个版本批量补齐引用与可用性。
     *
     * <p>版本列表页一次请求只按全部文档 ID / 分块 ID 查询一次，避免每个版本重复执行 ACL 与分块查询
     * （REV-TASK-0197-001 F-002）。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param versions 版本列表
     * @param citations 版本 ID 到引用列表的映射
     * @return 带引用与可用性的版本列表
     */
    private List<ArtifactVersionSnapshot> withCitationsBatch(
            TenantId tenantId,
            UUID viewerId,
            List<ArtifactVersionSnapshot> versions,
            Map<UUID, List<ArtifactCitationSnapshot>> citations) {
        if (versions.isEmpty()) {
            return List.of();
        }
        List<ArtifactCitationSnapshot> allCitations =
                citations.values().stream().flatMap(List::stream).toList();
        Map<UUID, CitationSourceState> states = resolveStates(tenantId, viewerId, allCitations);
        Map<UUID, CitationRegion> regions = resolveRegions(tenantId, allCitations);
        List<ArtifactVersionSnapshot> resolved = new ArrayList<>(versions.size());
        for (ArtifactVersionSnapshot version : versions) {
            List<ArtifactCitationSnapshot> versionCitations = citations.getOrDefault(version.id(), List.of()).stream()
                    .map(citation -> applySource(citation, states, regions))
                    .toList();
            resolved.add(withCitations(version, versionCitations));
        }
        return List.copyOf(resolved);
    }

    /**
     * 批量计算引用可用性与分块区域并回填快照。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param citations 引用列表
     * @return 带可用性的引用列表
     */
    private List<ArtifactCitationSnapshot> withStatus(
            TenantId tenantId, UUID viewerId, List<ArtifactCitationSnapshot> citations) {
        if (citations.isEmpty()) {
            return List.of();
        }
        Map<UUID, CitationSourceState> states = resolveStates(tenantId, viewerId, citations);
        Map<UUID, CitationRegion> regions = resolveRegions(tenantId, citations);
        List<ArtifactCitationSnapshot> resolved = new ArrayList<>(citations.size());
        for (ArtifactCitationSnapshot citation : citations) {
            resolved.add(applySource(citation, states, regions));
        }
        return List.copyOf(resolved);
    }

    /**
     * 批量读取引用文档状态。
     *
     * @param tenantId 当前租户
     * @param viewerId 当前账号
     * @param citations 引用列表
     * @return 文档 ID 到状态的映射
     */
    private Map<UUID, CitationSourceState> resolveStates(
            TenantId tenantId, UUID viewerId, List<ArtifactCitationSnapshot> citations) {
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (ArtifactCitationSnapshot citation : citations) {
            if (citation.sourceType() == ArtifactSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
                documentIds.add(citation.knowledgeDocumentId());
            }
        }
        return citationStatusRepository.findStates(tenantId, viewerId, documentIds);
    }

    /**
     * 批量读取引用分块区域。
     *
     * @param tenantId 当前租户
     * @param citations 引用列表
     * @return 分块 ID 到区域的映射
     */
    private Map<UUID, CitationRegion> resolveRegions(TenantId tenantId, List<ArtifactCitationSnapshot> citations) {
        Set<UUID> chunkIds = new LinkedHashSet<>();
        for (ArtifactCitationSnapshot citation : citations) {
            if (citation.chunkId() != null) {
                chunkIds.add(citation.chunkId());
            }
        }
        return citationRegionRepository.findRegions(tenantId, chunkIds);
    }

    /**
     * 计算单条引用的状态与区域。
     *
     * @param citation 引用
     * @param states 文档状态映射
     * @param regions 分块区域映射
     * @return 带状态与区域的引用
     */
    private static ArtifactCitationSnapshot applySource(
            ArtifactCitationSnapshot citation,
            Map<UUID, CitationSourceState> states,
            Map<UUID, CitationRegion> regions) {
        CitationSourceStatus status = CitationSourceStatus.AVAILABLE;
        if (citation.sourceType() == ArtifactSourceType.KNOWLEDGE && citation.knowledgeDocumentId() != null) {
            CitationSourceState state = states.get(citation.knowledgeDocumentId());
            status = state == null ? CitationSourceStatus.NO_ACCESS : state.statusFor(citation.sourceVersion());
        }
        CitationRegion region = citation.chunkId() == null ? null : regions.get(citation.chunkId());
        return citation.withSourceStatus(status).withRegion(region);
    }

    /**
     * 复制版本并替换引用清单。
     *
     * @param version 版本
     * @param citations 已计算状态与区域的引用
     * @return 新版本
     */
    private static ArtifactVersionSnapshot withCitations(
            ArtifactVersionSnapshot version, List<ArtifactCitationSnapshot> citations) {
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
                citations);
    }

    /**
     * 构造产物审计 details；只写 ADR-0007 白名单内的标识与版本。
     *
     * @param artifactId 产物 ID
     * @param version 版本号
     * @return 审计 details
     */
    private static Map<String, Object> artifactAuditDetails(UUID artifactId, int version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactId", artifactId.toString());
        details.put("version", version);
        return details;
    }

    /**
     * 规范化产物正文响应类型。
     *
     * <p>只允许 Markdown 与纯文本；历史或异常数据中的其他类型（例如 {@code text/html}）回退为 Markdown，
     * 防止同源响应被浏览器当作可执行 HTML 解析。
     *
     * @param stored 数据库记录的 MIME 类型
     * @return 响应类型
     */
    private static MediaType canonicalContentType(String stored) {
        String normalized = stored == null ? "" : stored.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("text/plain")) {
            return PLAIN_TEXT_MEDIA_TYPE;
        }
        return MARKDOWN_MEDIA_TYPE;
    }
}
