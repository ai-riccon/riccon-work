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
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelMetrics;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.domain.ArtifactException;
import com.riccon.worker.research.domain.ArtifactRecord;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ReportToolSource;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.domain.RunStageDetails;
import com.riccon.worker.research.domain.RunStageRecord;
import com.riccon.worker.research.repository.ArtifactRepository;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.research.service.ReportArtifactService.SavedArtifact;
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
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
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
 * 审批通过后的恢复：执行已批准工具 → 生成报告 → 保存产物。
 *
 * <p>审批拒绝时终止运行与任务，并把审批人、时间与决定写入 APPROVAL 阶段详情；批准后不重跑检索。
 * 工具参数从审批密文读取并用 SHA-256 与审批绑定，不得退回脱敏预览（ADR-0017）。
 * 生成成功后在同一编排内写入 {@code artifact}/{@code artifact_version}/{@code artifact_citation}
 * 并写 {@code artifact.saved} 审计；产物唯一键与正文摘要保证同一事件/重试不产生重复产物。
 *
 * <p>工具调用成功后审批即被消费：后续模型或对象存储失败不再自动重试同一恢复事件，
 * 任务标记失败并由员工从工具阶段重新审批后重试，避免复用旧审批重放外部工具。
 */
@Component
public class AgentRunResumeHandler implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunResumeHandler.class);

    /** 审批拒绝的稳定失败码，与 API 终止任务时保持一致。 */
    static final String APPROVAL_REJECTED_CODE = "APPROVAL_REJECTED";

    /** 审批拒绝的脱敏原因。 */
    static final String APPROVAL_REJECTED_MESSAGE = "tool call approval was rejected";

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

    /** 报告 Agent。 */
    private final ResearchAgentService researchAgentService;

    /** 报告组装。 */
    private final ReportComposer reportComposer;

    /** 产物写入。 */
    private final ReportArtifactWriter artifactWriter;

    /** 产物仓储，用于重复投递短路。 */
    private final ArtifactRepository artifactRepository;

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
     * @param modelRouter 模型网关
     * @param invocationService 模型调用记录
     * @param modelMetrics 模型指标
     * @param userAccessRepository 用户状态
     * @param auditService 审计
     * @param researchAgentService 报告 Agent
     * @param reportComposer 报告组装
     * @param artifactWriter 产物写入
     * @param artifactRepository 产物仓储
     * @param objectMapper JSON 序列化
     * @param payloadParser payload 解析
     */
    public AgentRunResumeHandler(
            ResearchRepository repository,
            RunRepository runRepository,
            McpRepository mcpRepository,
            McpGateway mcpGateway,
            ApprovalService approvalService,
            ModelRouter modelRouter,
            ModelInvocationService invocationService,
            ModelMetrics modelMetrics,
            UserAccessRepository userAccessRepository,
            AuditService auditService,
            ResearchAgentService researchAgentService,
            ReportComposer reportComposer,
            ReportArtifactWriter artifactWriter,
            ArtifactRepository artifactRepository,
            ObjectMapper objectMapper,
            EventPayloadParser payloadParser) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.mcpRepository = Objects.requireNonNull(mcpRepository, "mcpRepository");
        this.mcpGateway = Objects.requireNonNull(mcpGateway, "mcpGateway");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.invocationService = Objects.requireNonNull(invocationService, "invocationService");
        this.modelMetrics = Objects.requireNonNull(modelMetrics, "modelMetrics");
        this.userAccessRepository = Objects.requireNonNull(userAccessRepository, "userAccessRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.researchAgentService = Objects.requireNonNull(researchAgentService, "researchAgentService");
        this.reportComposer = Objects.requireNonNull(reportComposer, "reportComposer");
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
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
        // inflight 指标覆盖整个恢复处理；失败/成功都在 finally 归还。
        modelMetrics.agentRunStarted();
        try {
            handleResume(event);
        } finally {
            modelMetrics.agentRunFinished();
        }
    }

    /**
     * 处理恢复事件的主体逻辑。
     *
     * @param event 已领取事件
     */
    private void handleResume(ClaimedOutboxEvent event) {
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
        ResearchTaskRecord task = repository
                .findTask(tenantId, researchTaskId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research task not found"));

        // 重复投递：本尝试已生成阶段且产物已落库时直接收尾，避免再次执行工具或模型。
        Optional<RunStageRecord> existingGeneration =
                repository.findStage(tenantId, runId, RunStage.GENERATION.name(), attempt);
        Optional<ArtifactRecord> existingArtifact = artifactRepository.findByTask(tenantId, researchTaskId);
        if (existingGeneration.isPresent()
                && existingGeneration.get().status() != StageStatus.FAILED
                && existingArtifact.isPresent()
                && existingArtifact.get().currentVersion() > 0) {
            if (existingGeneration.get().status() == StageStatus.RUNNING) {
                // 崩溃恢复：产物已保存但阶段未收尾，补写终态，保持时间线与产物一致。
                repository.finishStage(
                        tenantId,
                        runId,
                        RunStage.GENERATION.name(),
                        attempt,
                        StageStatus.SUCCEEDED,
                        existingGeneration.get().detailsJson(),
                        null,
                        null);
            }
            repository.markTaskSucceeded(tenantId, researchTaskId, runId);
            LOG.info(
                    "research.run.resume_skipped tenantId={} runId={} reason=ARTIFACT_ALREADY_SAVED",
                    tenantId.value(),
                    runId);
            return;
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
                terminateRejected(tenantId, runId, researchTaskId, attempt);
                return;
            }
            repository.markTaskFailed(tenantId, researchTaskId, runId, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        }
        repository.markTaskRunning(tenantId, researchTaskId, runId, RunStage.GENERATION.name());
        McpToolHandle handle = mcpRepository
                .findAuthorizedTool(tenantId, approval.mcpToolId())
                .orElseThrow(() -> {
                    repository.markTaskFailed(
                            tenantId, researchTaskId, runId, McpException.DENIED, "approved mcp tool is unavailable");
                    return new EventProcessingException(
                            McpException.DENIED, false, "approved mcp tool is no longer authorized");
                });
        Map<String, Object> arguments;
        try {
            arguments = approvalService.arguments(tenantId, approval);
        } catch (AgentException ex) {
            // 密文缺失、key 退役、AAD/哈希不匹配：失败关闭，不退回脱敏预览，也不再重试。
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
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        }
        McpCallResult toolResult;
        try {
            toolResult = mcpGateway.invoke(new McpGateway.McpInvocationCommand(
                    tenantId,
                    runId,
                    researchTaskId,
                    approvalId,
                    approval.mcpToolId(),
                    arguments,
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
            repository.markTaskFailed(tenantId, researchTaskId, runId, ex.errorCode(), ex.getMessage());
            // 审批已被消费（网关已写入调用或拒绝记录），重试必须重新审批，不能自动复用旧审批。
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        }
        RunStageDetails toolDetails = new RunStageDetails(
                null,
                null,
                null,
                toolResult.invocationId(),
                handle.tool().id(),
                approvalId,
                objectMapper.valueToTree(approvalService.preview(approval)),
                "sha256=" + toolResult.resultSha256() + ",bytes=" + toolResult.sizeBytes(),
                null,
                null,
                RunStageDetails.decision(approval),
                approval.decidedBy(),
                RunStageDetails.decidedAt(approval));
        repository.finishStage(
                tenantId,
                runId,
                RunStage.TOOL.name(),
                attempt,
                StageStatus.SUCCEEDED,
                stageJson(toolDetails),
                null,
                null);
        repository.finishStage(
                tenantId,
                runId,
                RunStage.APPROVAL.name(),
                attempt,
                StageStatus.SUCCEEDED,
                stageJson(approvalDetails(handle, approval, approvalId)),
                null,
                null);

        runRepository.markStage(tenantId, runId, RunStage.GENERATION.name());
        repository.startStage(tenantId, runId, RunStage.GENERATION.name(), attempt);
        ModelRoute route;
        try {
            route = modelRouter.resolve(
                    tenantId, com.riccon.platform.domain.model.ModelPurpose.REPORT, run.traceId(), null);
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
            repository.markTaskFailed(tenantId, researchTaskId, runId, ex.errorCode(), ex.getMessage());
            // 工具已经执行，审批不可复用；生成阶段失败只能由员工重新发起工具审批后重试。
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
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
            session = researchAgentService.prepareGenerationStage(
                    tenantId, runId, run.userId(), task.topic(), toolResult.text(), modelClient, target);
            GenerationResult generated =
                    awaitGeneration(session.events().toIterable().iterator());
            // 生成阶段从 AgentResultEvent 取 provider usage，并从细粒度文本事件回填首字延迟；
            // usage 缺失或为负时由调用记录服务归一为 USAGE_UNKNOWN（ADR-0022 §2.1/§2.3）。
            invocationService.succeeded(
                    invocation,
                    selectedModelId(modelClient, target),
                    selectedModelKey(modelClient, target),
                    run.traceId(),
                    null,
                    generated.firstTokenMs(),
                    generated.usage() == null ? null : generated.usage().getInputTokens(),
                    generated.usage() == null ? null : generated.usage().getOutputTokens(),
                    modelClient.attemptCount());
            invocation = null;
            if (generated.text() == null || generated.text().isBlank()) {
                throw new EventProcessingException(
                        WorkerErrorCodes.UNEXPECTED, false, "generation produced no content");
            }
            ReportToolSource toolSource = new ReportToolSource(
                    handle.tool().name(),
                    toolResult.invocationId(),
                    toolResult.resultSha256(),
                    toolResult.sizeBytes(),
                    toolResult.durationMs());
            int hitCount = session.citations().size();
            ReportDocument document = reportComposer.compose(
                    task.topic(),
                    generated.text(),
                    session.citations(),
                    toolSource,
                    selectedModelKey(modelClient, target),
                    com.riccon.worker.mcp.domain.Digests.sha256Hex(task.topic()),
                    hitCount);
            SavedArtifact saved = artifactWriter.write(tenantId, task, runId, run.userId(), document, run.traceId());
            repository.finishStage(
                    tenantId,
                    runId,
                    RunStage.GENERATION.name(),
                    attempt,
                    StageStatus.SUCCEEDED,
                    stageJson(new RunStageDetails(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            com.riccon.worker.mcp.domain.Digests.sha256Hex(generated.text()),
                            generated.text().length(),
                            null,
                            null,
                            null)),
                    null,
                    null);
            repository.markTaskSucceeded(tenantId, researchTaskId, runId);
            LOG.info(
                    "research.run.generation_completed tenantId={} runId={} chars={} artifactId={} version={}",
                    tenantId.value(),
                    runId,
                    generated.text().length(),
                    saved.artifactId(),
                    saved.version().version());
        } catch (EventProcessingException ex) {
            failInvocation(invocation, modelClient, run.traceId(), ex.errorCode(), false);
            finishGenerationFailure(tenantId, researchTaskId, runId, attempt, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        } catch (ArtifactException ex) {
            failInvocation(invocation, modelClient, run.traceId(), ex.errorCode(), false);
            finishGenerationFailure(tenantId, researchTaskId, runId, attempt, ex.errorCode(), ex.getMessage());
            // 工具已执行，自动重试会复用已消费的审批；产物失败需要员工重新审批后重试。
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        } catch (ModelException ex) {
            failInvocation(invocation, modelClient, run.traceId(), ex.errorCode(), false);
            finishGenerationFailure(tenantId, researchTaskId, runId, attempt, ex.errorCode(), ex.getMessage());
            throw new EventProcessingException(ex.errorCode(), false, ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            failInvocation(invocation, modelClient, run.traceId(), WorkerErrorCodes.UNEXPECTED, false);
            finishGenerationFailure(
                    tenantId, researchTaskId, runId, attempt, WorkerErrorCodes.UNEXPECTED, "generation failed");
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, false, "research generation failed unexpectedly", ex);
        } finally {
            if (session != null) {
                close(session);
            }
        }
    }

    /**
     * 审批拒绝或被取消：终止运行与任务，写入决定详情，不生成产物。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param researchTaskId 报告任务 ID
     * @param attempt 尝试号
     */
    private void terminateRejected(TenantId tenantId, UUID runId, UUID researchTaskId, int attempt) {
        ApprovalRecord approval = repository
                .findApproval(tenantId, runId, ApprovalService.APPROVAL_STAGE, attempt)
                .orElse(null);
        RunStageDetails details = approval == null
                ? null
                : new RunStageDetails(
                        null,
                        null,
                        null,
                        null,
                        approval.mcpToolId(),
                        approval.id(),
                        objectMapper.valueToTree(approvalService.preview(approval)),
                        null,
                        null,
                        null,
                        RunStageDetails.decision(approval),
                        approval.decidedBy(),
                        RunStageDetails.decidedAt(approval));
        repository.finishStage(
                tenantId,
                runId,
                RunStage.APPROVAL.name(),
                attempt,
                StageStatus.SKIPPED,
                details == null ? null : stageJson(details),
                AgentException.APPROVAL_CONFLICT,
                "approval was not granted");
        repository.finishStage(
                tenantId,
                runId,
                RunStage.TOOL.name(),
                attempt,
                StageStatus.SKIPPED,
                details == null ? null : stageJson(details),
                AgentException.APPROVAL_CONFLICT,
                "approval was not granted");
        runRepository.markCancelled(tenantId, runId);
        repository.markTaskCancelled(
                tenantId, researchTaskId, runId, APPROVAL_REJECTED_CODE, APPROVAL_REJECTED_MESSAGE);
        LOG.info(
                "research.run.approval_rejected tenantId={} runId={} approvalId={} decision={}",
                tenantId.value(),
                runId,
                approval == null ? null : approval.id(),
                approval == null ? null : approval.status());
    }

    /**
     * 构造审批阶段详情。
     *
     * @param handle 工具
     * @param approval 审批
     * @param approvalId 审批 ID
     * @return 阶段详情
     */
    private RunStageDetails approvalDetails(McpToolHandle handle, ApprovalRecord approval, UUID approvalId) {
        return new RunStageDetails(
                null,
                null,
                null,
                null,
                handle.tool().id(),
                approvalId,
                objectMapper.valueToTree(approvalService.preview(approval)),
                null,
                null,
                null,
                RunStageDetails.decision(approval),
                approval.decidedBy(),
                RunStageDetails.decidedAt(approval));
    }

    /**
     * 记录模型调用失败；未开始时忽略。
     *
     * @param invocation 调用句柄
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
    private static java.util.UUID selectedModelId(FailoverChatModel model, ModelTarget target) {
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
     * 读取生成阶段最终文本与 usage。
     *
     * <p>{@link AgentResultEvent} 同时携带文本与 provider usage；usage 用于成本快照，
     * 缺失时保持 {@code null}，不得本地估算 token（ADR-0022 §2.3）。首字延迟从
     * {@link ModelCallStartEvent} 到首个非空 {@link TextBlockDeltaEvent} 的事件时间戳计算；
     * 工具调用不是可见首字（ADR-0021 §1），没有文本输出时保持 {@code null}。
     *
     * @param iterator 事件迭代器
     * @return 文本、usage 与首字延迟；没有结果时文本为空
     */
    private static GenerationResult awaitGeneration(Iterator<AgentEvent> iterator) {
        String text = null;
        ChatUsage usage = null;
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
            if (event instanceof AgentResultEvent result) {
                Msg message = result.getResult();
                text = message == null ? null : message.getTextContent();
                usage = message == null ? null : message.getChatUsage();
            } else if (event instanceof RequireUserConfirmEvent) {
                throw new EventProcessingException(
                        WorkerErrorCodes.EVENT_INVALID, false, "generation must not request tool approval");
            } else if (event instanceof AgentEndEvent) {
                break;
            }
        }
        return new GenerationResult(text, usage, firstTokenMs);
    }

    /**
     * 生成阶段结果。
     *
     * @param text 生成文本，可为空
     * @param usage provider token 用量，可为空
     * @param firstTokenMs 首字延迟（毫秒），没有可见文本时为空
     */
    private record GenerationResult(String text, ChatUsage usage, Integer firstTokenMs) {}

    /**
     * 写入生成阶段失败并同步任务失败状态。
     *
     * @param tenantId 租户
     * @param researchTaskId 报告任务 ID
     * @param runId 运行 ID
     * @param attempt 尝试号
     * @param errorCode 错误码
     * @param message 脱敏摘要
     */
    private void finishGenerationFailure(
            TenantId tenantId, UUID researchTaskId, UUID runId, int attempt, String errorCode, String message) {
        repository.finishStage(
                tenantId,
                runId,
                RunStage.GENERATION.name(),
                attempt,
                StageStatus.FAILED,
                null,
                errorCode,
                message != null && message.length() > 500 ? message.substring(0, 500) : message);
        repository.markTaskFailed(tenantId, researchTaskId, runId, errorCode, message);
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
