package com.riccon.worker.run.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.audit.service.AuditService;
import com.riccon.worker.chat.service.RunCancellationRegistry;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.run.domain.RunDetail;
import com.riccon.worker.run.repository.RunRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行取消服务。
 *
 * <p>活跃对话流通过进程内注册表中断 Agent；等待审批或由 outbox 执行的任务直接推进数据库状态。
 * 已取消的运行重复取消返回幂等结果；已成功/失败的运行返回冲突。
 */
@Component
public class RunCancellationService {

    /** 取消结果。 */
    public enum Outcome {
        /** 已请求中断活跃流。 */
        REQUESTED,
        /** 等待审批或未执行的任务已直接取消。 */
        CANCELLED,
        /** 已经是取消状态。 */
        ALREADY_CANCELLED,
        /** 已到不可取消的终态。 */
        CONFLICT,
        /** 不存在或不属于当前租户。 */
        NOT_FOUND
    }

    /** 运行仓库。 */
    private final RunRepository runRepository;

    /** 报告仓储。 */
    private final ResearchRepository researchRepository;

    /** 活跃流注册表。 */
    private final RunCancellationRegistry cancellationRegistry;

    /** 审计服务。 */
    private final AuditService auditService;

    /**
     * 创建取消服务。
     *
     * @param runRepository 运行仓库
     * @param researchRepository 报告仓储
     * @param cancellationRegistry 活跃流注册表
     * @param auditService 审计服务
     */
    public RunCancellationService(
            RunRepository runRepository,
            ResearchRepository researchRepository,
            RunCancellationRegistry cancellationRegistry,
            AuditService auditService) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.researchRepository = Objects.requireNonNull(researchRepository, "researchRepository");
        this.cancellationRegistry = Objects.requireNonNull(cancellationRegistry, "cancellationRegistry");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    /**
     * 取消运行。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param reason 取消原因
     * @param traceId 追踪 ID
     * @return 结果
     */
    public Outcome cancel(TenantId tenantId, UUID runId, String reason, String traceId) {
        RunDetail run = runRepository.findDetail(tenantId, runId).orElse(null);
        if (run == null) {
            auditService.tenantAccessDenied(tenantId, traceId, null, "unknown", "RUN_NOT_VISIBLE");
            return Outcome.NOT_FOUND;
        }
        if (run.status() == com.riccon.platform.domain.research.RunStatus.CANCELLED) {
            return Outcome.ALREADY_CANCELLED;
        }
        if (run.terminal()) {
            return Outcome.CONFLICT;
        }
        if (cancellationRegistry.cancel(runId, reason)) {
            return Outcome.REQUESTED;
        }
        researchRepository.cancelPendingApprovals(tenantId, runId);
        runRepository.markCancelled(tenantId, runId);
        return Outcome.CANCELLED;
    }
}
