package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.connector.domain.ConnectorCredential;
import com.riccon.worker.connector.domain.ConnectorDocumentRow;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import com.riccon.worker.connector.domain.ConnectorErrorCodes;
import com.riccon.worker.connector.domain.ConnectorFailureKind;
import com.riccon.worker.connector.domain.ConnectorGlobMatcher;
import com.riccon.worker.connector.domain.ConnectorItemRecord;
import com.riccon.worker.connector.domain.ConnectorObject;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncBudget;
import com.riccon.worker.connector.domain.ConnectorSyncRequest;
import com.riccon.worker.connector.domain.ConnectorSyncResult;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import com.riccon.worker.connector.domain.ConnectorTenantInfo;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 连接器同步核心流程。
 *
 * <p>首次全量、之后按 ETag/checksum 增量；未变化只更新 {@code last_seen_at}。只有完整 list 才传播删除；
 * 单对象失败继续并写 {@code FAILED}，同步整体为 {@code PARTIAL}。认证/网络失败按 1m/5m/15m 退避三次后
 * {@code ERROR}（ADR-0023 第 6.3/6.5/6.6 节）。
 *
 * <p>日志只记 connectorId/documentId 与计数，不记 bucket、externalId、对象 key 或凭据；凭据字符数组在使用后清零。
 */
@Service
public class ConnectorSyncService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorSyncService.class);

    /** S3 列表单页上限。 */
    private static final int LIST_PAGE_SIZE = 1_000;

    /** external_id 列长度。 */
    private static final int MAX_EXTERNAL_ID_LENGTH = 1_000;

    /** external_version 列长度。 */
    private static final int MAX_VERSION_LENGTH = 255;

    /** 默认保留天数。 */
    private static final int DEFAULT_RETENTION_DAYS = 180;

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 配置。 */
    private final ConnectorProperties properties;

    /** 凭据解密。 */
    private final ConnectorCredentials credentials;

    /** S3 客户端工厂。 */
    private final ConnectorS3ClientFactory clientFactory;

    /** 事件发布。 */
    private final ConnectorEventPublisher eventPublisher;

    /** 审计。 */
    private final ConnectorAuditWriter auditWriter;

    /** 终态收尾短事务。 */
    private final ConnectorSyncCompletionService completionService;

    /** 指标。 */
    private final ConnectorMetrics metrics;

    /** 时钟。 */
    private final Clock clock;

    /**
     * 创建同步服务。
     *
     * @param connectorRepository 仓储
     * @param properties 配置
     * @param credentials 凭据解密
     * @param clientFactory S3 客户端工厂
     * @param eventPublisher 事件发布
     * @param auditWriter 审计
     * @param metrics 指标
     * @param clock 时钟
     */
    public ConnectorSyncService(
            ConnectorRepository connectorRepository,
            ConnectorProperties properties,
            ConnectorCredentials credentials,
            ConnectorS3ClientFactory clientFactory,
            ConnectorEventPublisher eventPublisher,
            ConnectorAuditWriter auditWriter,
            ConnectorSyncCompletionService completionService,
            ConnectorMetrics metrics,
            Clock clock) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.completionService = Objects.requireNonNull(completionService, "completionService must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 执行一次同步并写终态。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param request 同步请求
     * @param traceId 追踪 ID
     * @return 同步结果
     */
    public ConnectorSyncResult synchronize(
            TenantId tenantId, ConnectorRecord connector, ConnectorSyncRequest request, String traceId) {
        Instant startedAt = clock.instant();
        connectorRepository.markSyncStarted(tenantId, connector.id(), startedAt);
        // sync.started 在同步发起处写：API 手动受理、Worker 定时调度各一次；执行处不重复写。
        ConnectorEndpointConfig endpoint = properties.registry() == null
                ? null
                : properties.registry().findById(connector.endpointId()).orElse(null);
        if (endpoint == null) {
            return finish(
                    tenantId,
                    connector,
                    request,
                    startedAt,
                    new SyncState(0, 0, 0, 0, 0, 0, false, ConnectorErrorCodes.CONFIG),
                    ConnectorFailureKind.CONFIG,
                    false,
                    traceId);
        }
        try {
            // 运行期再次解析，防止启动校验后 DNS 被改写为元数据/链路本地地址（TM-61）。
            properties.policy().verifyResolvedHost(java.net.URI.create(endpoint.url()), endpoint.id());
        } catch (IllegalArgumentException exception) {
            return finish(
                    tenantId,
                    connector,
                    request,
                    startedAt,
                    new SyncState(0, 0, 0, 0, 0, 0, false, ConnectorErrorCodes.CONFIG),
                    ConnectorFailureKind.CONFIG,
                    false,
                    traceId);
        }
        ConnectorCredential credential;
        try {
            credential = credentials.decrypt(tenantId, connector);
        } catch (RuntimeException exception) {
            return finish(
                    tenantId,
                    connector,
                    request,
                    startedAt,
                    new SyncState(0, 0, 0, 0, 0, 0, false, ConnectorErrorCodes.CREDENTIAL),
                    ConnectorFailureKind.CREDENTIAL,
                    false,
                    traceId);
        }
        UUID actorId;
        try {
            actorId = resolveActor(tenantId, connector);
        } catch (RuntimeException exception) {
            credential.close();
            return finish(
                    tenantId,
                    connector,
                    request,
                    startedAt,
                    new SyncState(0, 0, 0, 0, 0, 0, false, ConnectorErrorCodes.CONFIG),
                    ConnectorFailureKind.CONFIG,
                    false,
                    traceId);
        }
        SyncState state;
        ConnectorFailureKind failureKind;
        boolean retryable;
        try (credential;
                S3Client client = clientFactory.build(endpoint, connector.config(), credential)) {
            state = runSync(tenantId, connector, client, startedAt, actorId, traceId);
            failureKind = state.failureKind;
            retryable = state.retryable;
        } catch (SyncFailure failure) {
            state = new SyncState(failure.scanned, 0, 0, 0, 0, 0, false, failure.errorCode)
                    .withFailure(failure.errorCode, failure.kind, failure.retryable);
            failureKind = failure.kind;
            retryable = failure.retryable;
        } catch (RuntimeException exception) {
            // 不输出异常消息/堆栈：S3 异常可能携带 bucket 或对象 key（ADR-0023 §6.5）。
            LOG.error(
                    "connector.sync.failed connectorId={} errorCode={} exception={}",
                    connector.id(),
                    ConnectorErrorCodes.INTERNAL,
                    exception.getClass().getSimpleName());
            state = new SyncState(0, 0, 0, 0, 0, 0, false, ConnectorErrorCodes.INTERNAL);
            failureKind = ConnectorFailureKind.CONFIG;
            retryable = false;
        }
        // 终态收尾不在长同步 try/catch 内，避免审计/事务失败被误当作一次同步失败而再次 finish。
        return finish(tenantId, connector, request, startedAt, state, failureKind, retryable, traceId);
    }

    /**
     * 执行 list 与对象处理。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param client S3 客户端
     * @param startedAt 同步开始
     * @param actorId 发起人
     * @param traceId 追踪 ID
     * @return 同步状态
     */
    private SyncState runSync(
            TenantId tenantId,
            ConnectorRecord connector,
            S3Client client,
            Instant startedAt,
            UUID actorId,
            String traceId) {
        ConnectorSyncBudget budget = new ConnectorSyncBudget(
                startedAt, properties.syncTimeout(), properties.maxObjects(), properties.maxTotalBytes());
        SyncState state = new SyncState(0, 0, 0, 0, 0, 0, true, null);
        String continuation = null;
        do {
            if (budget.expired(clock.instant())) {
                state = state.withFailure(ConnectorErrorCodes.TIMEOUT, ConnectorFailureKind.TIMEOUT, false);
                break;
            }
            ListObjectsV2Response response;
            try {
                response = client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(connector.config().bucket())
                        .prefix(connector.config().prefix())
                        .maxKeys(LIST_PAGE_SIZE)
                        .continuationToken(continuation)
                        .build());
            } catch (S3Exception exception) {
                SyncFailure failure = classifyS3(exception, budget.scanned());
                state = state.withFailure(failure.errorCode, failure.kind, failure.retryable);
                break;
            } catch (SdkClientException exception) {
                SyncFailure failure = classifyClient(exception, budget.scanned());
                state = state.withFailure(failure.errorCode, failure.kind, failure.retryable);
                break;
            }
            for (S3Object object : response.contents()) {
                Instant now = clock.instant();
                if (budget.expired(now)) {
                    state = state.withFailure(ConnectorErrorCodes.TIMEOUT, ConnectorFailureKind.TIMEOUT, false);
                    break;
                }
                if (!budget.addObject()) {
                    state = state.withComplete(false).withError(ConnectorErrorCodes.OBJECT_LIMIT);
                    break;
                }
                ConnectorObject listed = new ConnectorObject(
                        object.key(), truncateVersion(object.eTag()), object.size() == null ? 0 : object.size());
                processObject(tenantId, connector, client, budget, state, listed, actorId, traceId);
                if (budget.truncated()) {
                    state = state.withComplete(false);
                    if (state.errorCode == null) {
                        state = state.withError(
                                budget.scanned() >= properties.maxObjects()
                                        ? ConnectorErrorCodes.OBJECT_LIMIT
                                        : ConnectorErrorCodes.TOTAL_BYTES_LIMIT);
                    }
                    break;
                }
            }
            if (state.errorCode != null || budget.truncated()) {
                break;
            }
            continuation = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuation != null);
        state.scanned = budget.scanned();
        if (state.complete) {
            propagateDeletes(tenantId, connector, startedAt, state, actorId, traceId);
        }
        return state;
    }

    /**
     * 处理单个对象；失败只影响当前项。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param client S3 客户端
     * @param budget 预算
     * @param state 同步状态
     * @param object 对象
     * @param actorId 发起人
     * @param traceId 追踪 ID
     */
    private void processObject(
            TenantId tenantId,
            ConnectorRecord connector,
            S3Client client,
            ConnectorSyncBudget budget,
            SyncState state,
            ConnectorObject object,
            UUID actorId,
            String traceId) {
        Instant now = clock.instant();
        String key = object.key();
        String extension = ConnectorContentTypes.extension(key);
        boolean supported =
                ConnectorContentTypes.supported(extension, connector.config().includeExtensions())
                        && !ConnectorGlobMatcher.excluded(connector.config().excludeGlobs(), key);
        if (key.length() > MAX_EXTERNAL_ID_LENGTH) {
            // 无法写入 external_id 列，只能计入失败；list 本身仍完整，不影响删除传播。
            state.failed++;
            return;
        }
        String hash = sha256Hex(key);
        ConnectorItemRecord item = findOrCreateItem(tenantId, connector.id(), key, hash, object.etag(), now);
        if (item.status() == ConnectorItemStatus.DELETED) {
            connectorRepository.resetTombstone(tenantId, item.id(), object.etag(), now);
            item = new ConnectorItemRecord(
                    item.id(),
                    key,
                    object.etag(),
                    null,
                    null,
                    item.generation() + 1,
                    ConnectorItemStatus.PENDING,
                    null,
                    now,
                    null);
        }
        if (!supported) {
            connectorRepository.markItemSkipped(
                    tenantId, item.id(), ConnectorErrorCodes.TYPE_UNSUPPORTED, object.etag(), now);
            state.skipped++;
            return;
        }
        if (object.sizeBytes() > properties.maxObjectBytes()) {
            connectorRepository.markItemFailed(
                    tenantId, item.id(), ConnectorErrorCodes.OBJECT_TOO_LARGE, object.etag(), now);
            state.failed++;
            return;
        }
        try {
            processSupportedObject(
                    tenantId, connector, client, budget, state, item, object, extension, actorId, traceId);
        } catch (DownloadFailure failure) {
            connectorRepository.markItemFailed(tenantId, item.id(), failure.errorCode, object.etag(), now);
            state.failed++;
        } catch (RuntimeException exception) {
            LOG.warn(
                    "connector.sync.object_failed connectorId={} documentId={} errorCode={} exception={}",
                    connector.id(),
                    item.documentId(),
                    ConnectorErrorCodes.OBJECT_FAILED,
                    exception.getClass().getSimpleName());
            connectorRepository.markItemFailed(
                    tenantId, item.id(), ConnectorErrorCodes.OBJECT_FAILED, object.etag(), now);
            state.failed++;
        }
    }

    /**
     * 处理类型受支持的对象：未变化跳过；变化/新增下载并写对象、文档与索引事件。
     */
    private void processSupportedObject(
            TenantId tenantId,
            ConnectorRecord connector,
            S3Client client,
            ConnectorSyncBudget budget,
            SyncState state,
            ConnectorItemRecord item,
            ConnectorObject object,
            String extension,
            UUID actorId,
            String traceId) {
        Instant now = clock.instant();
        LoadedDocument document = item.documentId() == null ? null : loadDocument(tenantId, item.documentId());
        if (item.status() == ConnectorItemStatus.SYNCED
                && document != null
                && document.active()
                && Objects.equals(item.externalVersion(), object.etag())) {
            connectorRepository.touchItem(tenantId, item.id(), object.etag(), now);
            return;
        }
        Download downloaded = download(client, connector, object, budget);
        try {
            if (document != null && document.active()) {
                if (downloaded.checksum().equals(document.checksum())) {
                    connectorRepository.markItemSynced(
                            tenantId,
                            item.id(),
                            document.row().documentId(),
                            downloaded.checksum(),
                            object.etag(),
                            now);
                    return;
                }
                String objectKey = ConnectorContentTypes.objectKey(
                        tenantId.value(), document.row().documentId(), document.createdAt());
                writeObject(client, connector, objectKey, downloaded.path(), extension);
                connectorRepository.updateDocumentContent(
                        tenantId,
                        document.row().documentId(),
                        downloaded.checksum(),
                        downloaded.sizeBytes(),
                        ConnectorContentTypes.mimeType(extension),
                        now);
                connectorRepository.markItemSynced(
                        tenantId, item.id(), document.row().documentId(), downloaded.checksum(), object.etag(), now);
                publishIndex(
                        tenantId,
                        connector.id(),
                        document.row().documentId(),
                        document.aclVersion(),
                        actorId,
                        traceId,
                        now);
                state.updated++;
                return;
            }
            UUID documentId = UuidV7.generate();
            String objectKey = ConnectorContentTypes.objectKey(tenantId.value(), documentId, now);
            writeObject(client, connector, objectKey, downloaded.path(), extension);
            int retentionDays = connectorRepository
                    .findTenant(tenantId)
                    .map(ConnectorTenantInfo::retentionDays)
                    .orElse(DEFAULT_RETENTION_DAYS);
            connectorRepository.insertDocument(
                    tenantId,
                    documentId,
                    ConnectorContentTypes.title(object.key()),
                    connector.id(),
                    object.key(),
                    downloaded.checksum(),
                    ConnectorContentTypes.mimeType(extension),
                    downloaded.sizeBytes(),
                    connector.defaultVisibility(),
                    connector.defaultDepartmentId(),
                    connector.createdBy(),
                    now.plus(Duration.ofDays(retentionDays)),
                    now);
            connectorRepository.markItemSynced(
                    tenantId, item.id(), documentId, downloaded.checksum(), object.etag(), now);
            publishIndex(tenantId, connector.id(), documentId, 1, actorId, traceId, now);
            state.created++;
        } finally {
            deleteQuietly(downloaded.path());
        }
    }

    /**
     * 完整 list 后传播源端删除；只删除本轮未见且上次出现早于开始时间的条目。
     */
    private void propagateDeletes(
            TenantId tenantId,
            ConnectorRecord connector,
            Instant startedAt,
            SyncState state,
            UUID actorId,
            String traceId) {
        for (ConnectorItemRecord item : connectorRepository.findUnseen(tenantId, connector.id(), startedAt)) {
            if (connectorRepository.markItemDeleted(tenantId, item.id(), clock.instant()) == 0) {
                continue;
            }
            state.deleted++;
            if (item.documentId() == null) {
                continue;
            }
            LoadedDocument document = loadDocument(tenantId, item.documentId());
            if (document == null || !document.active()) {
                continue;
            }
            Instant now = clock.instant();
            if (connectorRepository.softDeleteDocument(tenantId, item.documentId(), now) == 0) {
                continue;
            }
            auditWriter.documentDeleted(tenantId, item.documentId(), document.visibility(), traceId);
            try {
                eventPublisher.enqueueDelete(tenantId, item.documentId(), document.aclVersion(), actorId, traceId, now);
            } catch (DuplicateKeyException exception) {
                // API 删除连接器时可能已投递同名 DELETE 事件；唯一键已保证幂等。
            }
        }
    }

    /**
     * 下载对象到临时文件并计算 SHA-256。
     */
    private Download download(
            S3Client client, ConnectorRecord connector, ConnectorObject object, ConnectorSyncBudget budget) {
        Path temp = null;
        try {
            temp = Files.createTempFile("riccon-connector-", ".tmp");
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(connector.config().bucket())
                    .key(object.key())
                    .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                            .apiCallTimeout(properties.objectTimeout())
                            .apiCallAttemptTimeout(properties.objectTimeout())
                            .build())
                    .build();
            // createTempFile 已经创建空文件；toFile 对已存在文件可能失败，改用输出流显式截断。
            try (java.io.OutputStream output =
                    Files.newOutputStream(temp, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                client.getObject(request, ResponseTransformer.toOutputStream(output));
            }
            long size = Files.size(temp);
            if (size > properties.maxObjectBytes()) {
                throw new DownloadFailure(ConnectorErrorCodes.OBJECT_TOO_LARGE);
            }
            if (!budget.addBytes(size)) {
                throw new DownloadFailure(ConnectorErrorCodes.TOTAL_BYTES_LIMIT);
            }
            String checksum = sha256Hex(temp);
            return new Download(temp, checksum, size);
        } catch (S3Exception exception) {
            deleteQuietly(temp);
            throw new DownloadFailure(
                    exception.statusCode() == 401 || exception.statusCode() == 403
                            ? ConnectorErrorCodes.AUTH
                            : ConnectorErrorCodes.OBJECT_FAILED);
        } catch (SdkClientException exception) {
            deleteQuietly(temp);
            throw new DownloadFailure(
                    hasCause(exception, java.net.SocketTimeoutException.class)
                            ? ConnectorErrorCodes.TIMEOUT
                            : ConnectorErrorCodes.OBJECT_FAILED);
        } catch (IOException exception) {
            deleteQuietly(temp);
            throw new DownloadFailure(ConnectorErrorCodes.OBJECT_FAILED);
        }
    }

    /**
     * 写入服务端派生 key 的对象。
     */
    private void writeObject(
            S3Client client, ConnectorRecord connector, String objectKey, Path path, String extension) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(connector.config().bucket())
                            .key(objectKey)
                            .contentType(ConnectorContentTypes.mimeType(extension))
                            .build(),
                    RequestBody.fromFile(path));
        } catch (S3Exception exception) {
            throw new DownloadFailure(
                    exception.statusCode() == 401 || exception.statusCode() == 403
                            ? ConnectorErrorCodes.AUTH
                            : ConnectorErrorCodes.OBJECT_FAILED);
        } catch (SdkClientException exception) {
            throw new DownloadFailure(ConnectorErrorCodes.OBJECT_FAILED);
        }
    }

    /**
     * 投递全文索引事件；重复键视为已投递。
     */
    private void publishIndex(
            TenantId tenantId,
            UUID connectorId,
            UUID documentId,
            int aclVersion,
            UUID actorId,
            String traceId,
            Instant now) {
        try {
            eventPublisher.enqueueIndex(tenantId, documentId, aclVersion, actorId, traceId, now);
        } catch (DuplicateKeyException exception) {
            LOG.info("connector.sync.index_duplicate connectorId={} documentId={}", connectorId, documentId);
        }
    }

    /**
     * 写终态、审计、指标并按需安排重试。
     */
    private ConnectorSyncResult finish(
            TenantId tenantId,
            ConnectorRecord connector,
            ConnectorSyncRequest request,
            Instant startedAt,
            SyncState state,
            ConnectorFailureKind failureKind,
            boolean retryable,
            String traceId) {
        Instant finishedAt = clock.instant();
        ConnectorSyncStatus status;
        if (state.errorCode != null && failureKind != ConnectorFailureKind.NONE) {
            status = ConnectorSyncStatus.ERROR;
        } else if (state.errorCode != null) {
            status = ConnectorSyncStatus.PARTIAL;
        } else if (!state.complete || state.failed > 0) {
            status = ConnectorSyncStatus.PARTIAL;
        } else {
            status = ConnectorSyncStatus.SUCCESS;
        }
        ConnectorSyncSummary summary = new ConnectorSyncSummary(
                state.scanned,
                state.created,
                state.updated,
                state.skipped,
                state.failed,
                state.deleted,
                state.complete && status != ConnectorSyncStatus.ERROR);
        ConnectorStatus connectorStatus = status == ConnectorSyncStatus.ERROR
                ? ConnectorStatus.ERROR
                : (connector.status() == ConnectorStatus.ERROR ? ConnectorStatus.ACTIVE : connector.status());
        Instant nextSyncAt = connector.scheduleEnabled()
                ? finishedAt.plus(Duration.ofMinutes(properties.syncIntervalMinutes()))
                : null;
        String errorCode = state.errorCode;
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        // 终态与完成/失败/状态审计必须同一短事务；失败会抛异常，由 outbox 重试，不留下“终态已落库但审计缺失”。
        completionService.complete(
                tenantId,
                connector,
                request.syncId(),
                startedAt,
                finishedAt,
                status,
                summary.complete(),
                errorCode,
                summary,
                connectorStatus,
                nextSyncAt,
                durationMs,
                retryable,
                traceId);
        if (status == ConnectorSyncStatus.ERROR) {
            recordMetric(failureKind);
            scheduleRetry(tenantId, connector, request, failureKind, retryable, traceId, finishedAt);
        }
        LOG.info(
                "connector.sync.finished connectorId={} syncId={} status={} complete={} scanned={} created={} updated={}"
                        + " skipped={} failed={} deleted={} durationMs={}",
                connector.id(),
                request.syncId(),
                status,
                summary.complete(),
                summary.scanned(),
                summary.created(),
                summary.updated(),
                summary.skipped(),
                summary.failed(),
                summary.deleted(),
                durationMs);
        return new ConnectorSyncResult(
                status, summary.complete(), summary, errorCode, failureKind, retryable, durationMs);
    }

    /**
     * 认证/网络失败按 1m/5m/15m 安排下一次重试；已用尽则保持 ERROR。
     */
    private void scheduleRetry(
            TenantId tenantId,
            ConnectorRecord connector,
            ConnectorSyncRequest request,
            ConnectorFailureKind failureKind,
            boolean retryable,
            String traceId,
            Instant now) {
        if (!retryable
                || request.retryAttempt() >= properties.retryDelays().size()
                || (failureKind != ConnectorFailureKind.AUTH && failureKind != ConnectorFailureKind.NETWORK)) {
            return;
        }
        Duration delay = properties.retryDelays().get(request.retryAttempt());
        int nextAttempt = request.retryAttempt() + 1;
        UUID actorId;
        try {
            actorId = resolveActor(tenantId, connector);
        } catch (RuntimeException exception) {
            LOG.warn("connector.sync.retry_skipped connectorId={} reason=no_actor", connector.id());
            return;
        }
        try {
            eventPublisher.enqueueRetry(
                    tenantId,
                    connector,
                    request.syncId(),
                    nextAttempt,
                    failureKind.name(),
                    now.plus(delay),
                    actorId,
                    traceId,
                    now);
        } catch (DuplicateKeyException exception) {
            // 同一 syncId 的重试事件已存在；保持幂等。
        }
    }

    /**
     * 记录指标；只映射 ADR 要求的四个 reason。
     */
    private void recordMetric(ConnectorFailureKind kind) {
        switch (kind) {
            case AUTH -> metrics.syncFailure("auth");
            case NETWORK -> metrics.syncFailure("network");
            case TIMEOUT -> metrics.syncFailure("timeout");
            case CREDENTIAL -> metrics.syncFailure("credential");
            default -> {
                // 配置/内部错误不写入连接器失败指标，避免把部署问题算作源端失败。
            }
        }
    }

    /**
     * 解析事件发起人：连接器创建人，否则租户第一个启用账号。
     */
    private UUID resolveActor(TenantId tenantId, ConnectorRecord connector) {
        if (connector.createdBy() != null) {
            return connector.createdBy();
        }
        return connectorRepository
                .findFirstActiveUserId(tenantId)
                .orElseThrow(() -> new IllegalStateException("no active user for connector sync"));
    }

    /**
     * 查找或创建条目。
     */
    private ConnectorItemRecord findOrCreateItem(
            TenantId tenantId, UUID connectorId, String externalId, String hash, String version, Instant now) {
        return connectorRepository.findItem(tenantId, connectorId, hash).orElseGet(() -> {
            connectorRepository.insertItem(tenantId, connectorId, UuidV7.generate(), externalId, hash, version, now);
            return connectorRepository
                    .findItem(tenantId, connectorId, hash)
                    .orElseThrow(() -> new IllegalStateException("connector item insert is not visible"));
        });
    }

    /**
     * 读取文档并包装创建时间。
     */
    private LoadedDocument loadDocument(TenantId tenantId, UUID documentId) {
        return connectorRepository
                .findDocument(tenantId, documentId)
                .map(row -> new LoadedDocument(row, row.createdAt() == null ? clock.instant() : row.createdAt()))
                .orElse(null);
    }

    /**
     * 分类 S3 列表错误。
     */
    private static SyncFailure classifyS3(S3Exception exception, int scanned) {
        int status = exception.statusCode();
        if (status == 401 || status == 403) {
            return new SyncFailure(ConnectorErrorCodes.AUTH, ConnectorFailureKind.AUTH, true, scanned, "auth");
        }
        if (status >= 500) {
            return new SyncFailure(
                    ConnectorErrorCodes.UNREACHABLE, ConnectorFailureKind.NETWORK, true, scanned, "network");
        }
        return new SyncFailure(ConnectorErrorCodes.CONFIG, ConnectorFailureKind.CONFIG, false, scanned, "config");
    }

    /**
     * 分类 SDK 客户端错误。
     */
    private static SyncFailure classifyClient(SdkClientException exception, int scanned) {
        if (hasCause(exception, UnknownHostException.class)
                || hasCause(exception, ConnectException.class)
                || hasCause(exception, java.net.SocketTimeoutException.class)
                || exception.getCause() instanceof java.io.IOException) {
            return new SyncFailure(
                    ConnectorErrorCodes.UNREACHABLE, ConnectorFailureKind.NETWORK, true, scanned, "network");
        }
        return new SyncFailure(ConnectorErrorCodes.UNREACHABLE, ConnectorFailureKind.NETWORK, true, scanned, "network");
    }

    /**
     * 判断异常链是否包含指定类型。
     */
    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 计算 SHA-256 十六进制。
     */
    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 计算文件 SHA-256 十六进制。
     */
    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new DownloadFailure(ConnectorErrorCodes.OBJECT_FAILED);
        }
    }

    /**
     * 计算字节数组 SHA-256 十六进制。
     */
    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 截断 ETag。
     */
    private static String truncateVersion(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_VERSION_LENGTH ? value : value.substring(0, MAX_VERSION_LENGTH);
    }

    /**
     * 静默删除临时文件；失败只影响清理。
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时目录由操作系统回收；清理失败不掩盖同步结果。
        }
    }

    /**
     * 同步状态与计数（运行期可变）。
     */
    private static final class SyncState {

        /** 扫描数。 */
        private long scanned;

        /** 新建数。 */
        private long created;

        /** 更新数。 */
        private long updated;

        /** 跳过数。 */
        private long skipped;

        /** 失败数。 */
        private long failed;

        /** 删除数。 */
        private long deleted;

        /** list 是否完整。 */
        private boolean complete;

        /** 错误码。 */
        private String errorCode;

        /** 失败分类。 */
        private ConnectorFailureKind failureKind = ConnectorFailureKind.NONE;

        /** 是否可重试。 */
        private boolean retryable;

        /**
         * 创建状态。
         */
        private SyncState(
                long scanned,
                long created,
                long updated,
                long skipped,
                long failed,
                long deleted,
                boolean complete,
                String errorCode) {
            this.scanned = scanned;
            this.created = created;
            this.updated = updated;
            this.skipped = skipped;
            this.failed = failed;
            this.deleted = deleted;
            this.complete = complete;
            this.errorCode = errorCode;
        }

        /** 返回新状态并替换字段（不可变风格便于阅读）。 */
        private SyncState withComplete(boolean value) {
            this.complete = value;
            return this;
        }

        /** 设置错误码。 */
        private SyncState withError(String value) {
            this.errorCode = value;
            return this;
        }

        /** 设置错误码、失败分类与可重试标记。 */
        private SyncState withFailure(String code, ConnectorFailureKind kind, boolean canRetry) {
            this.complete = false;
            this.errorCode = code;
            this.failureKind = kind;
            this.retryable = canRetry;
            return this;
        }
    }

    /**
     * 文档行与创建时间。
     */
    private record LoadedDocument(ConnectorDocumentRow row, Instant createdAt) {

        private UUID documentId() {
            return row.documentId();
        }

        private String checksum() {
            return row.checksum();
        }

        private int aclVersion() {
            return row.aclVersion();
        }

        private com.riccon.platform.domain.knowledge.KnowledgeVisibility visibility() {
            return row.visibility();
        }

        private boolean active() {
            return row.active();
        }
    }

    /**
     * 下载结果。
     */
    private record Download(Path path, String checksum, long sizeBytes) {}

    /**
     * 单对象失败；由调用方标记 FAILED，不中断同步。
     */
    private static final class DownloadFailure extends RuntimeException {

        /** 错误码。 */
        private final String errorCode;

        private DownloadFailure(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }
    }

    /**
     * 同步级失败。
     */
    private static final class SyncFailure extends RuntimeException {

        /** 错误码。 */
        private final String errorCode;

        /** 分类。 */
        private final ConnectorFailureKind kind;

        /** 是否可重试。 */
        private final boolean retryable;

        /** 已扫描数。 */
        private final int scanned;

        private SyncFailure(
                String errorCode, ConnectorFailureKind kind, boolean retryable, int scanned, String message) {
            super(message);
            this.errorCode = errorCode;
            this.kind = kind;
            this.retryable = retryable;
            this.scanned = scanned;
        }
    }
}
