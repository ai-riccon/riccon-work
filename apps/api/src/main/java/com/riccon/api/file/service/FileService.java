package com.riccon.api.file.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.Sha256;
import com.riccon.api.common.UuidV7;
import com.riccon.api.config.StorageProperties;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.FileLimitExceededException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileParseRecord;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.domain.ObjectKeys;
import com.riccon.api.file.dto.DownloadUrlResponse;
import com.riccon.api.file.dto.FilePageResponse;
import com.riccon.api.file.dto.FileResponse;
import com.riccon.api.file.repository.FileRepository;
import com.riccon.api.file.storage.FileStorage;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.workspace.service.WorkspaceService;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileStatus;
import com.riccon.platform.domain.file.ParseStatus;
import com.riccon.platform.domain.research.RunKind;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传、解析状态、下载与删除。
 *
 * <p>上传先把原文写入对象存储，再在同一事务写 file、file_parse、agent_run、outbox 与 {@code file.uploaded}
 * 审计；任一步失败都会回滚元数据并删除已写对象（ADR-0007）。解析重试只重新投递 outbox，不同步执行解析。
 */
@Service
public class FileService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(FileService.class);

    /** 单会话文件上限；会话层还会在附件选择阶段提前提示。 */
    private static final int MAX_FILES_PER_CONVERSATION = 5;

    /** 文件仓储。 */
    private final FileRepository fileRepository;

    /** 工作空间可见性。 */
    private final WorkspaceService workspaceService;

    /** 运行创建。 */
    private final RunRepository runRepository;

    /** Outbox 事件。 */
    private final OutboxRepository outboxRepository;

    /** 对象存储。 */
    private final FileStorage fileStorage;

    /** 上传预处理。 */
    private final FileUploadPreparer uploadPreparer;

    /** 审计写入。 */
    private final AuditService auditService;

    /** 存储配置，提供下载地址 TTL。 */
    private final StorageProperties storageProperties;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建文件服务。
     *
     * @param fileRepository 文件仓储
     * @param workspaceService 工作空间服务
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     * @param fileStorage 对象存储
     * @param uploadPreparer 上传预处理
     * @param auditService 审计写入
     * @param storageProperties 存储配置
     * @param clock 可注入时钟
     */
    public FileService(
            FileRepository fileRepository,
            WorkspaceService workspaceService,
            RunRepository runRepository,
            OutboxRepository outboxRepository,
            FileStorage fileStorage,
            FileUploadPreparer uploadPreparer,
            AuditService auditService,
            StorageProperties storageProperties,
            Clock clock) {
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.fileStorage = Objects.requireNonNull(fileStorage, "fileStorage must not be null");
        this.uploadPreparer = Objects.requireNonNull(uploadPreparer, "uploadPreparer must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 上传文件并投递解析事件。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话 ID
     * @param file multipart 文件
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 已受理的文件
     */
    @Transactional
    public FileResponse upload(
            RicconPrincipal actor,
            UUID workspaceId,
            UUID conversationId,
            MultipartFile file,
            String traceId,
            String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        if (conversationId != null) {
            requireConversationCapacity(tenantId, workspaceId, conversationId);
        }

        FileUploadPreparer.PreparedUpload prepared = uploadPreparer.prepare(file);
        try {
            Instant now = clock.instant();
            UUID fileId = UuidV7.generate(now);
            UUID parseId = UuidV7.generate(now);
            UUID runId = UuidV7.generate(now);
            UUID eventId = UuidV7.generate(now);
            String storageKey = ObjectKeys.fileKey(actor.tenantId(), fileId, now);
            fileStorage.putObject(actor.tenantId(), storageKey, prepared.path(), prepared.mimeType());
            cleanupObjectOnRollback(actor.tenantId(), storageKey);

            FileParseRecord parse = new FileParseRecord(
                    parseId, 1, ParseStatus.PENDING, null, null, null, null, null, null, null, null);
            FileRecord record = new FileRecord(
                    fileId,
                    workspaceId,
                    conversationId,
                    prepared.originalName(),
                    prepared.format(),
                    prepared.mimeType(),
                    prepared.sizeBytes(),
                    prepared.sha256(),
                    storageKey,
                    FileStatus.AVAILABLE,
                    parse,
                    now,
                    null);

            fileRepository.insertFile(tenantId, record, actor.userId());
            fileRepository.insertParse(tenantId, fileId, parse, now);
            runRepository.createPending(tenantId, runId, RunKind.FILE_PARSE, fileId, actor.userId(), traceId, now);
            outboxRepository.enqueue(
                    tenantId,
                    new OutboxEvent(
                            eventId,
                            runId,
                            EventTypes.FILE_PARSE_REQUESTED,
                            1,
                            "file:" + fileId + ":parse:" + parseId,
                            fileParsePayload(fileId, parseId, 1),
                            now));
            auditService.append(AuditEvent.create(
                    now,
                    actor.tenantId(),
                    AuditActorType.USER,
                    actor.userId().toString(),
                    AuditActions.FILE_UPLOADED,
                    AuditTargetType.FILE,
                    fileId.toString(),
                    AuditOutcome.SUCCESS,
                    traceId,
                    requestId,
                    null,
                    Map.of(
                            "fileId", fileId.toString(),
                            "sizeBytes", prepared.sizeBytes(),
                            "contentType", prepared.mimeType(),
                            "sha256", prepared.sha256())));
            LOG.info(
                    "file.uploaded tenantId={} fileId={} sizeBytes={} format={}",
                    actor.tenantId(),
                    fileId,
                    prepared.sizeBytes(),
                    prepared.format());
            return FileResponse.from(record);
        } finally {
            deleteTemporary(prepared.path());
        }
    }

    /**
     * 列出工作空间文件。
     *
     * @param actor 当前员工
     * @param workspaceId 工作空间 ID
     * @param conversationId 可选会话过滤
     * @param page 分页参数
     * @return 文件分页
     */
    @Transactional(readOnly = true)
    public FilePageResponse list(RicconPrincipal actor, UUID workspaceId, UUID conversationId, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        workspaceService.requireAccessible(tenantId, workspaceId, actor.userId());
        long total = fileRepository.countAccessible(tenantId, actor.userId(), workspaceId, conversationId);
        List<FileResponse> items = fileRepository
                .listAccessible(tenantId, actor.userId(), workspaceId, conversationId, page.size(), page.offset())
                .stream()
                .map(FileResponse::from)
                .toList();
        return new FilePageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取文件元数据与最近一次解析状态。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @return 文件
     */
    @Transactional(readOnly = true)
    public FileResponse get(RicconPrincipal actor, UUID fileId) {
        return FileResponse.from(requireVisibleFile(actor, fileId));
    }

    /**
     * 重试失败的文件解析。
     *
     * <p>只创建新的 {@code file_parse} 尝试并重新投递 outbox，不同步解析；历史失败记录保留。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @param traceId 追踪 ID
     * @return 新解析尝试下的文件
     */
    @Transactional
    public FileResponse retryParse(RicconPrincipal actor, UUID fileId, String traceId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        FileRecord file = requireVisibleFileForUpdate(actor, fileId);
        FileParseRecord latest = fileRepository
                .findLatestParse(tenantId, fileId)
                .orElseThrow(() -> new ConflictException("file has no parse attempt"));
        if (latest.status() != ParseStatus.FAILED) {
            throw new ConflictException("only failed file parses can be retried");
        }
        int attempt = latest.attempt() + 1;
        Instant now = clock.instant();
        UUID parseId = UuidV7.generate(now);
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        FileParseRecord parse = new FileParseRecord(
                parseId, attempt, ParseStatus.PENDING, null, null, null, null, null, null, null, null);
        try {
            fileRepository.insertParse(tenantId, fileId, parse, now);
        } catch (DuplicateKeyException exception) {
            // 并发重试只允许一个请求创建 attempt；另一个请求返回冲突而不是产生重复事件。
            throw new ConflictException("file parse attempt already exists");
        }
        runRepository.createPending(tenantId, runId, RunKind.FILE_PARSE, fileId, actor.userId(), traceId, now);
        outboxRepository.enqueue(
                tenantId,
                new OutboxEvent(
                        eventId,
                        runId,
                        EventTypes.FILE_PARSE_REQUESTED,
                        1,
                        "file:" + fileId + ":parse:" + parseId,
                        fileParsePayload(fileId, parseId, attempt),
                        now));
        return FileResponse.from(new FileRecord(
                file.id(),
                file.workspaceId(),
                file.conversationId(),
                file.originalName(),
                file.format(),
                file.mimeType(),
                file.sizeBytes(),
                file.sha256(),
                file.storageKey(),
                file.status(),
                parse,
                file.createdAt(),
                file.deletedAt()));
    }

    /**
     * 获取短期下载地址。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @return 预签名地址与过期时间
     */
    @Transactional(readOnly = true)
    public DownloadUrlResponse downloadUrl(RicconPrincipal actor, UUID fileId) {
        FileRecord file = requireVisibleFile(actor, fileId);
        Duration ttl = storageProperties.downloadUrlTtl();
        URI url = fileStorage.presignGet(actor.tenantId(), file.storageKey(), ttl);
        return new DownloadUrlResponse(url.toString(), clock.instant().plus(ttl));
    }

    /**
     * 软删除文件并写审计；对象清理由提交后的维护任务执行。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void delete(RicconPrincipal actor, UUID fileId, String traceId, String requestId) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        FileRecord file = requireVisibleFileForUpdate(actor, fileId);
        Instant now = clock.instant();
        if (fileRepository.softDelete(tenantId, fileId, now) == 0) {
            throw new NotFoundException("file");
        }
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AuditActions.FILE_DELETED,
                AuditTargetType.FILE,
                fileId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                Map.of("fileId", fileId.toString(), "objectKeyHash", Sha256.hex(file.storageKey()))));
        LOG.info("file.deleted tenantId={} fileId={}", actor.tenantId(), fileId);
    }

    /**
     * 校验会话属于工作空间并检查 5 个文件上限。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param conversationId 会话 ID
     */
    private void requireConversationCapacity(TenantId tenantId, UUID workspaceId, UUID conversationId) {
        fileRepository
                .lockConversation(tenantId, workspaceId, conversationId)
                .orElseThrow(() -> new NotFoundException("conversation"));
        if (fileRepository.countConversationFiles(tenantId, conversationId) >= MAX_FILES_PER_CONVERSATION) {
            throw new FileLimitExceededException();
        }
    }

    /**
     * 查询当前员工可见的可用文件。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @return 文件
     */
    private FileRecord requireVisibleFile(RicconPrincipal actor, UUID fileId) {
        return fileRepository
                .findAccessible(TenantId.of(actor.tenantId()), fileId, actor.userId())
                .orElseThrow(() -> new NotFoundException("file"));
    }

    /**
     * 锁定并查询当前员工可见的可用文件。
     *
     * @param actor 当前员工
     * @param fileId 文件 ID
     * @return 文件
     */
    private FileRecord requireVisibleFileForUpdate(RicconPrincipal actor, UUID fileId) {
        return fileRepository
                .lockAccessible(TenantId.of(actor.tenantId()), fileId, actor.userId())
                .orElseThrow(() -> new NotFoundException("file"));
    }

    /**
     * 注册事务回滚补偿：对象先于元数据写入，元数据失败时必须删除对象。
     *
     * @param tenantId 租户
     * @param storageKey 对象 key
     */
    private void cleanupObjectOnRollback(String tenantId, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileStorage.deleteQuietly(tenantId, storageKey);
            throw new IllegalStateException("file upload must run inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    fileStorage.deleteQuietly(tenantId, storageKey);
                }
            }
        });
    }

    /**
     * 删除上传临时文件。
     *
     * @param path 临时文件
     */
    private void deleteTemporary(java.nio.file.Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOG.warn("file.upload.temp_cleanup_failed path={}", path, exception);
        }
    }

    /**
     * 构造文件解析事件 payload；只放 ID 与尝试号。
     *
     * @param fileId 文件 ID
     * @param parseId 解析尝试 ID
     * @param attempt 尝试号
     * @return payload
     */
    private static Map<String, Object> fileParsePayload(UUID fileId, UUID parseId, int attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", fileId.toString());
        payload.put("parseId", parseId.toString());
        payload.put("attempt", attempt);
        return payload;
    }
}
