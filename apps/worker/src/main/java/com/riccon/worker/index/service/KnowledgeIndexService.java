package com.riccon.worker.index.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.index.domain.EmbeddingModel;
import com.riccon.worker.index.domain.IndexException;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.index.domain.KnowledgeDocumentRecord;
import com.riccon.worker.index.repository.KnowledgeRepository;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.repository.FileRepository;
import com.riccon.worker.parse.service.FileContentExtractor;
import com.riccon.worker.parse.service.TextChunker;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import com.riccon.worker.run.service.EventPayloadParser;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 知识库索引事件处理器。
 *
 * <p>领取 {@code riccon.knowledge.index.requested.v1} 后按 {@link KnowledgeIndexMode} 执行：
 *
 * <ul>
 *   <li>{@code FULL}：从来源文件或已有分块重建权威 {@code knowledge_chunk}，写 parse_* 元数据，
 *       生成 embedding 并原子替换该文档的 OpenSearch 文档；重复投递先删后插，不产生重复结果。
 *   <li>{@code ACL_ONLY}：只读现有 MySQL 分块并重写索引文档，保持 {@code chunk_id} 稳定，
 *       不替换分块、不重新解析；payload 的 {@code aclVersion} 小于数据库当前值时视为过期事件跳过。
 *   <li>{@code DELETE}：删除该文档的分块与索引文档；文档已由 API 软删除，本模式不改文档状态。
 * </ul>
 *
 * <p>索引字段不作为授权依据（ADR-0023 第 5.5 节）；ACL 变更的下一次 HTTP 请求以 MySQL 为准，
 * ACL_ONLY 只负责最终一致的索引诊断字段。
 */
@Component
public class KnowledgeIndexService implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIndexService.class);

    /** 知识库仓库。 */
    private final KnowledgeRepository knowledgeRepository;

    /** 文件仓库（来源文件元数据）。 */
    private final FileRepository fileRepository;

    /** 文件内容抽取器。 */
    private final FileContentExtractor contentExtractor;

    /** 文本切分器。 */
    private final TextChunker textChunker;

    /** 可插拔向量模型。 */
    private final EmbeddingModel embeddingModel;

    /** OpenSearch 索引客户端。 */
    private final OpenSearchIndexClient indexClient;

    /** payload 解析器。 */
    private final EventPayloadParser payloadParser;

    /**
     * 创建知识库索引处理器。
     *
     * @param knowledgeRepository 知识库仓库
     * @param fileRepository 文件仓库
     * @param contentExtractor 内容抽取器
     * @param textChunker 文本切分器
     * @param embeddingModel 向量模型
     * @param indexClient OpenSearch 客户端
     * @param payloadParser payload 解析器
     */
    public KnowledgeIndexService(
            KnowledgeRepository knowledgeRepository,
            FileRepository fileRepository,
            FileContentExtractor contentExtractor,
            TextChunker textChunker,
            EmbeddingModel embeddingModel,
            OpenSearchIndexClient indexClient,
            EventPayloadParser payloadParser) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.contentExtractor = Objects.requireNonNull(contentExtractor, "contentExtractor must not be null");
        this.textChunker = Objects.requireNonNull(textChunker, "textChunker must not be null");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.indexClient = Objects.requireNonNull(indexClient, "indexClient must not be null");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser must not be null");
    }

    /** 返回知识库索引事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.KNOWLEDGE_INDEX_REQUESTED;
    }

    /** 返回知识库索引运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.KNOWLEDGE_INDEX;
    }

    /**
     * 处理知识库索引事件。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID documentId = payloadParser.requiredUuid(payload, "documentId");
        payloadParser.requiredPositiveInt(payload, "attempt");
        UUID payloadFileId = payloadParser.optionalUuid(payload, "fileId");
        KnowledgeIndexMode mode = readMode(payload);
        Integer aclVersion = readOptionalPositiveInt(payload, "aclVersion");
        boolean force = readOptionalBoolean(payload, "force");
        TenantId tenantId = TenantContext.require();

        KnowledgeDocumentRecord document = knowledgeRepository
                .findDocument(tenantId, documentId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "knowledge document not found"));
        switch (mode) {
            case DELETE -> handleDelete(tenantId, document);
            case ACL_ONLY -> handleAclOnly(tenantId, document, aclVersion);
            case FULL -> handleFull(tenantId, document, payloadFileId, force);
        }
    }

    /**
     * FULL：重建权威分块并原子替换索引，同时写 parse_* 元数据。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     * @param payloadFileId payload 携带的来源文件
     * @param force 是否整本重做；当前解析链总是整本解析，字段用于审计与后续缺失页优化
     */
    private void handleFull(TenantId tenantId, KnowledgeDocumentRecord document, UUID payloadFileId, boolean force) {
        if (document.status() == KnowledgeDocumentStatus.DELETED) {
            // 已软删除文档不再索引；完成 outbox，避免无意义重试。
            return;
        }
        if (!knowledgeRepository.markIndexing(tenantId, document.id())) {
            return;
        }
        UUID documentId = document.id();
        try {
            if (payloadFileId != null && document.fileId() != null && !payloadFileId.equals(document.fileId())) {
                throw new EventProcessingException(
                        WorkerErrorCodes.EVENT_INVALID, false, "knowledge document fileId does not match payload");
            }
            LoadedDocument loaded = loadDocument(tenantId, document);
            if (loaded.chunks().isEmpty()) {
                throw new IndexException(
                        WorkerErrorCodes.INDEX_SOURCE_MISSING, false, "document has no indexable text");
            }
            List<KnowledgeChunkRecord> records =
                    knowledgeRepository.replaceChunks(tenantId, documentId, loaded.chunks());
            ParsedContent content = loaded.content();
            KnowledgeDocumentRecord indexedDocument = document;
            if (content != null) {
                // parse_* 与分块来自同一次解析；先落库再写索引，API 详情与索引文档保持一致。
                knowledgeRepository.updateParseMetadata(
                        tenantId, documentId, content.parseEngine(), content.warnings(), content.summary());
                indexedDocument = document.withParseEngine(content.parseEngine());
            }
            List<float[]> embeddings = records.stream()
                    .map(record -> embeddingModel.embed(record.content()))
                    .toList();
            indexClient.replaceDocument(tenantId, indexedDocument, records, embeddings);
            knowledgeRepository.markIndexed(tenantId, documentId);
            LOG.info(
                    "knowledge.index.completed tenantId={} documentId={} mode=FULL chunks={} dimensions={} force={}"
                            + " parseEngine={}",
                    tenantId.value(),
                    documentId,
                    records.size(),
                    embeddingModel.dimensions(),
                    force,
                    content == null ? null : content.parseEngine());
        } catch (EventProcessingException ex) {
            markFailed(tenantId, documentId, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (ParseException ex) {
            markFailed(tenantId, documentId, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        } catch (IndexException ex) {
            markFailed(tenantId, documentId, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            // 未预期异常保留堆栈；对 outbox 是有界可重试，超过上限后进入 DEAD。
            markFailed(tenantId, documentId, WorkerErrorCodes.UNEXPECTED, "unexpected knowledge index failure");
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "unexpected knowledge index failure", ex);
        }
    }

    /**
     * ACL_ONLY：只刷新索引文档的 ACL/诊断字段，不替换分块、不重新解析、不改文档状态。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     * @param payloadAclVersion payload 携带的 ACL 版本
     */
    private void handleAclOnly(TenantId tenantId, KnowledgeDocumentRecord document, Integer payloadAclVersion) {
        if (document.status() == KnowledgeDocumentStatus.DELETED) {
            LOG.info(
                    "knowledge.index.acl_skipped tenantId={} documentId={} reason=deleted",
                    tenantId.value(),
                    document.id());
            return;
        }
        if (payloadAclVersion != null && payloadAclVersion < document.aclVersion()) {
            // 旧事件晚到：数据库已有更新版本，跳过以免把索引字段写回旧 ACL。
            LOG.info(
                    "knowledge.index.acl_skipped tenantId={} documentId={} payloadAclVersion={} currentAclVersion={}"
                            + " reason=stale",
                    tenantId.value(),
                    document.id(),
                    payloadAclVersion,
                    document.aclVersion());
            return;
        }
        List<KnowledgeChunkRecord> chunks = knowledgeRepository.findChunks(tenantId, document.id());
        if (chunks.isEmpty()) {
            LOG.info(
                    "knowledge.index.acl_skipped tenantId={} documentId={} reason=no_chunks",
                    tenantId.value(),
                    document.id());
            return;
        }
        try {
            List<float[]> embeddings = chunks.stream()
                    .map(chunk -> embeddingModel.embed(chunk.content()))
                    .toList();
            indexClient.replaceDocument(tenantId, document, chunks, embeddings);
            LOG.info(
                    "knowledge.index.acl_completed tenantId={} documentId={} chunks={} aclVersion={}",
                    tenantId.value(),
                    document.id(),
                    chunks.size(),
                    document.aclVersion());
        } catch (IndexException ex) {
            // ACL_ONLY 失败不得把文档标成 FAILED：索引只是最终一致副本，MySQL 授权仍立即生效。
            LOG.warn(
                    "knowledge.index.acl_failed tenantId={} documentId={} errorCode={} retryable={}",
                    tenantId.value(),
                    document.id(),
                    ex.errorCode(),
                    ex.retryable());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        }
    }

    /**
     * DELETE：删除该文档的分块与索引文档；重复执行幂等。
     *
     * @param tenantId 当前租户
     * @param document 文档快照（API 已软删除）
     */
    private void handleDelete(TenantId tenantId, KnowledgeDocumentRecord document) {
        try {
            int chunks = knowledgeRepository.deleteChunks(tenantId, document.id());
            indexClient.deleteDocument(tenantId, document.id());
            LOG.info(
                    "knowledge.index.delete_completed tenantId={} documentId={} chunks={} status={}",
                    tenantId.value(),
                    document.id(),
                    chunks,
                    document.status());
        } catch (IndexException ex) {
            // 文档已软删除即对用户不可见；索引清理失败按可重试处理，不把文档状态改成 FAILED。
            LOG.warn(
                    "knowledge.index.delete_failed tenantId={} documentId={} errorCode={} retryable={}",
                    tenantId.value(),
                    document.id(),
                    ex.errorCode(),
                    ex.retryable());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        }
    }

    /**
     * 从来源文件或已有 MySQL 分块加载待索引文本与解析元数据。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     * @return 分块与解析结果；无源文件时分块来自 MySQL、解析结果为空
     */
    private LoadedDocument loadDocument(TenantId tenantId, KnowledgeDocumentRecord document) {
        if (document.fileId() == null) {
            return new LoadedDocument(
                    knowledgeRepository.findChunks(tenantId, document.id()).stream()
                            .map(KnowledgeIndexService::toParsedChunk)
                            .toList(),
                    null);
        }
        FileRecord file = fileRepository
                .findFile(tenantId, document.fileId())
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "knowledge source file not found"));
        ParsedContent content = contentExtractor.extract(tenantId, file);
        return new LoadedDocument(textChunker.chunk(content), content);
    }

    /**
     * 将已有知识库分块转换为统一切分模型，便于重建（保留 bbox/置信度）。
     *
     * @param record 持久化分块
     * @return 原始分块
     */
    private static ParsedChunk toParsedChunk(KnowledgeChunkRecord record) {
        return new ParsedChunk(
                record.content(),
                record.locatorType(),
                record.pageNo(),
                record.sheetName(),
                record.cellRange(),
                record.paragraphNo(),
                record.tokenCount(),
                record.bbox(),
                record.ocrConfidence());
    }

    /**
     * 读取索引模式；缺省按 FULL 兼容 M02–M04 事件。
     *
     * @param payload 事件 payload
     * @return 索引模式
     */
    private static KnowledgeIndexMode readMode(JsonNode payload) {
        JsonNode node = payload.get("mode");
        if (node == null || node.isNull()) {
            return KnowledgeIndexMode.FULL;
        }
        if (!node.isTextual()) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "payload field mode must be a string");
        }
        try {
            return KnowledgeIndexMode.valueOf(node.asText());
        } catch (IllegalArgumentException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "payload field mode is not a known index mode");
        }
    }

    /**
     * 读取可空正整数；缺失/null 返回 null。
     *
     * @param payload 事件 payload
     * @param field 字段名
     * @return 正整数或 null
     */
    private static Integer readOptionalPositiveInt(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isInt() || node.asInt() < 1) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "payload field " + field + " must be a positive integer");
        }
        return node.asInt();
    }

    /**
     * 读取可空布尔；缺失/null 返回 false。
     *
     * @param payload 事件 payload
     * @param field 字段名
     * @return 布尔值
     */
    private static boolean readOptionalBoolean(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "payload field " + field + " must be a boolean");
        }
        return node.asBoolean();
    }

    /**
     * 写入失败状态并保留稳定错误码。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param errorCode 错误码
     * @param message 脱敏摘要
     */
    private void markFailed(TenantId tenantId, UUID documentId, String errorCode, String message) {
        knowledgeRepository.markFailed(tenantId, documentId, errorCode, message);
    }

    /**
     * 一次加载的结果：待索引分块与来源解析元数据。
     *
     * @param chunks 待索引分块
     * @param content 解析结果；无源文件（SEED）时为 null
     */
    private record LoadedDocument(List<ParsedChunk> chunks, ParsedContent content) {}
}
