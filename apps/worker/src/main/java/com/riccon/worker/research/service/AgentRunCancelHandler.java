package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
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
import tools.jackson.databind.JsonNode;

/**
 * 运行取消事件处理器。
 *
 * <p>取消优先于后续阶段：运行进入 CANCELLED，所有 PENDING 审批进入 CANCELLED；已生成内容保留，
 * 不产生新产物。重复取消幂等。
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

    /**
     * 创建取消处理器。
     *
     * @param repository 报告仓储
     * @param runRepository 运行仓库
     * @param payloadParser payload 解析
     */
    public AgentRunCancelHandler(
            ResearchRepository repository, RunRepository runRepository, EventPayloadParser payloadParser) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser");
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
        repository.cancelPendingApprovals(tenantId, runId);
        runRepository.markCancelled(tenantId, runId);
        if (run.researchTaskId() != null) {
            // 取消不是失败：任务进入 CANCELLED，不写失败码；已完成的阶段与引用保持原样。
            repository.markTaskCancelled(tenantId, run.researchTaskId(), runId, null, null);
        }
        LOG.info("research.run.cancelled tenantId={} runId={} reason={}", tenantId.value(), runId, reason);
    }
}
