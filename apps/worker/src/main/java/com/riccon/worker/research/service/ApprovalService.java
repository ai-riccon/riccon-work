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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * HITL 审批持久化与参数重放。
 *
 * <p>审批创建时同时保存两种表示（ADR-0017）：
 *
 * <ul>
 *   <li>{@code params_preview}：白名单脱敏摘要，供审批 UI 与阶段时间线展示；
 *   <li>{@code params_ciphertext} + {@code params_key_id}：规范化完整参数的 AES-256-GCM 密文，
 *       只在审批通过后由 {@link ApprovalParamsCrypto} 解密重放。
 * </ul>
 *
 * <p>含敏感键或超过 8000 UTF-8 字节的调用直接拒绝；解密失败、密文缺失或哈希不符都失败关闭，
 * 不允许用脱敏摘要执行外部工具。审批决定由 API 写入，Worker 只读取。
 */
@Component
public class ApprovalService {

    /** 审批阶段名，与 {@code approval.stage} 一致。 */
    public static final String APPROVAL_STAGE = "APPROVAL";

    /** 脱敏预览的序列化上限；预留 JSON 二进制存储开销，确保 SQL CHECK（≤4096）成立。 */
    public static final int MAX_PREVIEW_BYTES = 3800;

    /** 预览最多保留的顶层键数量。 */
    public static final int MAX_PREVIEW_KEYS = 16;

    /** 预览中单个字符串的最大长度。 */
    public static final int MAX_PREVIEW_STRING = 200;

    /** 仓储。 */
    private final ResearchRepository repository;

    /** 审批参数加密与恢复。 */
    private final ApprovalParamsCrypto paramsCrypto;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审批服务。
     *
     * @param repository 报告仓储
     * @param paramsCrypto 审批参数加密服务
     * @param objectMapper JSON 序列化
     */
    public ApprovalService(
            ResearchRepository repository, ApprovalParamsCrypto paramsCrypto, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.paramsCrypto = Objects.requireNonNull(paramsCrypto, "paramsCrypto must not be null");
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
        Map<String, Object> arguments = argumentsOf(pendingCall);
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
        // AAD 绑定审批行：密文被搬到另一审批或另一租户后无法通过 GCM 认证。
        ApprovalParamsCrypto.Sealed sealed = paramsCrypto.seal(tenantId, approvalId, canonical);
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
                        previewJson(arguments),
                        sealed.ciphertext(),
                        sealed.keyId(),
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
     * 解密并校验审批时的完整工具参数。
     *
     * <p>历史行没有密文、key 已退役、AAD 不匹配、密文被篡改或哈希不符都会失败；绝不退回
     * {@code params_preview} 明文执行。
     *
     * @param tenantId 租户
     * @param approval 审批
     * @return 完整参数映射
     */
    public Map<String, Object> arguments(TenantId tenantId, ApprovalRecord approval) {
        return paramsCrypto.open(
                tenantId, approval.id(), approval.paramsKeyId(), approval.paramsCiphertext(), approval.paramsSha256());
    }

    /**
     * 解析脱敏预览，供阶段详情展示；预览本身已经过白名单与长度裁剪。
     *
     * @param approval 审批
     * @return 预览映射；历史脏数据返回空映射
     */
    public Map<String, Object> preview(ApprovalRecord approval) {
        String json = approval.paramsPreviewJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException ex) {
            // 预览仅用于展示，损坏时不阻塞工具调用；不得回退到完整参数。
            return Map.of();
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
     * 读取工具调用参数。
     *
     * <p>OpenAI 兼容流式模型先输出 fragment，AgentScope 聚合后的 {@link ToolUseBlock} 可能只在
     * {@code content} 中保留参数 JSON，而 {@code input} 为空；审批必须使用完整参数，因此这里对
     * content 做一次显式解析。解析失败直接拒绝，不能把空参数当成审批通过。
     *
     * @param pendingCall 待审批工具调用
     * @return 参数映射
     */
    private Map<String, Object> argumentsOf(ToolUseBlock pendingCall) {
        Map<String, Object> input = pendingCall.getInput();
        if (input != null && !input.isEmpty()) {
            return input;
        }
        String content = pendingCall.getContent();
        if (content != null && !content.isBlank()) {
            try {
                Map<String, Object> parsed =
                        objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
                if (parsed != null) {
                    return parsed;
                }
            } catch (JacksonException ex) {
                throw new AgentException(
                        AgentException.INVALID, false, "tool arguments cannot be parsed from model output", ex);
            }
        }
        return input == null ? Map.of() : input;
    }

    /**
     * 生成可重放的规范参数 JSON。
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
            if (canonical.getBytes(StandardCharsets.UTF_8).length > ApprovalParamsCrypto.MAX_PARAMS_BYTES) {
                throw new AgentException(AgentException.INVALID, false, "tool arguments exceed approval budget");
            }
            return canonical;
        } catch (JacksonException ex) {
            throw new AgentException(AgentException.INVALID, false, "tool arguments cannot be serialized", ex);
        }
    }

    /**
     * 生成脱敏预览 JSON；敏感键与敏感值递归脱敏，键数与字节数受限。
     *
     * @param arguments 原始参数
     * @return 预览 JSON 对象
     */
    private String previewJson(Map<String, Object> arguments) {
        Map<String, Object> preview =
                ArgumentDigest.preview(arguments, MAX_PREVIEW_BYTES, MAX_PREVIEW_KEYS, MAX_PREVIEW_STRING);
        try {
            String json = objectMapper.writeValueAsString(new LinkedHashMap<>(preview));
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PREVIEW_BYTES) {
                // 理论上 preview 已按字节预算裁剪；这里保持 fail-closed，避免写入违反 SQL CHECK 的预览。
                throw new AgentException(AgentException.INVALID, false, "approval preview exceeds the summary budget");
            }
            return json;
        } catch (JacksonException ex) {
            throw new AgentException(AgentException.INVALID, false, "approval preview cannot be serialized", ex);
        }
    }
}
