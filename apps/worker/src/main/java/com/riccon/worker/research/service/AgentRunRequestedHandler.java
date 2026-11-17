package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.ResearchAgentService;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.chat.repository.UserAccessRepository;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRegistry;
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
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
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

/**
 * 报告任务首次运行：检索 → 工具 → 挂起等待审批。
 *
 * <p>M02 不提前实现报告产物编排：本处理器完成 RETRIEVAL 阶段与 TOOL 阶段的 HITL 挂起，
 * 审批通过后由 resume 事件执行已批准工具并进入生成阶段。审批等待不占用 Worker 租约。
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

    /** 模型解析。 */
    private final ModelRegistry modelRegistry;

    /** 模型调用记录。 */
    private final ModelInvocationService invocationService;

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
     * @param modelRegistry 模型解析
     * @param invocationService 模型调用记录
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
            ModelRegistry modelRegistry,
            ModelInvocationService invocationService,
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
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.invocationService = Objects.requireNonNull(invocationService, "invocationService");
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
        JsonNode payload = payloadParser.parse(event);
        UUID runId = payloadParser.requiredUuid(payload, "runId");
        UUID researchTaskId = payloadParser.requiredUuid(payload, "researchTaskId");
        String stage = payloadParser.requiredText(payload, "stage");
        int attempt = payloadParser.requiredPositiveInt(payload, "attempt");
        if (!runId.equals(event.runId()) || !RunStage.RETRIEVAL.name().equals(stage)) {
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

        runRepository.markStage(tenantId, runId, RunStage.RETRIEVAL.name());
        repository.startStage(tenantId, runId, RunStage.RETRIEVAL.name(), attempt);
        List<?> hits;
        try {
            hits = retriever.retrieveKnowledge(tenantId, task.topic());
        } catch (RuntimeException ex) {
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.RETRIEVAL.name(),
                    attempt,
                    StageStatus.FAILED,
                    null,
                    "RICCON-WORKER-500",
                    "retrieval failed");
            throw new EventProcessingException(WorkerErrorCodes.UNEXPECTED, true, "research retrieval failed", ex);
        }
        repository.finishStage(
                tenantId,
                runId,
                RunStage.RETRIEVAL.name(),
                attempt,
                StageStatus.SUCCEEDED,
                stageJson(new RunStageDetails(
                        null,
                        com.riccon.worker.mcp.domain.Digests.sha256Hex(task.topic()),
                        hits.size(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                null,
                null);

        runRepository.markStage(tenantId, runId, RunStage.TOOL.name());
        repository.startStage(tenantId, runId, RunStage.TOOL.name(), attempt);
        Optional<McpToolHandle> handle = mcpRepository.findFirstAuthorizedTool(tenantId);
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
            throw new EventProcessingException(McpException.DENIED, false, "no authorized mcp tool is available");
        }
        ResolvedModel model;
        try {
            model = modelRegistry.resolve(tenantId);
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
            throw new EventProcessingException(ex.errorCode(), ex.retryable(), ex.getMessage(), ex);
        }
        AgentSession session = null;
        InvocationHandle invocation = null;
        try {
            session = researchAgentService.prepareToolStage(
                    tenantId, runId, run.userId(), task.topic(), handle.get(), model);
            invocation = invocationService.start(
                    tenantId,
                    runId,
                    null,
                    null,
                    model.model().id(),
                    model.model().modelKey(),
                    com.riccon.platform.domain.model.ModelPurpose.REPORT,
                    run.traceId(),
                    null);
            ToolUseBlock pending =
                    awaitApprovalRequest(session.events().toIterable().iterator());
            invocationService.succeeded(invocation, run.traceId(), null, null, null, null);
            invocation = null;
            if (pending == null) {
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.TOOL.name(),
                        attempt,
                        StageStatus.FAILED,
                        null,
                        "RICCON-WORKER-422",
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
                    null);
            repository.updateStageDetails(tenantId, runId, RunStage.TOOL.name(), attempt, stageJson(waitingDetails));
            runRepository.markStage(tenantId, runId, RunStage.APPROVAL.name());
            repository.startStage(tenantId, runId, RunStage.APPROVAL.name(), attempt);
            repository.updateStageDetails(
                    tenantId, runId, RunStage.APPROVAL.name(), attempt, stageJson(waitingDetails));
            runRepository.markWaitingApproval(tenantId, runId);
            LOG.info(
                    "research.run.waiting_approval tenantId={} runId={} approvalId={} toolId={}",
                    tenantId.value(),
                    runId,
                    approval.id(),
                    handle.get().tool().id());
        } catch (EventProcessingException | McpException | AgentException ex) {
            failInvocation(invocation, run.traceId(), errorCode(ex), retryable(ex));
            finishToolFailure(tenantId, runId, attempt, ex);
            throw new EventProcessingException(errorCode(ex), retryable(ex), "research tool stage failed", ex);
        } catch (RuntimeException ex) {
            failInvocation(invocation, run.traceId(), WorkerErrorCodes.UNEXPECTED, true);
            finishToolFailure(tenantId, runId, attempt, ex);
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
     * @param traceId 追踪 ID
     * @param errorCode 错误码
     * @param retryable 是否可重试
     */
    private void failInvocation(InvocationHandle invocation, String traceId, String errorCode, boolean retryable) {
        if (invocation == null) {
            return;
        }
        invocationService.failed(invocation, traceId, null, errorCode, retryable, null);
    }

    /**
     * 等待模型发起需要审批的工具调用。
     *
     * @param iterator 事件迭代器
     * @return 待审批工具调用；模型未请求时为空
     */
    private static ToolUseBlock awaitApprovalRequest(Iterator<AgentEvent> iterator) {
        while (iterator.hasNext()) {
            AgentEvent event = iterator.next();
            if (event instanceof RequireUserConfirmEvent confirm) {
                if (confirm.getToolCalls().size() != 1) {
                    throw new EventProcessingException(
                            WorkerErrorCodes.EVENT_INVALID, false, "M02 requires exactly one approved tool call");
                }
                return confirm.getToolCalls().get(0);
            }
            if (event instanceof AgentEndEvent) {
                return null;
            }
        }
        return null;
    }

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
