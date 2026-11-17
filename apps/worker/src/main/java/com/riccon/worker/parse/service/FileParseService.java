package com.riccon.worker.parse.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.ParseStatus;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.parse.domain.FileParseRecord;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.repository.FileRepository;
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
 * 文件解析事件处理器。
 *
 * <p>领取 {@code riccon.file.parse.requested.v1} 后读取 S3 原文、解析 PDF/DOCX/XLSX/图片，
 * 写 {@code file_parse} 状态与 {@code file_chunk} 定位分块。失败保留稳定错误码；可重试错误
 * 由 outbox 退避后再次投递，重复投递先删后插，不产生重复分块。
 */
@Component
public class FileParseService implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(FileParseService.class);

    /** 文件与解析尝试仓库。 */
    private final FileRepository fileRepository;

    /** S3/解析器抽取入口。 */
    private final FileContentExtractor contentExtractor;

    /** 文本切分器。 */
    private final TextChunker textChunker;

    /** payload 解析器。 */
    private final EventPayloadParser payloadParser;

    /**
     * 创建文件解析处理器。
     *
     * @param fileRepository 文件仓库
     * @param contentExtractor 内容抽取器
     * @param textChunker 文本切分器
     * @param payloadParser payload 解析器
     */
    public FileParseService(
            FileRepository fileRepository,
            FileContentExtractor contentExtractor,
            TextChunker textChunker,
            EventPayloadParser payloadParser) {
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.contentExtractor = Objects.requireNonNull(contentExtractor, "contentExtractor must not be null");
        this.textChunker = Objects.requireNonNull(textChunker, "textChunker must not be null");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser must not be null");
    }

    /** 返回文件解析事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.FILE_PARSE_REQUESTED;
    }

    /** 返回文件解析运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.FILE_PARSE;
    }

    /**
     * 处理文件解析事件。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID fileId = payloadParser.requiredUuid(payload, "fileId");
        UUID parseId = payloadParser.requiredUuid(payload, "parseId");
        int attempt = payloadParser.requiredPositiveInt(payload, "attempt");
        TenantId tenantId = TenantContext.require();

        FileParseRecord parse = fileRepository
                .findParse(tenantId, parseId, fileId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "file parse attempt not found"));
        if (parse.attempt() != attempt) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "file parse attempt does not match payload");
        }
        if (parse.status() == ParseStatus.SUCCEEDED) {
            // 重复投递：解析已成功，直接让轮询器完成 outbox。
            return;
        }
        try {
            FileRecord file = fileRepository
                    .findFile(tenantId, fileId)
                    .orElseThrow(() ->
                            new EventProcessingException(WorkerErrorCodes.TENANT_NOT_FOUND, false, "file not found"));
            if (!fileRepository.markRunning(tenantId, parseId, fileId, DocumentParser.PARSER_VERSION)) {
                return;
            }
            ParsedContent content = contentExtractor.extract(tenantId, file);
            List<ParsedChunk> chunks = textChunker.chunk(content);
            if (chunks.isEmpty()) {
                throw ParseException.unprocessable("file has no extractable text", null);
            }
            fileRepository.replaceChunks(tenantId, fileId, parseId, chunks);
            fileRepository.markSucceeded(
                    tenantId, parseId, fileId, content.pageCount(), content.sheetCount(), chunks.size());
            LOG.info(
                    "file.parse.completed tenantId={} fileId={} parseId={} chunks={}",
                    tenantId.value(),
                    fileId,
                    parseId,
                    chunks.size());
        } catch (EventProcessingException ex) {
            markFailed(tenantId, parseId, fileId, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (ParseException ex) {
            markFailed(tenantId, parseId, fileId, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            // 未预期异常保留堆栈；对 outbox 是有界可重试，避免单次瞬时故障直接丢任务。
            markFailed(tenantId, parseId, fileId, WorkerErrorCodes.UNEXPECTED, "unexpected file parse failure");
            throw new EventProcessingException(WorkerErrorCodes.UNEXPECTED, true, "unexpected file parse failure", ex);
        }
    }

    /**
     * 写入失败状态；保留原因供用户重试与排查。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param fileId 文件 ID
     * @param errorCode 稳定错误码
     * @param message 脱敏摘要
     */
    private void markFailed(TenantId tenantId, UUID parseId, UUID fileId, String errorCode, String message) {
        fileRepository.markFailed(tenantId, parseId, fileId, errorCode, message);
    }
}
