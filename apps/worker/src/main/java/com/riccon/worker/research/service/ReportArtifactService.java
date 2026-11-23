package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.research.domain.ArtifactException;
import com.riccon.worker.research.domain.ArtifactRecord;
import com.riccon.worker.research.domain.ArtifactVersionRecord;
import com.riccon.worker.research.domain.ReportCitation;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.repository.ArtifactRepository;
import com.riccon.worker.research.service.ArtifactStorage.StoredArtifact;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告产物落库。
 *
 * <p>一个报告任务只创建一个产物；同一正文摘要重复保存直接复用已有版本，只有正文变化时才递增版本，
 * 从而保证 outbox 重投与阶段重试不产生重复产物/版本/引用。产物、版本、引用与审计在同一事务内提交，
 * 任一步失败都回滚，避免出现半套时间线。
 */
@Service
public class ReportArtifactService {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(ReportArtifactService.class);

    /** 产物仓储。 */
    private final ArtifactRepository repository;

    /** 审计。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建产物服务。
     *
     * @param repository 产物仓储
     * @param auditService 审计
     * @param clock UTC 时钟
     */
    public ReportArtifactService(ArtifactRepository repository, AuditService auditService, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 保存报告产物；同一正文摘要幂等复用。
     *
     * @param tenantId 租户
     * @param task 报告任务
     * @param runId 运行 ID
     * @param createdBy 生成人
     * @param document 报告文档
     * @param stored 已写入对象存储的正文元数据
     * @param traceId 追踪 ID
     * @return 保存结果
     */
    @Transactional
    public SavedArtifact persist(
            TenantId tenantId,
            ResearchTaskRecord task,
            UUID runId,
            UUID createdBy,
            ReportDocument document,
            StoredArtifact stored,
            String traceId) {
        ArtifactRecord artifact = repository
                .findByTask(tenantId, task.id())
                .orElseGet(() -> createArtifact(tenantId, task, createdBy, document.title()));
        Optional<ArtifactVersionRecord> existing =
                repository.findVersionBySha(tenantId, artifact.id(), stored.sha256());
        if (existing.isPresent()) {
            LOG.info(
                    "research.artifact.reused tenantId={} taskId={} artifactId={} version={}",
                    tenantId.value(),
                    task.id(),
                    artifact.id(),
                    existing.get().version());
            return new SavedArtifact(artifact.id(), existing.get(), false);
        }

        int nextVersion = artifact.currentVersion() + 1;
        int updated = repository.updateCurrentVersion(tenantId, artifact.id(), artifact.currentVersion(), nextVersion);
        if (updated == 0) {
            throw new ArtifactException(
                    ArtifactException.CONFLICT, true, "artifact version was advanced by another writer");
        }
        UUID versionId = UuidV7.generate();
        repository.insertVersion(
                tenantId,
                versionId,
                artifact.id(),
                nextVersion,
                stored.storageKey(),
                ArtifactStorage.CONTENT_TYPE,
                stored.sizeBytes(),
                stored.sha256(),
                document.summary(),
                createdBy);
        // 引用落库前按文档 ID 批量取 checksum，写入 source_version 快照；不逐条查询（ADR-0023 §5.1）。
        Map<UUID, String> sourceVersions =
                repository.findKnowledgeChecksums(tenantId, knowledgeDocumentIds(document.citations()));
        for (ReportCitation citation : document.citations()) {
            repository.insertCitation(tenantId, versionId, withSourceVersion(citation, sourceVersions));
        }
        auditService.artifactSaved(tenantId, traceId, null, artifact.id(), runId, nextVersion);
        ArtifactVersionRecord version = repository
                .findVersion(tenantId, artifact.id(), nextVersion)
                .orElseThrow(() ->
                        new ArtifactException(ArtifactException.CONFLICT, true, "artifact version was not persisted"));
        LOG.info(
                "research.artifact.saved tenantId={} taskId={} artifactId={} version={} citations={}",
                tenantId.value(),
                task.id(),
                artifact.id(),
                nextVersion,
                document.citations().size());
        return new SavedArtifact(artifact.id(), version, true);
    }

    /**
     * 收集引用涉及的知识库文档 ID。
     *
     * @param citations 引用清单
     * @return 去重后的文档 ID
     */
    private static Set<UUID> knowledgeDocumentIds(List<ReportCitation> citations) {
        Set<UUID> documentIds = new LinkedHashSet<>();
        for (ReportCitation citation : citations) {
            if ("KNOWLEDGE".equals(citation.sourceType()) && citation.knowledgeDocumentId() != null) {
                documentIds.add(citation.knowledgeDocumentId());
            }
        }
        return documentIds;
    }

    /**
     * 为知识库引用写入版本快照；FILE/MCP 引用保持空版本。
     *
     * @param citation 引用
     * @param sourceVersions 文档 ID 到当前 checksum 的映射
     * @return 带版本快照的引用
     */
    private static ReportCitation withSourceVersion(ReportCitation citation, Map<UUID, String> sourceVersions) {
        String sourceVersion =
                "KNOWLEDGE".equals(citation.sourceType()) ? sourceVersions.get(citation.knowledgeDocumentId()) : null;
        if (sourceVersion == null) {
            return citation;
        }
        return new ReportCitation(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.mcpInvocationId(),
                citation.title(),
                citation.snippet(),
                citation.locatorType(),
                citation.pageNo(),
                citation.sheetName(),
                citation.cellRange(),
                citation.paragraphNo(),
                sourceVersion);
    }

    /**
     * 创建产物行，保留到期时间按租户策略计算。
     *
     * @param tenantId 租户
     * @param task 报告任务
     * @param createdBy 创建人
     * @param title 标题
     * @return 新建的产物
     */
    private ArtifactRecord createArtifact(TenantId tenantId, ResearchTaskRecord task, UUID createdBy, String title) {
        UUID artifactId = UuidV7.generate();
        int retentionDays = repository.retentionDays(tenantId);
        repository.insertArtifact(
                tenantId,
                artifactId,
                task.workspaceId(),
                task.id(),
                title,
                createdBy,
                clock.instant().plus(Duration.ofDays(retentionDays)));
        return repository
                .findByTask(tenantId, task.id())
                .orElseThrow(
                        () -> new ArtifactException(ArtifactException.CONFLICT, true, "artifact was not persisted"));
    }

    /**
     * 产物保存结果。
     *
     * @param artifactId 产物 ID
     * @param version 版本元数据
     * @param created 是否新建版本；false 表示复用了相同正文的版本
     */
    public record SavedArtifact(UUID artifactId, ArtifactVersionRecord version, boolean created) {}
}
