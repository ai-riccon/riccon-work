package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.domain.RunStageDetails;
import com.riccon.worker.research.domain.RunStageRecord;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.RunDetail;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import com.riccon.worker.run.repository.RunRepository;
import com.riccon.worker.run.service.EventPayloadParser;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行取消事件处理器。
 *
 * <p>取消优先于后续阶段：运行进入 CANCELLED，所有 PENDING 审批进入 CANCELLED；已生成内容保留，
 * 不产生新产物。重复取消幂等。
 *
 * <p>审批拒绝时 API 先把运行置为 CANCELLED 再投递取消事件；本处理器在终态运行上仍会执行，
 * 把已决定的审批结论合并进 TOOL/APPROVAL 阶段详情，避免时间线缺少审批人、时间与决定。
 */
@Component
public class AgentRunCancelHandler implements OutboxEventHandler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunCancelHandler.class);

    /** 允许的取消原因。 */
    private static final Set<String> REASONS = Set.of("USER_CANCELLED", "PERMISSION_REVOKED");

    /** 报告仓储。 */
    private final ResearchRepository repository;

    /** 运行仓库。 */
    private final RunRepository runRepository;

    /** payload 解析。 */
    private final EventPayloadParser payloadParser;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建取消处理器。
     *
     * @param repository 报告仓储
     * @param runRepository 运行仓库
     * @param payloadParser payload 解析
     * @param objectMapper JSON 序列化
     */
    public AgentRunCancelHandler(
            ResearchRepository repository,
            RunRepository runRepository,
            EventPayloadParser payloadParser,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 返回事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.AGENT_RUN_CANCEL;
    }

    /** 返回运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.RESEARCH_REPORT;
    }

    /**
     * 取消事件必须在终态运行上执行。
     *
     * <p>API 拒绝审批时先置运行 CANCELLED 再投递取消事件；若轮询器按普通重复投递跳过，
     * TOOL/APPROVAL 阶段会永远停在 RUNNING（TASK-0024 F-005）。
     *
     * @return 固定 {@code true}
     */
    @Override
    public boolean handlesTerminalRun() {
        return true;
    }

    /**
     * 处理取消事件。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID runId = payloadParser.requiredUuid(payload, "runId");
        String reason = payloadParser.requiredText(payload, "reason");
        if (!runId.equals(event.runId()) || !REASONS.contains(reason)) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "cancel payload does not match event");
        }
        TenantId tenantId = TenantContext.require();
        RunDetail run = runRepository
                .findDetail(tenantId, runId)
                .orElseThrow(() -> new EventProcessingException(
                        WorkerErrorCodes.TENANT_NOT_FOUND, false, "research run not found"));
        ApprovalRecord decided = repository
                .findLatestApproval(tenantId, runId)
                .filter(AgentRunCancelHandler::hasDecision)
                .orElse(null);
        repository.cancelPendingApprovals(tenantId, runId);
        runRepository.markCancelled(tenantId, runId);
        // 审批拒绝的取消事件要在阶段收尾前合并决定详情；已终态阶段不重复写，保证重放不覆盖时间。
        if (decided != null) {
            finishDecidedStage(tenantId, runId, RunStage.APPROVAL.name(), decided);
            finishDecidedStage(tenantId, runId, RunStage.TOOL.name(), decided);
        }
        // 未完成阶段进入 SKIPPED：审批拒绝/用户取消后时间线不得停留在 RUNNING。
        int skipped = repository.skipRunningStages(tenantId, runId);
        if (run.researchTaskId() != null) {
            // 取消不是失败：任务进入 CANCELLED，不写失败码；已完成的阶段与引用保持原样。
            repository.markTaskCancelled(tenantId, run.researchTaskId(), runId, null, null);
        }
        LOG.info(
                "research.run.cancelled tenantId={} runId={} reason={} skippedStages={}",
                tenantId.value(),
                runId,
                reason,
                skipped);
    }

    /**
     * 审批是否已有明确结论；只有 APPROVED/REJECTED 才写决定详情。
     *
     * @param approval 审批记录
     * @return 是否已有审批人与时间
     */
    private static boolean hasDecision(ApprovalRecord approval) {
        return (approval.status() == ApprovalStatus.APPROVED || approval.status() == ApprovalStatus.REJECTED)
                && approval.decidedBy() != null
                && approval.decidedAt() != null;
    }

    /**
     * 把审批决定合并进指定阶段详情并标记为 SKIPPED。
     *
     * <p>只处理仍为 RUNNING 的阶段；重复取消时阶段已终态，直接返回，保留首次写入的详情与时间。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param stage 阶段名
     * @param approval 已决定的审批
     */
    private void finishDecidedStage(TenantId tenantId, UUID runId, String stage, ApprovalRecord approval) {
        RunStageRecord existing = repository
                .findStage(tenantId, runId, stage, approval.attemptNo())
                .orElse(null);
        if (existing == null || existing.status() != StageStatus.RUNNING) {
            return;
        }
        String details;
        try {
            details = RunStageDetails.withApprovalDecision(objectMapper, existing.detailsJson(), approval);
        } catch (JacksonException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.UNEXPECTED, true, "failed to serialize approval stage details", ex);
        }
        repository.skipStage(tenantId, runId, stage, approval.attemptNo(), details);
    }
}
