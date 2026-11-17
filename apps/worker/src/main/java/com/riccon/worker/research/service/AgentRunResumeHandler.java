package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.AgentSession;
import com.riccon.worker.agent.service.ResearchAgentService;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.chat.repository.UserAccessRepository;
import com.riccon.worker.mcp.domain.McpCallResult;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.repository.McpRepository;
import com.riccon.worker.mcp.service.McpGateway;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRegistry;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.domain.ResearchTaskRecord;
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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批通过后的恢复：执行已批准工具 → 生成阶段。
 *
 * <p>不重跑检索；工具参数从审批记录读取并用 SHA-256 与审批绑定。生成结果目前只写阶段摘要，
 * 报告产物保存由后续报告编排任务接管（TASK-0016 范围）。
 */
@Component
public class AgentRunResumeHandler implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunResumeHandler.class);

    /** 报告仓储。 */
    private final ResearchRepository repository;

    /** 运行仓库。 */
    private final RunRepository runRepository;

    /** MCP 仓储。 */
    private final McpRepository mcpRepository;

    /** MCP 网关。 */
    private final McpGateway mcpGateway;

    /** 审批服务。 */
    private final ApprovalService approvalService;

    /** 模型解析。 */
    private final ModelRegistry modelRegistry;

    /** 模型调用记录。 */
    private final ModelInvocationService invocationService;

    /** 用户状态。 */
    private final UserAccessRepository userAccessRepository;

    /** 审计。 */
    private final AuditService auditService;

    /** 报告 Agent。 */
    private final ResearchAgentService researchAgentService;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /** payload 解析。 */
    private final EventPayloadParser payloadParser;

    /**
     * 创建恢复处理器。
     *
     * @param repository 报告仓储
     * @param runRepository 运行仓库
     * @param mcpRepository MCP 仓储
     * @param mcpGateway MCP 网关
     * @param approvalService 审批服务
     * @param modelRegistry 模型解析
     * @param invocationService 模型调用记录
     * @param userAccessRepository 用户状态
     * @param auditService 审计
     * @param researchAgentService 报告 Agent
     * @param objectMapper JSON 序列化
     * @param payloadParser payload 解析
     */
    public AgentRunResumeHandler(
            ResearchRepository repository,
            RunRepository runRepository,
            McpRepository mcpRepository,
            McpGateway mcpGateway,
            ApprovalService approvalService,
            ModelRegistry modelRegistry,
            ModelInvocationService invocationService,
            UserAccessRepository userAccessRepository,
            AuditService auditService,
            ResearchAgentService researchAgentService,
            ObjectMapper objectMapper,
            EventPayloadParser payloadParser) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.mcpRepository = Objects.requireNonNull(mcpRepository, "mcpRepository");
        this.mcpGateway = Objects.requireNonNull(mcpGateway, "mcpGateway");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.invocationService = Objects.requireNonNull(invocationService, "invocationService");
        this.userAccessRepository = Objects.requireNonNull(userAccessRepository, "userAccessRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.researchAgentService = Objects.requireNonNull(researchAgentService, "researchAgentService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser");
    }

    /** 返回事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.AGENT_RUN_RESUME;
    }

    /** 返回运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.RESEARCH_REPORT;
    }

    /**
     * 处理恢复事件。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID runId = payloadParser.requiredUuid(payload, "runId");
        UUID researchTaskId = payloadParser.requiredUuid(payload, "researchTaskId");
        UUID approvalId = payloadParser.requiredUuid(payload, "approvalId");
        String stage = payloadParser.requiredText(payload, "stage");
        int attempt = payloadParser.requiredPositiveInt(payload, "attempt");
        if (!runId.equals(event.runId()) || !RunStage.GENERATION.name().equals(stage)) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "resume payload does not match event");
        }
        TenantId tenantId = TenantContext.require();
        RunDetail run = runRepository
                .findDetail(tenantId, runId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research run not found"));
        if (run.kind() != RunKind.RESEARCH_REPORT
                || run.researchTaskId() == null
                || !run.researchTaskId().equals(researchTaskId)) {
            throw new EventProcessingException(WorkerErrorCodes.EVENT_INVALID, false, "resume run binding is invalid");
        }
        if (!userAccessRepository.isActive(tenantId, run.userId())) {
            auditService.userAccessDenied(tenantId, run.traceId(), null, run.userId());
            throw new EventProcessingException("RICCON-TENANT-403", false, "run owner is disabled");
        }
        ApprovalRecord approval;
        try {
            approval = approvalService.requireApproved(tenantId, approvalId, runId, researchTaskId);
        } catch (AgentException ex) {
            if (AgentException.APPROVAL_CONFLICT.equals(ex.errorCode())
                    && repository
                            .findApproval(tenantId, runId, ApprovalService.APPROVAL_STAGE, attempt)
                            .map(item -> item.status() == ApprovalStatus.REJECTED
                                    || item.status() == ApprovalStatus.EXPIRED
                                    || item.status() == ApprovalStatus.CANCELLED)
                            .orElse(false)) {
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.APPROVAL.name(),
                        attempt,
                        StageStatus.SKIPPED,
                        null,
                        AgentException.APPROVAL_CONFLICT,
                        "approval was not granted");
                runRepository.markCancelled(tenantId, runId);
                return;
            }
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        }
        ResearchTaskRecord task = repository
                .findTask(tenantId, researchTaskId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research task not found"));
        McpToolHandle handle = mcpRepository
                .findAuthorizedTool(tenantId, approval.mcpToolId())
                .orElseThrow(() -> new EventProcessingException(
                        McpException.DENIED, false, "approved mcp tool is no longer authorized"));
        McpCallResult toolResult;
        try {
            toolResult = mcpGateway.invoke(new McpGateway.McpInvocationCommand(
                    tenantId,
                    runId,
                    researchTaskId,
                    approvalId,
                    approval.mcpToolId(),
                    approvalService.arguments(approval),
                    run.traceId(),
                    approvalId.toString()));
        } catch (McpException ex) {
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
        repository.finishStage(
                tenantId,
                runId,
                RunStage.TOOL.name(),
                attempt,
                StageStatus.SUCCEEDED,
                json(Map.of("toolId", handle.tool().id().toString(), "approvalId", approvalId.toString())),
                null,
                null);
        repository.finishStage(
                tenantId,
                runId,
                RunStage.APPROVAL.name(),
                attempt,
                StageStatus.SUCCEEDED,
                json(Map.of("approvalId", approvalId.toString())),
                null,
                null);

        runRepository.markStage(tenantId, runId, RunStage.GENERATION.name());
        repository.startStage(tenantId, runId, RunStage.GENERATION.name(), attempt);
        ResolvedModel model;
        try {
            model = modelRegistry.resolve(tenantId);
        } catch (ModelException ex) {
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.GENERATION.name(),
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
            session = researchAgentService.prepareGenerationStage(
                    tenantId, runId, run.userId(), task.topic(), toolResult.text(), model);
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
            String generated = awaitGeneration(session.events().toIterable().iterator());
            invocationService.succeeded(invocation, run.traceId(), null, null, null, null);
            invocation = null;
            if (generated == null || generated.isBlank()) {
                throw new EventProcessingException(WorkerErrorCodes.UNEXPECTED, true, "generation produced no content");
            }
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.GENERATION.name(),
                    attempt,
                    StageStatus.SUCCEEDED,
                    json(Map.of(
                            "generatedSha256",
                            com.riccon.worker.mcp.domain.Digests.sha256Hex(generated),
                            "generatedChars",
                            generated.length())),
                    null,
                    null);
            LOG.info(
                    "research.run.generation_completed tenantId={} runId={} chars={}",
                    tenantId.value(),
                    runId,
                    generated.length());
        } catch (EventProcessingException ex) {
            failInvocation(invocation, run.traceId(), ex.errorCode(), ex.retryable());
            finishGenerationFailure(tenantId, runId, attempt, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            failInvocation(invocation, run.traceId(), WorkerErrorCodes.UNEXPECTED, true);
            finishGenerationFailure(tenantId, runId, attempt, WorkerErrorCodes.UNEXPECTED, "generation failed");
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "research generation failed unexpectedly", ex);
        } finally {
            if (session != null) {
                close(session);
            }
        }
    }

    /**
     * 记录生成阶段模型调用失败；未开始时忽略。
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
     * 读取生成阶段最终文本。
     *
     * @param iterator 事件迭代器
     * @return 文本；没有结果时为空
     */
    private static String awaitGeneration(Iterator<AgentEvent> iterator) {
        String text = null;
        while (iterator.hasNext()) {
            AgentEvent event = iterator.next();
            if (event instanceof AgentResultEvent result) {
                Msg message = result.getResult();
                text = message == null ? null : message.getTextContent();
            } else if (event instanceof RequireUserConfirmEvent) {
                throw new EventProcessingException(
                        WorkerErrorCodes.EVENT_INVALID, false, "generation must not request tool approval");
            } else if (event instanceof AgentEndEvent) {
                break;
            }
        }
        return text;
    }

    /**
     * 写入生成阶段失败。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param attempt 尝试号
     * @param errorCode 错误码
     * @param message 脱敏摘要
     */
    private void finishGenerationFailure(TenantId tenantId, UUID runId, int attempt, String errorCode, String message) {
        repository.finishStage(
                tenantId,
                runId,
                RunStage.GENERATION.name(),
                attempt,
                StageStatus.FAILED,
                null,
                errorCode,
                message != null && message.length() > 500 ? message.substring(0, 500) : message);
    }

    /**
     * 序列化摘要。
     *
     * @param value 摘要
     * @return JSON
     */
    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(value));
        } catch (JacksonException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "failed to serialize stage details", ex);
        }
    }

    /**
     * 关闭 Agent。
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
