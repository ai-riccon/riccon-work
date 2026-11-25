package com.riccon.worker.workflow.tool.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.mcp.domain.McpCallResult;
import com.riccon.worker.mcp.domain.McpException;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.mcp.service.McpCallExecutor;
import com.riccon.worker.workflow.config.WorkflowRuntimeProperties;
import com.riccon.worker.workflow.engine.domain.WorkflowEngineLimits;
import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowNodeTimeoutException;
import com.riccon.worker.workflow.tool.domain.WorkflowToolApprovalSnapshot;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationInsert;
import com.riccon.worker.workflow.tool.domain.WorkflowToolInvocationStatus;
import com.riccon.worker.workflow.tool.repository.WorkflowToolRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP_TOOL 节点执行器（ADR-0024 §4.4/§4.7，T07b）。
 *
 * <p>执行链条：
 *
 * <ol>
 *   <li>共享 {@link WorkflowToolApprovalVerifier} 校验审批状态/禁自批/目标绑定/plan 哈希/重试重审批，
 *       并解密参数；
 *   <li>同一审批最多执行一次：已有 STARTED 调用按 {@code WF_SIDE_EFFECT_UNKNOWN} 失败关闭；已有终态
 *       调用按 {@code WF_APPROVAL_REQUIRED} 要求重新审批；
 *   <li>用共享 {@link McpCallExecutor} 的 {@code APPROVAL_BOUND_WRITE} 策略复查工具状态、租户授权与
 *       平台白名单；J2 的 {@code READ_ONLY} 行为不变；
 *   <li>插入 {@code workflow_tool_invocation} STARTED（唯一键兜底并发），绑定节点尝试后发起调用；
 *   <li>成功/失败写终态与 {@code mcp.tool.invoked} 审计（带 workflowRunId/workflowNodeId）。
 * </ol>
 *
 * <p><b>未知终态</b>：崩溃遗留的 STARTED、并发重复插入或调用异常一律不自动重放；WRITE 工具重试
 * 必须重新走审批节点。L3 失败码使用 {@code WF_SIDE_EFFECT_UNKNOWN}，不把“未知”伪装成可重试失败。
 *
 * <p>单个工具结果受 {@code maxResultBytes} 与节点输出 256 KiB 双重约束；超限不静默截断。
 */
@Component
public class WorkflowToolNodeExecutor implements WorkflowNodeExecutor {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowToolNodeExecutor.class);

    /** 未知副作用提示。 */
    static final String UNKNOWN_SIDE_EFFECT_MESSAGE = "工具调用外部副作用状态未知，需重新审批后重试";

    /** 工具未授权提示。 */
    static final String NOT_AUTHORIZED_MESSAGE = "工具未授权、已停用或参数不满足安全要求";

    /** 结果超限提示。 */
    static final String RESULT_LIMIT_MESSAGE = "工具结果超过大小上限，未保存";

    /** 工具调用仓储。 */
    private final WorkflowToolRepository repository;

    /** 审批/plan 校验。 */
    private final WorkflowToolApprovalVerifier verifier;

    /** 共享 MCP 执行器。 */
    private final McpCallExecutor mcpCallExecutor;

    /** 工具调用审计。 */
    private final WorkflowToolAuditor auditor;

    /** 运行配置（MCP 节点超时）。 */
    private final WorkflowRuntimeProperties runtimeProperties;

    /** JSON 序列化（节点输出大小检查）。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建工具节点执行器。
     *
     * @param repository 工具调用仓储
     * @param verifier 审批/plan 校验
     * @param mcpCallExecutor 共享 MCP 执行器
     * @param auditor 工具调用审计
     * @param runtimeProperties 运行配置
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public WorkflowToolNodeExecutor(
            WorkflowToolRepository repository,
            WorkflowToolApprovalVerifier verifier,
            McpCallExecutor mcpCallExecutor,
            WorkflowToolAuditor auditor,
            WorkflowRuntimeProperties runtimeProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.mcpCallExecutor = Objects.requireNonNull(mcpCallExecutor, "mcpCallExecutor must not be null");
        this.auditor = Objects.requireNonNull(auditor, "auditor must not be null");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowNodeType nodeType() {
        return WorkflowNodeType.MCP_TOOL;
    }

    /**
     * 执行工具节点。
     *
     * @param execution 节点执行上下文
     * @return 成功结果（含状态、结果哈希与大小）
     * @throws WorkflowNodeFailureException 审批/授权/plan/调用失败
     * @throws WorkflowNodeAbortedException 取消或租约丢失
     * @throws WorkflowNodeTimeoutException MCP 调用超过节点预算
     */
    @Override
    public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
        execution.guard().requireActive();
        execution.deadline().requireRemaining();

        TenantId tenantId = execution.tenantId();
        UUID runId = execution.runId();
        String nodeKey = execution.node().id();
        WorkflowToolApprovalVerifier.VerifiedToolApproval verified;
        try {
            verified = verifier.verify(execution);
        } catch (WorkflowNodeFailureException failure) {
            // 审批/plan/参数校验失败发生在任何外部调用之前；仍写 DENIED 审计，便于安全检索。
            auditVerificationDenied(execution, failure);
            throw failure;
        }
        WorkflowToolApprovalSnapshot approval = verified.approval();
        UUID approvalId = approval.id();
        int attempt = attempt(execution);

        // 一个审批最多执行一次：未知终态优先失败关闭，终态视为审批已消费。
        Optional<WorkflowToolInvocationStatus> existing = repository.findInvocationStatus(tenantId, approvalId);
        if (existing.isPresent()) {
            WorkflowToolInvocationStatus status = existing.get();
            if (status == WorkflowToolInvocationStatus.STARTED) {
                deny(execution, verified, approvalId, "INVOCATION_STARTED");
                throw new WorkflowNodeFailureException(
                        WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN, UNKNOWN_SIDE_EFFECT_MESSAGE);
            }
            deny(execution, verified, approvalId, "APPROVAL_CONSUMED");
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_APPROVAL_REQUIRED, "该审批已执行过工具调用，需重新审批");
        }

        Optional<McpToolHandle> maybeHandle = mcpCallExecutor.findAuthorizedTool(tenantId, verified.toolId());
        if (maybeHandle.isEmpty()) {
            deny(execution, verified, approvalId, "TOOL_NOT_AUTHORIZED");
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, NOT_AUTHORIZED_MESSAGE);
        }
        McpToolHandle handle = maybeHandle.get();
        String policyFailure =
                mcpCallExecutor.validatePolicy(handle, McpCallExecutor.Policy.APPROVAL_BOUND_WRITE, approvalId);
        if (policyFailure != null) {
            deny(execution, verified, approvalId, "POLICY_DENIED");
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, NOT_AUTHORIZED_MESSAGE);
        }

        UUID invocationId = UuidV7.generate();
        Instant startedAt = clock.instant();
        try {
            repository.insertStarted(
                    tenantId,
                    new WorkflowToolInvocationInsert(
                            invocationId,
                            runId,
                            approvalId,
                            nodeKey,
                            attempt,
                            verified.toolId(),
                            handle.tool().name(),
                            verified.paramsSha256(),
                            approval.paramsPreview(),
                            startedAt));
        } catch (DuplicateKeyException exception) {
            // 唯一键冲突说明另一执行路径已占用同一审批；不发起第二次外部调用。
            deny(execution, verified, approvalId, "DUPLICATE_INVOCATION");
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN, UNKNOWN_SIDE_EFFECT_MESSAGE, exception);
        }
        if (repository.bindNodeInvocation(tenantId, runId, nodeKey, attempt, invocationId, clock.instant()) == 0) {
            // 节点已不在 RUNNING（取消/重领）：不发起外部调用，调用记录关闭为失败。
            long duration = Math.max(0L, clock.millis() - startedAt.toEpochMilli());
            repository.markTerminated(
                    tenantId,
                    invocationId,
                    WorkflowToolInvocationStatus.FAILED,
                    WorkflowErrorCode.WF_CANCELLED.name(),
                    duration,
                    clock.instant());
            throw new WorkflowNodeAbortedException(execution.guard().lost());
        }

        McpCallResult result;
        long durationMs;
        try {
            long startedMillis = clock.millis();
            result = mcpCallExecutor.call(handle, verified.arguments(), callTimeout(execution));
            durationMs = Math.max(0L, clock.millis() - startedMillis);
        } catch (McpException exception) {
            WorkflowErrorCode errorCode = errorCodeOf(exception);
            long duration = Math.max(0L, clock.millis() - startedAt.toEpochMilli());
            repository.markTerminated(
                    tenantId,
                    invocationId,
                    WorkflowToolInvocationStatus.FAILED,
                    errorCode.name(),
                    duration,
                    clock.instant());
            auditor.invoked(
                    tenantId,
                    runId,
                    nodeKey,
                    handle.tool().id(),
                    handle.tool().name(),
                    verified.paramsSha256(),
                    null,
                    duration,
                    approvalId,
                    AuditOutcome.FAILURE);
            throw new WorkflowNodeFailureException(errorCode, messageOf(errorCode), exception);
        } catch (RuntimeException exception) {
            // 未预期异常：调用可能已经发出，WRITE 工具按未知副作用失败关闭。
            long duration = Math.max(0L, clock.millis() - startedAt.toEpochMilli());
            repository.markTerminated(
                    tenantId,
                    invocationId,
                    WorkflowToolInvocationStatus.FAILED,
                    WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN.name(),
                    duration,
                    clock.instant());
            auditor.invoked(
                    tenantId,
                    runId,
                    nodeKey,
                    handle.tool().id(),
                    handle.tool().name(),
                    verified.paramsSha256(),
                    null,
                    duration,
                    approvalId,
                    AuditOutcome.FAILURE);
            throw new WorkflowNodeFailureException(
                    WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN, UNKNOWN_SIDE_EFFECT_MESSAGE, exception);
        }

        Map<String, Object> output = output(handle, result);
        long outputBytes = serializedBytes(output);
        if (repository.markSucceeded(
                        tenantId,
                        invocationId,
                        result.resultSha256(),
                        result.sizeBytes(),
                        resultPreview(result),
                        durationMs,
                        clock.instant())
                == 0) {
            // 不可达：STARTED 只由本方法写入且只有本方法终结；仍失败关闭，避免“调用成功但无记录”。
            auditor.invoked(
                    tenantId,
                    runId,
                    nodeKey,
                    handle.tool().id(),
                    handle.tool().name(),
                    verified.paramsSha256(),
                    result.resultSha256(),
                    durationMs,
                    approvalId,
                    AuditOutcome.FAILURE);
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具调用记录状态不一致");
        }
        if (outputBytes > WorkflowEngineLimits.NODE_OUTPUT_MAX_BYTES) {
            // 外部调用已成功，但结果无法安全落节点输出；不静默截断，节点失败并要求重新审批。
            auditor.invoked(
                    tenantId,
                    runId,
                    nodeKey,
                    handle.tool().id(),
                    handle.tool().name(),
                    verified.paramsSha256(),
                    result.resultSha256(),
                    durationMs,
                    approvalId,
                    AuditOutcome.FAILURE);
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_RESULT_LIMIT, RESULT_LIMIT_MESSAGE);
        }
        auditor.invoked(
                tenantId,
                runId,
                nodeKey,
                handle.tool().id(),
                handle.tool().name(),
                verified.paramsSha256(),
                result.resultSha256(),
                durationMs,
                approvalId,
                AuditOutcome.SUCCESS);
        LOG.info(
                "workflow.tool.completed tenantId={} runId={} nodeKey={} toolId={} durationMs={} resultBytes={}",
                tenantId.value(),
                runId,
                nodeKey,
                handle.tool().id(),
                durationMs,
                result.sizeBytes());
        return WorkflowNodeResult.of("调用工具：" + handle.tool().name(), summary(result), output, List.of());
    }

    /**
     * 记录审批/plan/参数校验失败的拒绝审计（无调用记录）。
     *
     * <p>工具 ID 来自节点配置快照；工具名与审批 ID 在验证失败时尚不可信，不写入。审计 details 只有
     * 运行/节点 ID 与耗时，满足 T01 白名单。
     *
     * @param execution 执行上下文
     * @param failure 校验失败
     */
    private void auditVerificationDenied(WorkflowNodeExecution execution, WorkflowNodeFailureException failure) {
        UUID toolId = execution.node().config().toolId();
        if (toolId == null) {
            return;
        }
        auditor.invoked(
                execution.tenantId(),
                execution.runId(),
                execution.node().id(),
                toolId,
                null,
                null,
                null,
                0L,
                null,
                AuditOutcome.DENIED);
        LOG.warn(
                "workflow.tool.denied tenantId={} runId={} nodeKey={} toolId={} reason={}",
                execution.tenantId().value(),
                execution.runId(),
                execution.node().id(),
                toolId,
                failure.errorCode().name());
    }

    /**
     * 记录拒绝审计（无调用记录）。
     *
     * @param execution 执行上下文
     * @param verified 已验证审批
     * @param approvalId 审批 ID
     * @param reason 脱敏原因（只进服务端日志）
     */
    private void deny(
            WorkflowNodeExecution execution,
            WorkflowToolApprovalVerifier.VerifiedToolApproval verified,
            UUID approvalId,
            String reason) {
        auditor.invoked(
                execution.tenantId(),
                execution.runId(),
                execution.node().id(),
                verified.toolId(),
                verified.toolName(),
                verified.paramsSha256(),
                null,
                0L,
                approvalId,
                AuditOutcome.DENIED);
        LOG.warn(
                "workflow.tool.denied tenantId={} runId={} nodeKey={} toolId={} reason={}",
                execution.tenantId().value(),
                execution.runId(),
                execution.node().id(),
                verified.toolId(),
                reason);
    }

    /**
     * 读取当前 RUNNING 节点尝试号。
     *
     * @param execution 执行上下文
     * @return 尝试号
     */
    private int attempt(WorkflowNodeExecution execution) {
        return repository
                .findAttempt(execution.tenantId(), execution.nodeRunId())
                .orElseThrow(() -> new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具节点尝试不存在"));
    }

    /**
     * 计算本次 MCP 调用超时：工作流 MCP 预算与节点剩余截止时间的较小值。
     *
     * @param execution 执行上下文
     * @return 超时
     * @throws WorkflowNodeTimeoutException 剩余预算已耗尽
     */
    private Duration callTimeout(WorkflowNodeExecution execution) {
        Duration budget = Duration.ofSeconds(runtimeProperties.mcpTimeoutSeconds());
        Duration remaining = execution.deadline().remaining();
        Duration timeout = remaining.compareTo(budget) < 0 ? remaining : budget;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new WorkflowNodeTimeoutException("工具调用超过节点超时预算");
        }
        return timeout;
    }

    /**
     * 构造节点输出；工具文本只作为下游不可信输入。
     *
     * @param handle 工具
     * @param result 调用结果
     * @return 输出
     */
    private static Map<String, Object> output(McpToolHandle handle, McpCallResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", WorkflowToolInvocationStatus.SUCCEEDED.name());
        output.put("toolName", handle.tool().name());
        output.put("resultSha256", result.resultSha256());
        output.put("resultSizeBytes", result.sizeBytes());
        output.put("text", result.text());
        return output;
    }

    /**
     * 构造结果摘要；截断到摘要列宽。
     *
     * @param result 调用结果
     * @return 摘要
     */
    private static String summary(McpCallResult result) {
        if (result.text() == null || result.text().isBlank()) {
            return "工具调用成功，返回 0 字节";
        }
        int max = WorkflowEngineLimits.SUMMARY_MAX_LENGTH;
        if (result.text().length() <= max) {
            return result.text();
        }
        return result.text().substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * 构造脱敏结果预览（不存结果原文）。
     *
     * @param result 调用结果
     * @return 预览
     */
    private static String resultPreview(McpCallResult result) {
        return "sha256=" + result.resultSha256() + ",bytes=" + result.sizeBytes();
    }

    /**
     * 计算节点输出序列化后的 UTF-8 字节数。
     *
     * @param output 输出
     * @return 字节数
     */
    private long serializedBytes(Map<String, Object> output) {
        try {
            return objectMapper.writeValueAsString(output).getBytes(StandardCharsets.UTF_8).length;
        } catch (JacksonException exception) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具结果无法序列化", exception);
        }
    }

    /**
     * 把共享执行器的 MCP 错误映射为工作流稳定错误码。
     *
     * <p>只区分结果超限、明确授权拒绝与其他失败；其他失败（端点、协议、超时）都可能已在外部产生
     * 副作用，统一 {@code WF_SIDE_EFFECT_UNKNOWN}，不声明可安全重试。
     *
     * @param exception MCP 错误
     * @return 稳定错误码
     */
    static WorkflowErrorCode errorCodeOf(McpException exception) {
        if (exception instanceof McpCallExecutor.ResultLimitException) {
            return WorkflowErrorCode.WF_TOOL_RESULT_LIMIT;
        }
        if (McpException.DENIED.equals(exception.errorCode())) {
            return WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED;
        }
        return WorkflowErrorCode.WF_SIDE_EFFECT_UNKNOWN;
    }

    /**
     * 读取稳定错误码对应的中文提示。
     *
     * @param errorCode 错误码
     * @return 提示
     */
    private static String messageOf(WorkflowErrorCode errorCode) {
        return switch (errorCode) {
            case WF_TOOL_RESULT_LIMIT -> RESULT_LIMIT_MESSAGE;
            case WF_TOOL_NOT_AUTHORIZED -> NOT_AUTHORIZED_MESSAGE;
            default -> UNKNOWN_SIDE_EFFECT_MESSAGE;
        };
    }
}
