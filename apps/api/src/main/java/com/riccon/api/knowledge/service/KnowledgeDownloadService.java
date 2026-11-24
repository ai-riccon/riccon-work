package com.riccon.api.knowledge.service;

import com.riccon.api.audit.service.AuditService;
import com.riccon.api.config.KnowledgeAclProperties;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.repository.FileRepository;
import com.riccon.api.file.storage.FileStorage;
import com.riccon.api.file.storage.StorageReadException;
import com.riccon.api.file.storage.StorageStream;
import com.riccon.api.knowledge.domain.KnowledgeActor;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.repository.KnowledgeAclRepository;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库源文件流式下载。
 *
 * <p>每次请求重新判定文档 ACL：不可见、跨租户、跨部门统一 404；ACL 通过但没有源文件（SEED、已清理或
 * 缺少连接器来源）返回 {@code RICCON-KB-404}。下载只按文档 ACL，不要求用户仍有源文件所在空间的访问权，
 * 也不放宽原空间路径（ADR-0023 第 5.3 节、DEPT-014）。
 *
 * <p>源对象有两类：UPLOAD 文档经 {@code file} 行取存储 key；连接器文档（{@code file_id} 为空、
 * {@code connector_id} 非空）按 {@code tenants/{tenantId}/connector/{yyyy}/{documentId}} 读取平台桶对象，
 * 与 worker 写入侧一致（ADR-0023 第 6.2 节）。两条路径都只返回文档展示信息与流句柄。
 *
 * <p>管理性读取（{@code TENANT_ADMIN} 绕过 ACL）写 {@code knowledge.document.admin_read scope=DOWNLOAD}；
 * 审计在打开流之前写入，失败则下载失败（fail-closed）。
 */
@Service
public class KnowledgeDownloadService {

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** ACL 仓储。 */
    private final KnowledgeAclRepository aclRepository;

    /** 文件仓储。 */
    private final FileRepository fileRepository;

    /** 对象存储端口。 */
    private final FileStorage fileStorage;

    /** 审计写入。 */
    private final AuditService auditService;

    /** ACL 配置。 */
    private final KnowledgeAclProperties aclProperties;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建下载服务。
     *
     * @param knowledgeRepository 知识库仓储
     * @param aclRepository ACL 仓储
     * @param fileRepository 文件仓储
     * @param fileStorage 对象存储端口
     * @param auditService 审计写入
     * @param aclProperties ACL 配置
     * @param clock 可注入时钟
     */
    public KnowledgeDownloadService(
            KnowledgeRepository knowledgeRepository,
            KnowledgeAclRepository aclRepository,
            FileRepository fileRepository,
            FileStorage fileStorage,
            AuditService auditService,
            KnowledgeAclProperties aclProperties,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.aclRepository = Objects.requireNonNull(aclRepository, "aclRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.fileStorage = Objects.requireNonNull(fileStorage, "fileStorage must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.aclProperties = Objects.requireNonNull(aclProperties, "aclProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 准备下载流；服务端代理，不返回对象 key 或预签名 URL。
     *
     * @param actor 当前操作者
     * @param documentId 文档 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 下载源
     * @throws NotFoundException 文档不可见或跨租户
     * @throws KnowledgeSourceNotFoundException ACL 已通过但无源文件
     * @throws KnowledgeDownloadFailedException 对象存储读取失败
     */
    @Transactional
    public KnowledgeDownload prepare(KnowledgeActor actor, UUID documentId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        var scope = knowledgeRepository.resolveScope(tenantId, actor, aclProperties.enforcementEnabled());
        // 先按“本人 ACL”判定，再走管理员绕过；否则管理员 scope 会掩盖 admin_read 触发条件。
        var aclScope = knowledgeRepository.resolveScope(
                tenantId,
                new KnowledgeActor(actor.userId(), actor.tenantId(), false, actor.manage()),
                aclProperties.enforcementEnabled());
        Optional<KnowledgeDocumentRecord> visible = knowledgeRepository.findVisibleById(tenantId, documentId, aclScope);
        KnowledgeDocumentRecord document;
        if (visible.isPresent()) {
            document = visible.get();
        } else if (scope.unrestricted() && aclProperties.enforcementEnabled()) {
            document = knowledgeRepository
                    .findActiveByIdIgnoringAcl(tenantId, documentId)
                    .orElseThrow(() -> new NotFoundException("knowledge document"));
            // 绕过 ACL 的下载必须审计；按 ACL 可见的读取不写，避免读放大。
            auditService.append(KnowledgeAuditEvents.adminRead(
                    clock.instant(),
                    actor.tenantId(),
                    actor.userId(),
                    "DOWNLOAD",
                    document.id(),
                    document.visibility(),
                    aclRepository.count(tenantId, document.id()),
                    null,
                    traceId,
                    requestId));
        } else {
            throw new NotFoundException("knowledge document");
        }
        if (document.fileId() != null) {
            FileRecord file = fileRepository
                    .findById(tenantId, document.fileId())
                    .orElseThrow(KnowledgeSourceNotFoundException::new);
            StorageStream stream;
            try {
                stream = fileStorage.openObject(tenantId.value(), file.storageKey());
            } catch (StorageReadException exception) {
                throw new KnowledgeDownloadFailedException("source storage is unavailable");
            }
            return new KnowledgeDownload(document.title(), document.mimeType(), file.sizeBytes(), stream);
        }
        if (document.connectorId() != null) {
            // 连接器文档没有 file 行：原文由 worker 写入平台桶，key 由服务端派生（ADR-0023 §6.2）。
            // 与 UPLOAD 路径同样只返回文档展示信息，不把对象 key、桶名或预签名 URL 带出。
            StorageStream stream;
            try {
                stream = fileStorage.openObject(
                        tenantId.value(), connectorObjectKey(tenantId.value(), document.id(), document.createdAt()));
            } catch (StorageReadException exception) {
                // StorageReadException 有意不暴露 SDK 分类，无法安全区分 NoSuchKey 与瞬时读取失败；
                // 改写 FileStorage 又超出本任务范围，因此沿用 UPLOAD 路径的 KB-502，
                // 避免把存储故障伪装成“无源文件”而误导客户端放弃重试。
                throw new KnowledgeDownloadFailedException("connector source storage is unavailable");
            }
            return new KnowledgeDownload(document.title(), document.mimeType(), document.sizeBytes(), stream);
        }
        // SEED 语料或已清理对象：ACL 已通过，不泄露对象存在性。
        throw new KnowledgeSourceNotFoundException();
    }

    /**
     * 派生连接器文档的平台对象 key，必须与 worker {@code ConnectorContentTypes.objectKey} 完全一致。
     *
     * <p>年份取 {@code createdAt} 的 UTC 年；数据库存 UTC、API 侧按 UTC 解释，避免北京时间跨年时落到下一年。
     * key 只在服务端内部使用，不得进入响应、日志或审计。
     *
     * @param tenantId 租户代码
     * @param documentId 文档 ID
     * @param createdAt 文档创建时间
     * @return 平台对象 key
     */
    private static String connectorObjectKey(String tenantId, UUID documentId, Instant createdAt) {
        int year = createdAt.atZone(ZoneOffset.UTC).getYear();
        return "tenants/" + tenantId + "/connector/" + year + "/" + documentId;
    }
}
