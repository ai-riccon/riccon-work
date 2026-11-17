package com.riccon.worker.index.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
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
 * <p>领取 {@code riccon.knowledge.index.requested.v1} 后，从来源文件或已有分块重建权威
 * {@code knowledge_chunk}，生成 embedding 并写入租户 OpenSearch 索引。整个流程幂等：
 * 重复投递先删后插 MySQL 分块、按 document_id 清理索引文档，不产生重复检索结果。
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
        TenantId tenantId = TenantContext.require();

        KnowledgeDocumentRecord document = knowledgeRepository
                .findDocument(tenantId, documentId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "knowledge document not found"));
        if (document.status() == KnowledgeDocumentStatus.DELETED) {
            // 已软删除文档不再索引；完成 outbox，避免无意义重试。
            return;
        }
        if (!knowledgeRepository.markIndexing(tenantId, documentId)) {
            return;
        }
        try {
            if (payloadFileId != null && document.fileId() != null && !payloadFileId.equals(document.fileId())) {
                throw new EventProcessingException(
                        WorkerErrorCodes.EVENT_INVALID, false, "knowledge document fileId does not match payload");
            }
            List<ParsedChunk> chunks = loadChunks(tenantId, document);
            if (chunks.isEmpty()) {
                throw new IndexException(
                        WorkerErrorCodes.INDEX_SOURCE_MISSING, false, "document has no indexable text");
            }
            List<KnowledgeChunkRecord> records = knowledgeRepository.replaceChunks(tenantId, documentId, chunks);
            List<float[]> embeddings = records.stream()
                    .map(record -> embeddingModel.embed(record.content()))
                    .toList();
            indexClient.replaceDocument(tenantId, documentId, document.title(), records, embeddings);
            knowledgeRepository.markIndexed(tenantId, documentId);
            LOG.info(
                    "knowledge.index.completed tenantId={} documentId={} chunks={} dimensions={}",
                    tenantId.value(),
                    documentId,
                    records.size(),
                    embeddingModel.dimensions());
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
     * 从来源文件或已有 MySQL 分块加载待索引文本。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     * @return 原始分块
     */
    private List<ParsedChunk> loadChunks(TenantId tenantId, KnowledgeDocumentRecord document) {
        if (document.fileId() == null) {
            return knowledgeRepository.findChunks(tenantId, document.id()).stream()
                    .map(KnowledgeIndexService::toParsedChunk)
                    .toList();
        }
        FileRecord file = fileRepository
                .findFile(tenantId, document.fileId())
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "knowledge source file not found"));
        ParsedContent content = contentExtractor.extract(tenantId, file);
        return textChunker.chunk(content);
    }

    /**
     * 将已有知识库分块转换为统一切分模型，便于重建。
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
                record.tokenCount());
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
}
