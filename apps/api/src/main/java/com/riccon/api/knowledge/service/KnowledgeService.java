package com.riccon.api.knowledge.service;

import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.file.domain.FileRecord;
import com.riccon.api.file.repository.FileRepository;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.api.knowledge.dto.KnowledgeDocumentCreateRequest;
import com.riccon.api.knowledge.dto.KnowledgeDocumentPageResponse;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.api.knowledge.repository.KnowledgeRepository;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.research.RunKind;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库文档登记与查询。
 *
 * <p>登记复用工作空间文件，写入文档元数据后创建 KNOWLEDGE_INDEX 运行并投递 outbox；解析与索引由 Worker
 * 异步完成。同一来源文件重复登记收敛到同一文档，避免重复投放索引任务。
 */
@Service
public class KnowledgeService {

    /** 标题最大长度，与 V3 列定义一致。 */
    private static final int MAX_TITLE_LENGTH = 300;

    /** 知识库仓储。 */
    private final KnowledgeRepository knowledgeRepository;

    /** 文件仓储，用于校验来源文件归属并复制元数据。 */
    private final FileRepository fileRepository;

    /** 运行创建。 */
    private final RunRepository runRepository;

    /** Outbox 事件。 */
    private final OutboxRepository outboxRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param knowledgeRepository 知识库仓储
     * @param fileRepository 文件仓储
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     * @param clock 可注入时钟
     */
    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            FileRepository fileRepository,
            RunRepository runRepository,
            OutboxRepository outboxRepository,
            Clock clock) {
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 分页列出当前租户知识库文档。
     *
     * @param actor 当前员工
     * @param page 分页参数
     * @param status 可选状态过滤
     * @return 文档分页
     */
    @Transactional(readOnly = true)
    public KnowledgeDocumentPageResponse list(RicconPrincipal actor, PageQuery page, KnowledgeDocumentStatus status) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        long total = knowledgeRepository.count(tenantId, status);
        List<KnowledgeDocumentResponse> items =
                knowledgeRepository.list(tenantId, status, page.size(), page.offset()).stream()
                        .map(KnowledgeDocumentResponse::from)
                        .toList();
        return new KnowledgeDocumentPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 读取当前租户知识库文档。
     *
     * @param actor 当前员工
     * @param documentId 文档 ID
     * @return 文档
     * @throws NotFoundException 不存在、已删除或属于其他租户
     */
    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse get(RicconPrincipal actor, UUID documentId) {
        return KnowledgeDocumentResponse.from(knowledgeRepository
                .findById(TenantId.of(actor.tenantId()), documentId)
                .orElseThrow(() -> new NotFoundException("knowledge document")));
    }

    /**
     * 将可访问文件登记为知识库文档并投递索引事件。
     *
     * @param actor 当前员工
     * @param request 登记请求
     * @param traceId 追踪 ID
     * @return 已受理文档
     * @throws NotFoundException 来源文件不存在或不可见
     */
    @Transactional
    public KnowledgeDocumentResponse create(
            RicconPrincipal actor, KnowledgeDocumentCreateRequest request, String traceId) {
        Validation.requireBody(request);
        String title = Validation.requireText(request.title(), "title", 1, MAX_TITLE_LENGTH);
        if (request.fileId() == null) {
            throw new BadRequestException("fileId is required");
        }
        TenantId tenantId = TenantId.of(actor.tenantId());
        // 锁定来源文件，串行化同一文件的重复登记，避免生成两个文档与两个索引任务。
        FileRecord file = fileRepository
                .lockAccessible(tenantId, request.fileId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("file"));
        Optional<KnowledgeDocumentRecord> existing = knowledgeRepository.findByFileId(tenantId, file.id());
        if (existing.isPresent()) {
            return KnowledgeDocumentResponse.from(existing.get());
        }
        Instant now = clock.instant();
        UUID documentId = UuidV7.generate(now);
        UUID runId = UuidV7.generate(now);
        UUID eventId = UuidV7.generate(now);
        KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(
                documentId,
                title,
                KnowledgeSourceType.UPLOAD,
                file.id(),
                file.sha256(),
                file.mimeType(),
                file.sizeBytes(),
                KnowledgeDocumentStatus.PENDING,
                null,
                null,
                null,
                actor.userId(),
                now);
        knowledgeRepository.insert(tenantId, document);
        runRepository.createPending(tenantId, runId, RunKind.KNOWLEDGE_INDEX, null, actor.userId(), traceId, now);
        OutboxEvent event = new OutboxEvent(
                eventId,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                1,
                "doc:" + documentId + ":index:1",
                knowledgeIndexPayload(documentId, file.id(), 1),
                now);
        outboxRepository.enqueue(tenantId, event);
        return KnowledgeDocumentResponse.from(document);
    }

    /**
     * 构造知识库索引事件 payload；只放文档、来源文件与尝试号。
     *
     * @param documentId 文档 ID
     * @param fileId 来源文件 ID
     * @param attempt 尝试号
     * @return payload
     */
    private static Map<String, Object> knowledgeIndexPayload(UUID documentId, UUID fileId, int attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("fileId", fileId.toString());
        payload.put("attempt", attempt);
        return payload;
    }
}
