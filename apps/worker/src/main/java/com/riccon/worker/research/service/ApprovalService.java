package com.riccon.worker.research.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.mcp.domain.McpToolHandle;
import com.riccon.worker.research.domain.ApprovalRecord;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.research.repository.ResearchRepository.PendingApproval;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * HITL 审批持久化。
 *
 * <p>审批参数必须能在审批通过后精确重放：{@code params_preview} 保存规范化、受限且不含敏感键的
 * 参数 JSON，{@code params_sha256} 绑定其内容。含敏感键或超过 8000 字符的调用直接拒绝，
 * 不允许用脱敏后的参数执行外部工具。审批决定由 API 写入，Worker 只读取。
 */
@Component
public class ApprovalService {

    /** 审批参数长度上限；超过时无法可靠重放，直接拒绝。 */
    static final int MAX_PARAMS_LENGTH = 8000;

    /** 审批阶段名，与 {@code approval.stage} 一致。 */
    public static final String APPROVAL_STAGE = "APPROVAL";

    /** 仓储。 */
    private final ResearchRepository repository;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审批服务。
     *
     * @param repository 报告仓储
     * @param objectMapper JSON 序列化
     */
    public ApprovalService(ResearchRepository repository, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 为挂起的工具调用创建或复用审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param researchTaskId 任务 ID
     * @param attemptNo 阶段尝试号
     * @param requestedBy 发起人
     * @param handle 工具
     * @param pendingCall 模型发起的工具调用
     * @return 审批记录
     */
    public ApprovalRecord requestApproval(
            TenantId tenantId,
            UUID runId,
            UUID researchTaskId,
            int attemptNo,
            UUID requestedBy,
            McpToolHandle handle,
            ToolUseBlock pendingCall) {
        Map<String, Object> arguments = pendingCall.getInput() == null ? Map.of() : pendingCall.getInput();
        String canonical = canonicalJson(arguments);
        String sha256 = Digests.sha256Hex(canonical);
        Optional<ApprovalRecord> existing = repository.findApproval(tenantId, runId, APPROVAL_STAGE, attemptNo);
        if (existing.isPresent()) {
            ApprovalRecord approval = existing.get();
            if (!approval.mcpToolId().equals(handle.tool().id())
                    || !approval.paramsSha256().equals(sha256)) {
                throw new AgentException(
                        AgentException.APPROVAL_CONFLICT, false, "pending approval does not match tool call");
            }
            return approval;
        }
        UUID approvalId = UuidV7.generate();
        repository.insertPendingApproval(
                tenantId,
                new PendingApproval(
                        approvalId,
                        runId,
                        researchTaskId,
                        APPROVAL_STAGE,
                        attemptNo,
                        handle.tool().id(),
                        handle.tool().name(),
                        canonical,
                        sha256,
                        requestedBy));
        return repository
                .findApproval(tenantId, approvalId)
                .orElseThrow(() -> new AgentException(AgentException.RUNTIME, false, "approval was not persisted"));
    }

    /**
     * 读取并校验审批已通过。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param runId 运行 ID
     * @param researchTaskId 任务 ID
     * @return 已批准审批
     */
    public ApprovalRecord requireApproved(TenantId tenantId, UUID approvalId, UUID runId, UUID researchTaskId) {
        ApprovalRecord approval = repository
                .findApproval(tenantId, approvalId)
                .orElseThrow(() ->
                        new AgentException(AgentException.NOT_FOUND, false, "approval does not exist for this tenant"));
        if (!approval.runId().equals(runId) || !approval.researchTaskId().equals(researchTaskId)) {
            throw new AgentException(AgentException.APPROVAL_CONFLICT, false, "approval binding mismatch");
        }
        if (approval.status() != ApprovalStatus.APPROVED) {
            throw new AgentException(AgentException.APPROVAL_CONFLICT, false, "approval is not approved");
        }
        return approval;
    }

    /**
     * 解析审批时的工具参数。
     *
     * @param approval 审批
     * @return 参数映射
     */
    public Map<String, Object> arguments(ApprovalRecord approval) {
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(approval.paramsPreviewJson(), new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException ex) {
            throw new AgentException(AgentException.INVALID, false, "approval parameters are not valid JSON", ex);
        }
    }

    /**
     * 取消运行下所有待审批。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     */
    public void cancelPending(TenantId tenantId, UUID runId) {
        repository.cancelPendingApprovals(tenantId, runId);
    }

    /**
     * 生成可重放的参数 JSON。
     *
     * @param arguments 原始参数
     * @return 规范 JSON
     */
    private String canonicalJson(Map<String, Object> arguments) {
        if (ArgumentDigest.hasSensitiveKey(arguments)) {
            throw new AgentException(
                    AgentException.INVALID, false, "tool arguments contain sensitive fields and cannot be approved");
        }
        try {
            String canonical = objectMapper.writeValueAsString(ArgumentDigest.canonical(arguments));
            if (canonical.length() > MAX_PARAMS_LENGTH) {
                throw new AgentException(AgentException.INVALID, false, "tool arguments exceed approval budget");
            }
            return canonical;
        } catch (JacksonException ex) {
            throw new AgentException(AgentException.INVALID, false, "tool arguments cannot be serialized", ex);
        }
    }
}
