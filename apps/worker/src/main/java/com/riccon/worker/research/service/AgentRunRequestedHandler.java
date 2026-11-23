package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.ResearchAgentService;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.chat.repository.UserAccessRepository;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelMetrics;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.domain.RunStageDetails;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.RunDetail;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import com.riccon.worker.run.repository.RunRepository;
import com.riccon.worker.run.service.EventPayloadParser;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 报告任务首次运行：检索 → 工具 → 挂起等待审批。
 *
 * <p>首次事件从 RETRIEVAL 开始；用户从 TOOL 阶段手动重试时 payload 的 stage 为 TOOL，处理器跳过检索，
 * 用新的 attempt 创建新审批。审批通过后由 resume 事件执行已批准工具、生成报告并保存产物。
 * 审批等待不占用 Worker 租约。
 */
@Component
public class AgentRunRequestedHandler implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunRequestedHandler.class);

    /** 报告仓储。 */
    private final ResearchRepository repository;

    /** 运行仓库。 */
    private final RunRepository runRepository;

    /** 检索服务。 */
    private final KnowledgeRetriever retriever;

    /** MCP 仓储。 */
    private final McpRepository mcpRepository;

    /** 模型网关。 */
    private final ModelRouter modelRouter;

    /** 模型调用记录。 */
    private final ModelInvocationService invocationService;

    /** 模型指标。 */
    private final ModelMetrics modelMetrics;

    /** 用户状态。 */
    private final UserAccessRepository userAccessRepository;

    /** 审计。 */
    private final AuditService auditService;

    /** 报告 Agent 构建。 */
    private final ResearchAgentService researchAgentService;

    /** 审批服务。 */
    private final ApprovalService approvalService;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** payload 解析。 */
    private final EventPayloadParser payloadParser;

    /**
     * 创建处理器。
     *
     * @param repository 报告仓储
     * @param runRepository 运行仓库
     * @param retriever 检索服务
     * @param mcpRepository MCP 仓储
     * @param modelRouter 模型网关
     * @param invocationService 模型调用记录
     * @param modelMetrics 模型指标
     * @param userAccessRepository 用户状态
     * @param auditService 审计
     * @param researchAgentService 报告 Agent 服务
     * @param approvalService 审批服务
     * @param objectMapper JSON 序列化
     * @param payloadParser payload 解析
     */
    public AgentRunRequestedHandler(
            ResearchRepository repository,
            RunRepository runRepository,
            KnowledgeRetriever retriever,
            McpRepository mcpRepository,
            ModelRouter modelRouter,
            ModelInvocationService invocationService,
            ModelMetrics modelMetrics,
            UserAccessRepository userAccessRepository,
            AuditService auditService,
            ResearchAgentService researchAgentService,
            ApprovalService approvalService,
            ObjectMapper objectMapper,
            EventPayloadParser payloadParser) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.mcpRepository = Objects.requireNonNull(mcpRepository, "mcpRepository");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.invocationService = Objects.requireNonNull(invocationService, "invocationService");
        this.modelMetrics = Objects.requireNonNull(modelMetrics, "modelMetrics");
        this.userAccessRepository = Objects.requireNonNull(userAccessRepository, "userAccessRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.researchAgentService = Objects.requireNonNull(researchAgentService, "researchAgentService");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser");
    }

    /** 返回事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.AGENT_RUN_REQUESTED;
    }

    /** 返回运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.RESEARCH_REPORT;
    }

    /**
     * 处理首次运行。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        // inflight 指标覆盖整个报告处理（检索/工具/审批挂起），与 Prometheus gauge 语义一致。
        modelMetrics.agentRunStarted();
        try {
            handleRun(event);
        } finally {
            modelMetrics.agentRunFinished();
        }
    }

    /**
     * 处理首次运行的主体逻辑。
     *
     * @param event 已领取事件
     */
    private void handleRun(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID runId = payloadParser.requiredUuid(payload, "runId");
        UUID researchTaskId = payloadParser.requiredUuid(payload, "researchTaskId");
        String stage = payloadParser.requiredText(payload, "stage");
        int attempt = payloadParser.requiredPositiveInt(payload, "attempt");
        RunStage requestedStage;
        try {
            requestedStage = RunStage.valueOf(stage);
        } catch (IllegalArgumentException ex) {
            throw new EventProcessingException(WorkerErrorCodes.EVENT_INVALID, false, "run requested stage is invalid");
        }
        if (!runId.equals(event.runId()) || (requestedStage != RunStage.RETRIEVAL && requestedStage != RunStage.TOOL)) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "run requested payload does not match event");
        }
        TenantId tenantId = TenantContext.require();
        RunDetail run = runRepository
                .findDetail(tenantId, runId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research run not found"));
        if (run.kind() != RunKind.RESEARCH_REPORT) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "run kind is not RESEARCH_REPORT");
        }
        if (!userAccessRepository.isActive(tenantId, run.userId())) {
            auditService.userAccessDenied(tenantId, run.traceId(), null, run.userId());
            throw new EventProcessingException("RICCON-TENANT-403", false, "run owner is disabled");
        }
        ResearchTaskRecord task = repository
                .findTask(tenantId, researchTaskId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research task not found"));
        if (run.researchTaskId() == null || !run.researchTaskId().equals(researchTaskId)) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "run is not bound to research task");
        }

        repository.markTaskRunning(tenantId, researchTaskId, runId, requestedStage.name());
        if (requestedStage == RunStage.RETRIEVAL) {
            runRepository.markStage(tenantId, runId, RunStage.RETRIEVAL.name());
            repository.startStage(tenantId, runId, RunStage.RETRIEVAL.name(), attempt);
            List<RetrievedChunk> hits;
            String querySha256 = Digests.sha256Hex(task.topic());
            try {
                hits = retriever.retrieveKnowledge(tenantId, run.userId(), runId, task.topic());
            } catch (RuntimeException ex) {
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.RETRIEVAL.name(),
                        attempt,
                        StageStatus.FAILED,
                        null,
                        WorkerErrorCodes.UNEXPECTED,
                        "retrieval failed");
                repository.markTaskFailed(
                        tenantId, researchTaskId, runId, WorkerErrorCodes.UNEXPECTED, "retrieval failed");
                throw new EventProcessingException(WorkerErrorCodes.UNEXPECTED, true, "research retrieval failed", ex);
            }
            if (hits.isEmpty()) {
                // 检索无命中不进入工具与生成：保留阶段失败原因，员工调整主题后重新创建任务（M02-AC-07）。
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.RETRIEVAL.name(),
                        attempt,
                        StageStatus.FAILED,
                        retrievalJson(task.topic(), querySha256, List.of()),
                        WorkerErrorCodes.RESEARCH_NO_SOURCES,
                        "no knowledge sources matched the topic");
                repository.markTaskFailed(
                        tenantId,
                        researchTaskId,
                        runId,
                        WorkerErrorCodes.RESEARCH_NO_SOURCES,
                        "no knowledge sources matched the topic");
                throw new EventProcessingException(
                        WorkerErrorCodes.RESEARCH_NO_SOURCES, false, "no knowledge sources matched the topic");
            }
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.RETRIEVAL.name(),
                    attempt,
                    StageStatus.SUCCEEDED,
                    retrievalJson(task.topic(), querySha256, hits),
                    null,
                    null);
        }

        runRepository.markStage(tenantId, runId, RunStage.TOOL.name());
        repository.startStage(tenantId, runId, RunStage.TOOL.name(), attempt);
        Optional<McpToolHandle> handle = task.mcpToolId() == null
                ? mcpRepository.findFirstAuthorizedTool(tenantId)
                : mcpRepository.findAuthorizedTool(tenantId, task.mcpToolId());
        if (handle.isEmpty()) {
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.TOOL.name(),
                    attempt,
                    StageStatus.FAILED,
                    null,
                    McpException.DENIED,
                    "no authorized mcp tool");
            repository.markTaskFailed(
                    tenantId, researchTaskId, runId, McpException.DENIED, "no authorized mcp tool is available");
            throw new EventProcessingException(McpException.DENIED, false, "no authorized mcp tool is available");
        }
        ModelRoute route;
        try {
            route = modelRouter.resolve(
                    tenantId, com.riccon.platform.domain.model.ModelPurpose.REPORT, run.traceId(), null);
        } catch (ModelException ex) {
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.TOOL.name(),
                    attempt,
                    StageStatus.FAILED,
                    null,
                    ex.errorCode(),
                    ex.getMessage());
            repository.markTaskFailed(tenantId, researchTaskId, runId, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        }
        ModelTarget target = route.firstModel()
                .map(routed -> routed.target())
                .orElseThrow(
                        () -> new EventProcessingException(WorkerErrorCodes.UNEXPECTED, false, "model route is empty"));
        AgentSession session = null;
        InvocationHandle invocation = null;
        FailoverChatModel modelClient = null;
        try {
            invocation = invocationService.start(
                    tenantId,
                    runId,
                    null,
                    null,
                    // 成本按用户聚合使用运行发起人快照；不读取其他租户数据。
                    run.userId(),
                    target.model().id(),
                    target.model().modelKey(),
                    com.riccon.platform.domain.model.ModelPurpose.REPORT,
                    run.traceId(),
                    null);
            modelClient = modelRouter.createModel(
                    route,
                    tenantId,
                    invocation,
                    run.traceId(),
                    null,
                    com.riccon.platform.domain.model.ModelPurpose.REPORT);
            session = researchAgentService.prepareToolStage(
                    tenantId, runId, run.userId(), task.topic(), handle.get(), modelClient, target);
            ApprovalRequest approvalRequest =
                    awaitApprovalRequest(session.events().toIterable().iterator());
            ToolUseBlock pending = approvalRequest.pending();
            // 工具阶段以 HITL 审批事件结束，AgentScope 不回传 usage；成本记为 USAGE_UNKNOWN，
            // 只计入调用量，不猜测 token 或金额（ADR-0022 §2.3）。首字只在模型先输出文本时记录；
            // 仅返回 tool_calls 时没有可见首字，保持 null（ADR-0021 §1）。
            invocationService.succeeded(
                    invocation,
                    selectedModelId(modelClient, target),
                    selectedModelKey(modelClient, target),
                    run.traceId(),
                    null,
                    approvalRequest.firstTokenMs(),
                    null,
                    null,
                    modelClient.attemptCount());
            invocation = null;
            if (pending == null) {
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.TOOL.name(),
                        attempt,
                        StageStatus.FAILED,
                        null,
                        WorkerErrorCodes.EVENT_INVALID,
                        "model did not request an approval-required tool");
                repository.markTaskFailed(
                        tenantId,
                        researchTaskId,
                        runId,
                        WorkerErrorCodes.EVENT_INVALID,
                        "model did not request an approval-required tool");
                throw new EventProcessingException(
                        WorkerErrorCodes.EVENT_INVALID, false, "model did not request an approval-required tool");
            }
            ApprovalRecord approval = approvalService.requestApproval(
                    tenantId, runId, researchTaskId, attempt, run.userId(), handle.get(), pending);
            // 审批等待期间先把脱敏预览写入时间线；阶段结束时会用同一摘要再次写入终态详情。
            RunStageDetails waitingDetails = new RunStageDetails(
                    null,
                    null,
                    null,
                    null,
                    handle.get().tool().id(),
                    approval.id(),
                    objectMapper.valueToTree(approvalService.preview(approval)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            repository.updateStageDetails(tenantId, runId, RunStage.TOOL.name(), attempt, stageJson(waitingDetails));
            runRepository.markStage(tenantId, runId, RunStage.APPROVAL.name());
            repository.startStage(tenantId, runId, RunStage.APPROVAL.name(), attempt);
            repository.updateStageDetails(
                    tenantId, runId, RunStage.APPROVAL.name(), attempt, stageJson(waitingDetails));
            runRepository.markWaitingApproval(tenantId, runId);
            repository.markTaskWaitingApproval(tenantId, researchTaskId, runId, RunStage.APPROVAL.name());
            LOG.info(
                    "research.run.waiting_approval tenantId={} runId={} approvalId={} toolId={}",
                    tenantId.value(),
                    runId,
                    approval.id(),
                    handle.get().tool().id());
        } catch (EventProcessingException | McpException | AgentException | ModelException ex) {
            failInvocation(invocation, modelClient, run.traceId(), errorCode(ex), retryable(ex));
            finishToolFailure(tenantId, runId, attempt, ex);
            repository.markTaskFailed(tenantId, researchTaskId, runId, errorCode(ex), truncate(ex.getMessage()));
            throw new EventProcessingException(errorCode(ex), retryable(ex), "research tool stage failed", ex);
        } catch (RuntimeException ex) {
            failInvocation(invocation, modelClient, run.traceId(), WorkerErrorCodes.UNEXPECTED, true);
            finishToolFailure(tenantId, runId, attempt, ex);
            repository.markTaskFailed(
                    tenantId, researchTaskId, runId, WorkerErrorCodes.UNEXPECTED, "research tool stage failed");
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "research tool stage failed unexpectedly", ex);
        } finally {
            if (session != null) {
                close(session);
            }
        }
    }

    /**
     * 记录模型调用失败；未开始的调用忽略。
     *
     * @param invocation 调用句柄
     * @param model 降级客户端，可为空
     * @param traceId 追踪 ID
     * @param errorCode 错误码
     * @param retryable 是否可重试
     */
    private void failInvocation(
            InvocationHandle invocation, FailoverChatModel model, String traceId, String errorCode, boolean retryable) {
        if (invocation == null) {
            return;
        }
        invocationService.failed(
                invocation,
                traceId,
                null,
                errorCode,
                retryable,
                null,
                model != null && model.emitted(),
                model == null ? 0 : model.attemptCount());
    }

    /**
     * 返回最终成功模型 ID；未降级时使用链首模型。
     *
     * @param model 降级客户端
     * @param target 链首模型
     * @return 模型 ID
     */
    private static UUID selectedModelId(FailoverChatModel model, ModelTarget target) {
        ModelTarget selected = model.selectedTarget();
        return selected == null ? target.model().id() : selected.model().id();
    }

    /**
     * 返回最终成功模型 key；未降级时使用链首模型。
     *
     * @param model 降级客户端
     * @param target 链首模型
     * @return 模型 key
     */
    private static String selectedModelKey(FailoverChatModel model, ModelTarget target) {
        ModelTarget selected = model.selectedTarget();
        return selected == null ? target.model().modelKey() : selected.model().modelKey();
    }

    /**
     * 等待模型发起需要审批的工具调用，并顺带记录首字。
     *
     * <p>首字从 {@link ModelCallStartEvent} 到首个非空 {@link TextBlockDeltaEvent} 的事件
     * 时间戳计算；工具调用不是可见首字，模型只返回 tool_calls 时保持 {@code null}
     *（ADR-0021 §1、ADR-0022 §2.1/§2.3）。
     *
     * @param iterator 事件迭代器
     * @return 待审批工具调用与首字延迟；模型未请求时 pending 为空
     */
    private static ApprovalRequest awaitApprovalRequest(Iterator<AgentEvent> iterator) {
        Integer firstTokenMs = null;
        Instant modelCallStartedAt = null;
        while (iterator.hasNext()) {
            AgentEvent event = iterator.next();
            if (firstTokenMs == null) {
                // 首字出现后冻结起点：后续模型调用属于下一轮，不能改写已观测的首字。
                if (event instanceof ModelCallStartEvent) {
                    modelCallStartedAt = ModelFirstToken.eventInstant(event);
                }
                firstTokenMs = ModelFirstToken.firstTokenMs(modelCallStartedAt, event);
            }
            if (event instanceof RequireUserConfirmEvent confirm) {
                if (confirm.getToolCalls().size() != 1) {
                    throw new EventProcessingException(
                            WorkerErrorCodes.EVENT_INVALID, false, "M02 requires exactly one approved tool call");
                }
                return new ApprovalRequest(confirm.getToolCalls().get(0), firstTokenMs);
            }
            if (event instanceof AgentEndEvent) {
                break;
            }
        }
        return new ApprovalRequest(null, firstTokenMs);
    }

    /**
     * 工具阶段等待结果。
     *
     * @param pending 待审批工具调用，可为空
     * @param firstTokenMs 首字延迟，可为空
     */
    private record ApprovalRequest(ToolUseBlock pending, Integer firstTokenMs) {}

    /**
     * 写入工具阶段失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param attempt 尝试号
     * @param error 异常
     */
    private void finishToolFailure(TenantId tenantId, UUID runId, int attempt, RuntimeException error) {
        repository.finishStage(
                tenantId,
                runId,
                RunStage.TOOL.name(),
                attempt,
                StageStatus.FAILED,
                null,
                errorCode(error),
                truncate(error.getMessage()));
    }

    /**
     * 提取稳定错误码。
     *
     * @param error 异常
     * @return 错误码
     */
    private static String errorCode(RuntimeException error) {
        if (error instanceof EventProcessingException eventError) {
            return eventError.errorCode();
        }
        if (error instanceof McpException mcpError) {
            return mcpError.errorCode();
        }
        if (error instanceof AgentException agentError) {
            return agentError.errorCode();
        }
        if (error instanceof ModelException modelError) {
            return modelError.errorCode();
        }
        return WorkerErrorCodes.UNEXPECTED;
    }

    /**
     * 提取可重试标记。
     *
     * @param error 异常
     * @return 是否可重试
     */
    private static boolean retryable(RuntimeException error) {
        if (error instanceof EventProcessingException eventError) {
            return eventError.retryable();
        }
        if (error instanceof McpException mcpError) {
            return mcpError.retryable();
        }
        if (error instanceof AgentException agentError) {
            return agentError.retryable();
        }
        if (error instanceof ModelException modelError) {
            return modelError.retryable();
        }
        return true;
    }

    /**
     * 截断摘要。
     *
     * @param value 摘要
     * @return 最长 500
     */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * 构造检索阶段详情，并附带可回溯的来源清单。
     *
     * <p>{@code sources} 是契约允许的额外键，只包含来源 ID、标题、定位、截断片段与相关度；
     * 不写知识库全文，也不生成 HTML。片段先单行化再截断到
     * {@link RunStageDetails#MAX_SOURCE_SNIPPET_LENGTH}，审批前即可由 API 映射为引用展示。
     * 产物引用清单可据此与检索阶段逐条对齐（M02-AC-07、M02-AC-10）。
     *
     * @param topic 调研主题
     * @param querySha256 查询摘要
     * @param hits 检索命中
     * @return 详情 JSON
     */
    private String retrievalJson(String topic, String querySha256, List<RetrievedChunk> hits) {
        RunStageDetails base = new RunStageDetails(
                truncate(topic), querySha256, hits.size(), null, null, null, null, null, null, null, null, null, null);
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(base.toJson(objectMapper));
            ArrayNode sources = node.putArray("sources");
            for (RetrievedChunk chunk : hits) {
                ObjectNode source = sources.addObject();
                source.put("ordinal", chunk.ordinal());
                source.put("sourceType", chunk.sourceType().name());
                if (chunk.fileId() != null) {
                    source.put("fileId", chunk.fileId().toString());
                }
                if (chunk.knowledgeDocumentId() != null) {
                    source.put(
                            "knowledgeDocumentId", chunk.knowledgeDocumentId().toString());
                }
                if (chunk.chunkId() != null) {
                    source.put("chunkId", chunk.chunkId().toString());
                }
                source.put("title", truncate(chunk.title()));
                source.put("snippet", RunStageDetails.sourceSnippet(chunk.content()));
                source.put("score", chunk.score());
                if (chunk.locatorType() != null) {
                    source.put("locatorType", chunk.locatorType().name());
                }
                if (chunk.pageNo() != null) {
                    source.put("pageNo", chunk.pageNo());
                }
                if (chunk.sheetName() != null) {
                    source.put("sheetName", chunk.sheetName());
                }
                if (chunk.cellRange() != null) {
                    source.put("cellRange", chunk.cellRange());
                }
                if (chunk.paragraphNo() != null) {
                    source.put("paragraphNo", chunk.paragraphNo());
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "failed to serialize retrieval details", ex);
        }
    }

    /**
     * 序列化阶段详情。
     *
     * @param details 白名单摘要
     * @return JSON
     */
    private String stageJson(RunStageDetails details) {
        try {
            return details.toJson(objectMapper);
        } catch (JacksonException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "failed to serialize stage details", ex);
        }
    }

    /**
     * 关闭 Agent；关闭失败只记录日志。
     *
     * @param session Agent 会话
     */
    private static void close(AgentSession session) {
        try {
            session.close();
        } catch (RuntimeException ex) {
            LOG.warn("research.agent.close_failed", ex);
        }
    }
}
