package com.riccon.api.research.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.common.UuidV7;
import com.riccon.api.error.BadRequestException;
import com.riccon.api.error.ConflictException;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.repository.OutboxRepository;
import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.api.research.dto.ApprovalDecisionRequest;
import com.riccon.api.research.dto.ApprovalPageResponse;
import com.riccon.api.research.dto.ApprovalResponse;
import com.riccon.api.research.repository.ApprovalRepository;
import com.riccon.api.research.repository.ResearchTaskRepository;
import com.riccon.api.run.domain.RunSnapshot;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.api.web.Validation;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.ApprovalDecision;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批列表与决定。
 *
 * <p>审批决定是 J2 的唯一人工闸门：只有 PENDING 可决定；重复同一决定幂等返回既有结果，相反决定返回 409。
 * 批准后写 {@code agent.run.resume.v1} 让 Worker 从生成阶段继续；拒绝后写 {@code agent.run.cancel.v1}
 * 并终止任务，不触发生成。审批、运行、任务、outbox 与审计在同一事务内提交，审计失败使决定失败（ADR-0007）。
 */
@Service
public class ApprovalService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);

    /** 审批备注最大长度，与 OpenAPI 和列定义一致。 */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** 拒绝后任务的稳定失败码；前端据此映射文案。 */
    private static final String REJECTED_FAILURE_CODE = "APPROVAL_REJECTED";

    /** 拒绝后任务的脱敏说明；不含审批备注与工具入参。 */
    private static final String REJECTED_FAILURE_MESSAGE = "tool call approval was rejected";

    /** 恢复阶段：审批通过后只生成，不重跑检索与工具。 */
    private static final RunStage RESUME_STAGE = RunStage.GENERATION;

    /** 审批仓储。 */
    private final ApprovalRepository approvalRepository;

    /** 报告任务仓储。 */
    private final ResearchTaskRepository researchTaskRepository;

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** Outbox 仓储。 */
    private final OutboxRepository outboxRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建审批服务。
     *
     * @param approvalRepository 审批仓储
     * @param researchTaskRepository 报告任务仓储
     * @param runRepository 运行仓储
     * @param outboxRepository outbox 仓储
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ApprovalService(
            ApprovalRepository approvalRepository,
            ResearchTaskRepository researchTaskRepository,
            RunRepository runRepository,
            OutboxRepository outboxRepository,
            AuditService auditService,
            Clock clock) {
        this.approvalRepository = Objects.requireNonNull(approvalRepository, "approvalRepository");
        this.researchTaskRepository = Objects.requireNonNull(researchTaskRepository, "researchTaskRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 列出本租户审批。
     *
     * <p>M02 审批列表按租户过滤；员工与租户管理员都可审批（{@code RoleDefinitions}），M03 再引入数据范围。
     *
     * @param actor 当前员工
     * @param status 可选状态过滤
     * @param page 分页参数
     * @return 审批分页
     */
    @Transactional(readOnly = true)
    public ApprovalPageResponse list(RicconPrincipal actor, ApprovalStatus status, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        long total = approvalRepository.count(tenantId, status);
        List<ApprovalResponse> items = approvalRepository.list(tenantId, status, page.size(), page.offset()).stream()
                .map(ApprovalResponse::from)
                .toList();
        return new ApprovalPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }

    /**
     * 批准或拒绝审批。
     *
     * @param actor 当前员工
     * @param approvalId 审批 ID
     * @param request 决定请求
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审批结果
     * @throws NotFoundException 审批不存在或属于其他租户
     * @throws ConflictException 审批已终结、运行状态不允许决定，或与既有决定相反
     */
    @Transactional
    public ApprovalResponse decide(
            RicconPrincipal actor, UUID approvalId, ApprovalDecisionRequest request, String traceId, String requestId) {
        Validation.requireBody(request);
        ApprovalDecision decision = Validation.requireNonNull(request.decision(), "decision");
        String comment = normalizeComment(request.comment());

        TenantId tenantId = TenantId.of(actor.tenantId());
        ApprovalSnapshot approval = approvalRepository
                .findForUpdate(tenantId, approvalId)
                .orElseThrow(() -> new NotFoundException("approval"));

        // 已决定：同一决定幂等返回；相反决定或失效审批一律冲突，避免“先拒后批”。
        if (approval.status() != ApprovalStatus.PENDING) {
            if (alreadyDecidedWith(approval.status(), decision)) {
                return ApprovalResponse.from(approval);
            }
            throw new ConflictException("approval is already decided");
        }

        RunSnapshot run =
                runRepository.findRun(tenantId, approval.runId()).orElseThrow(() -> new NotFoundException("run"));
        if (run.status() != RunStatus.WAITING_APPROVAL) {
            throw new ConflictException("run is not waiting for approval");
        }

        Instant now = clock.instant();
        ApprovalStatus targetStatus =
                decision == ApprovalDecision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        int updated =
                approvalRepository.markDecided(tenantId, approval.id(), targetStatus, actor.userId(), now, comment);
        if (updated == 0) {
            // 行锁下不应出现；若发生说明审批被并发终结，返回冲突而不是写入半套状态。
            throw new ConflictException("approval is already decided");
        }
        if (decision == ApprovalDecision.APPROVE) {
            approve(tenantId, approval, now);
        } else {
            reject(tenantId, approval, now);
        }
        auditService.append(AuditEvent.create(
                now,
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId().toString(),
                AuditActions.APPROVAL_DECIDED,
                AuditTargetType.RESEARCH_TASK,
                approval.researchTaskId().toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                decisionDetails(approval, decision, actor.userId())));
        LOG.info(
                "research.approval.decided tenantId={} approvalId={} taskId={} runId={} decision={}",
                actor.tenantId(),
                approval.id(),
                approval.researchTaskId(),
                approval.runId(),
                decision);
        return ApprovalResponse.from(new ApprovalSnapshot(
                approval.id(),
                approval.researchTaskId(),
                approval.runId(),
                approval.stage(),
                approval.attempt(),
                targetStatus,
                approval.toolName(),
                approval.paramsPreview(),
                approval.paramsSha256(),
                approval.requestedBy(),
                actor.userId(),
                now,
                comment,
                approval.expiresAt(),
                approval.createdAt()));
    }

    /**
     * 批准：恢复运行、推进任务并投递 resume 事件。
     *
     * @param tenantId 当前租户
     * @param approval 审批快照
     * @param now 当前时间
     */
    private void approve(TenantId tenantId, ApprovalSnapshot approval, Instant now) {
        if (runRepository.markResumed(tenantId, approval.runId(), now) == 0) {
            throw new ConflictException("run is not waiting for approval");
        }
        researchTaskRepository.markRunning(tenantId, approval.researchTaskId(), approval.runId(), now);
        outboxRepository.enqueue(
                tenantId,
                new OutboxEvent(
                        UuidV7.generate(now),
                        approval.runId(),
                        EventTypes.AGENT_RUN_RESUME,
                        1,
                        "approval:" + approval.id() + ":resume:v1",
                        resumePayload(approval),
                        now));
    }

    /**
     * 拒绝：终止运行与任务，并投递取消事件；不产生任何产物。
     *
     * @param tenantId 当前租户
     * @param approval 审批快照
     * @param now 当前时间
     */
    private void reject(TenantId tenantId, ApprovalSnapshot approval, Instant now) {
        if (runRepository.markCancelled(tenantId, approval.runId(), now) == 0) {
            throw new ConflictException("run is not waiting for approval");
        }
        researchTaskRepository.markTerminated(
                tenantId,
                approval.researchTaskId(),
                approval.runId(),
                REJECTED_FAILURE_CODE,
                REJECTED_FAILURE_MESSAGE,
                now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", approval.runId().toString());
        payload.put("reason", "USER_CANCELLED");
        outboxRepository.enqueue(
                tenantId,
                new OutboxEvent(
                        UuidV7.generate(now),
                        approval.runId(),
                        EventTypes.AGENT_RUN_CANCEL,
                        1,
                        "approval:" + approval.id() + ":cancel:v1",
                        payload,
                        now));
    }

    /**
     * 判断既有终态是否与本次决定一致。
     *
     * @param status 既有状态
     * @param decision 本次决定
     * @return 一致时返回 {@code true}
     */
    private static boolean alreadyDecidedWith(ApprovalStatus status, ApprovalDecision decision) {
        return (status == ApprovalStatus.APPROVED && decision == ApprovalDecision.APPROVE)
                || (status == ApprovalStatus.REJECTED && decision == ApprovalDecision.REJECT);
    }

    /**
     * 规范化审批备注：空白视为未填写，超长拒绝。
     *
     * @param comment 原始备注
     * @return 备注或 {@code null}
     */
    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new BadRequestException("comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * 构造 resume 事件 payload；字段与 {@code agent-run-resume.v1} schema 一致。
     *
     * @param approval 审批快照
     * @return payload
     */
    private static Map<String, Object> resumePayload(ApprovalSnapshot approval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", approval.runId().toString());
        payload.put("researchTaskId", approval.researchTaskId().toString());
        payload.put("approvalId", approval.id().toString());
        payload.put("stage", RESUME_STAGE.name());
        payload.put("attempt", approval.attempt());
        return payload;
    }

    /**
     * 构造审批审计 details；严格使用 ADR-0007 白名单，不含预览、密文或备注。
     *
     * @param approval 审批快照
     * @param decision 决定
     * @param approverUserId 审批人
     * @return details
     */
    private static Map<String, Object> decisionDetails(
            ApprovalSnapshot approval, ApprovalDecision decision, UUID approverUserId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvalId", approval.id().toString());
        details.put("decision", decision.name());
        details.put("approverUserId", approverUserId.toString());
        details.put("toolName", approval.toolName());
        return details;
    }
}
