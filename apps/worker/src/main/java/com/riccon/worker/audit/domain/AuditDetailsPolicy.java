package com.riccon.worker.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Worker 审计 details 白名单。
 *
 * <p>ADR-0007 第 5 节要求 details 只包含白名单字段。Worker 写模型、MCP 与产物保存动作；
 * 提示词、回答、文件原文、工具请求/响应原文、凭据与完整 PII 一律禁止。
 *
 * <p>M04（ADR-0021 §7.4）：Worker 新增降级与凭据自动失效动作；{@code model.invocation.failed}
 * 增加 {@code emitted} 以区分首字后失败。数组与 nullable 规则与 API 侧
 * {@code com.riccon.api.audit.domain.AuditDetailsPolicy} 保持同一语义，避免写入端与读取端漂移。
 *
 * <p>M06（ADR-0024 §8.1）：Worker 写运行/审批生命周期审计（{@code workflow.run.started/completed/
 * failed/cancelled}、{@code workflow.approval.requested}），并给 {@code mcp.tool.invoked} 追加可选
 * {@code workflowRunId}/{@code workflowNodeId}。只允许 ID、枚举、计数与脱敏摘要；枚举与必填键
 * fail-closed，未登记动作仍只允许空 details，与 API 读取白名单保持对称。
 */
public final class AuditDetailsPolicy {

    /** 每个动作允许的 details 键。 */
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.ofEntries(
            Map.entry(AuditActions.MODEL_INVOCATION_STARTED, Set.of("modelId", "purpose")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_COMPLETED,
                    Set.of("modelId", "durationMs", "promptTokens", "completionTokens")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_FAILED,
                    // reason 仅用于链空（CHAIN_EMPTY）；其余失败不写该键（ADR-0021 §4.1）。
                    Set.of("modelId", "durationMs", "errorCode", "retryable", "emitted", "reason")),
            Map.entry(
                    AuditActions.MCP_TOOL_INVOKED,
                    Set.of(
                            "toolName",
                            "paramsSha256",
                            "resultSha256",
                            "durationMs",
                            "approvalId",
                            "workflowRunId",
                            "workflowNodeId")),
            Map.entry(AuditActions.ARTIFACT_SAVED, Set.of("artifactId", "reportRunId", "version")),
            Map.entry(WorkerAuditActions.TENANT_ACCESS_DENIED, Set.of("attemptedTenant", "reason")),
            Map.entry(WorkerAuditActions.USER_ACCESS_DENIED, Set.of("userId", "reason")),
            // 首字前切换 key/模型；不含 credential ID、provider 地址或 key（ADR-0021 §7.4）。
            Map.entry(
                    AuditActions.MODEL_FAILOVER_TRIGGERED,
                    Set.of("purpose", "fromModelId", "toModelId", "reason", "attempt")),
            // 401/403 自动失效；以 tenant_id=platform、actor_type=SYSTEM 写入，只含掩码。
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_INVALIDATED,
                    Set.of("providerId", "credentialId", "maskedHint", "reason")),
            // M05 U8：管理员检索绕过文档 ACL 时写入；不写标题、片段、正文或名单。
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ,
                    Set.of("scope", "documentId", "visibility", "aclUserCount", "resultCount", "reason")),
            // M06 运行/审批生命周期（ADR-0024 §8.1）：只写 ID、枚举、计数与脱敏摘要；不写输入输出、
            // 图 JSON、工具参数或凭据。
            Map.entry(AuditActions.WORKFLOW_RUN_STARTED, Set.of("workflowRunId", "workflowId")),
            Map.entry(
                    AuditActions.WORKFLOW_RUN_COMPLETED,
                    Set.of("workflowRunId", "status", "durationMs", "nodeCount", "resultBytes")),
            Map.entry(AuditActions.WORKFLOW_RUN_FAILED, Set.of("workflowRunId", "errorCode", "failedNodeId")),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Set.of("workflowRunId", "previousStatus", "reason")),
            Map.entry(
                    AuditActions.WORKFLOW_APPROVAL_REQUESTED,
                    Set.of(
                            "workflowRunId",
                            "workflowApprovalId",
                            "nodeId",
                            "assigneeCount",
                            "actionKind",
                            "targetToolName")));

    /**
     * 允许 JSON null 的动作级键；没有 nullable 字段的动作不登记，新增时必须显式加入。
     *
     * <p>M06：失败节点、取消原因与目标工具名按 ADR-0024 §8.1 的 “?” 字段允许空；
     * {@code mcp.tool.invoked} 的 workflowRunId/workflowNodeId 只在工作流调用时写入，J2 调用可显式置空。
     */
    private static final Map<String, Set<String>> NULLABLE_KEYS = Map.ofEntries(
            Map.entry(AuditActions.WORKFLOW_RUN_FAILED, Set.of("failedNodeId")),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Set.of("reason")),
            Map.entry(AuditActions.WORKFLOW_APPROVAL_REQUESTED, Set.of("targetToolName")),
            Map.entry(AuditActions.MCP_TOOL_INVOKED, Set.of("workflowRunId", "workflowNodeId")));

    /**
     * 封闭枚举值的动作级校验。
     *
     * <p>与 API 读取白名单保持同一语义：写入非法枚举会让 M10 读取 fail-closed，因此在写入端提前拒绝。
     */
    private static final Map<String, Map<String, Set<String>>> ENUM_VALUES = Map.ofEntries(
            Map.entry(
                    AuditActions.WORKFLOW_RUN_COMPLETED,
                    Map.of(
                            "status",
                            Set.of(
                                    WorkflowRunStatus.SUCCEEDED.name(),
                                    WorkflowRunStatus.FAILED.name(),
                                    WorkflowRunStatus.CANCELLED.name()))),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Map.of("previousStatus", runStatusValues())),
            Map.entry(AuditActions.WORKFLOW_APPROVAL_REQUESTED, Map.of("actionKind", approvalActionKindValues())));

    /**
     * 允许受限数组的动作级键；M04 Worker 写入的动作没有数组字段，数组能力保留用于未来
     * 与 API 侧同一实现，非法类型继续 fail-closed。
     */
    private static final Map<String, Set<String>> ARRAY_KEYS = Map.of();

    /** 禁止出现的敏感键片段（大小写不敏感）。 */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "password",
            "token",
            "cookie",
            "secret",
            "credential",
            "prompt",
            "content",
            "email",
            "phone",
            "apikey",
            "ciphertext",
            "authorization",
            "baseurl");

    /** details 单值最大长度，避免把原文写入审计。 */
    private static final int MAX_VALUE_LENGTH = 200;

    /** 单个数组最多元素数（ADR-0021 §7.4）。 */
    private static final int MAX_ARRAY_ELEMENTS = 20;

    /** 单个数组元素总长度上限（ADR-0021 §7.4）。 */
    private static final int MAX_ARRAY_TOTAL_LENGTH = 2048;

    /** 工具类禁止实例化。 */
    private AuditDetailsPolicy() {}

    /**
     * 校验动作与 details。
     *
     * @param action 审计动作
     * @param details 待写入字段
     * @throws IllegalArgumentException 出现未授权键、敏感键或非法值类型
     */
    public static void validate(String action, Map<String, Object> details) {
        Map<String, Object> safeDetails = details == null ? Map.of() : details;
        Set<String> allowed = ALLOWED_KEYS.getOrDefault(action, Set.of());
        Set<String> nullable = NULLABLE_KEYS.getOrDefault(action, Set.of());
        Set<String> arrays = ARRAY_KEYS.getOrDefault(action, Set.of());
        for (Map.Entry<String, Object> entry : safeDetails.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("audit detail key must not be blank");
            }
            // 白名单键优先：promptTokens/completionTokens 是 ADR-0007 要求保留的指标，
            // 不能因为包含 "token" 子串被通用敏感词规则误杀；未列入白名单的键仍执行敏感词检查。
            if (!allowed.contains(key)) {
                String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
                if (FORBIDDEN_KEYS.stream().anyMatch(normalizedKey::contains)) {
                    throw new IllegalArgumentException("audit detail key is forbidden: " + key);
                }
                throw new IllegalArgumentException("audit detail key is not allowed for " + action + ": " + key);
            }
            validateValue(key, entry.getValue(), nullable.contains(key), arrays.contains(key));
            validateEnumValue(action, key, entry.getValue());
        }
        validateRequiredDetails(action, safeDetails);
    }

    /**
     * 校验封闭枚举字段。
     *
     * @param action 审计动作
     * @param key 字段名
     * @param value 字段值；null 已由 nullable 白名单处理
     */
    private static void validateEnumValue(String action, String key, Object value) {
        if (value == null) {
            return;
        }
        Set<String> allowedValues = ENUM_VALUES.getOrDefault(action, Map.of()).get(key);
        if (allowedValues == null) {
            return;
        }
        if (!(value instanceof String text) || !allowedValues.contains(text)) {
            throw new IllegalArgumentException("audit detail value is not allowed for " + action + ": " + key);
        }
    }

    /**
     * 校验 SEARCH 管理性读取的必填键与封闭枚举。
     *
     * <p>Worker 只写 {@code scope=SEARCH}；documentId/visibility/resultCount 必填，reason 固定
     * {@code MANAGEMENT_READ}。缺键、类型错误或未知枚举值 fail-closed。
     *
     * @param action 审计动作
     * @param details 已通过白名单校验的字段
     */
    private static void validateRequiredDetails(String action, Map<String, Object> details) {
        // M06：运行/审批审计的核心 ID 与枚举必填，避免写入无法归属的审计行（ADR-0024 §8.1/§8.2）。
        switch (action) {
            case AuditActions.WORKFLOW_RUN_STARTED -> {
                requireText(details, "workflowRunId");
                requireText(details, "workflowId");
                return;
            }
            case AuditActions.WORKFLOW_RUN_COMPLETED -> {
                requireText(details, "workflowRunId");
                requireText(details, "status");
                return;
            }
            case AuditActions.WORKFLOW_RUN_FAILED -> {
                requireText(details, "workflowRunId");
                requireText(details, "errorCode");
                return;
            }
            case AuditActions.WORKFLOW_RUN_CANCELLED -> {
                requireText(details, "workflowRunId");
                requireText(details, "previousStatus");
                return;
            }
            case AuditActions.WORKFLOW_APPROVAL_REQUESTED -> {
                requireText(details, "workflowRunId");
                requireText(details, "workflowApprovalId");
                requireText(details, "nodeId");
                requireText(details, "actionKind");
                return;
            }
            default -> {
                // 其他动作继续走下方 admin_read 分支或只依赖白名单。
            }
        }
        if (!AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ.equals(action)) {
            return;
        }
        if (!"SEARCH".equals(details.get("scope"))) {
            throw new IllegalArgumentException("worker admin_read scope must be SEARCH");
        }
        if (!"MANAGEMENT_READ".equals(details.get("reason"))) {
            throw new IllegalArgumentException("admin_read reason must be MANAGEMENT_READ");
        }
        if (!(details.get("documentId") instanceof String documentId) || documentId.isBlank()) {
            throw new IllegalArgumentException("admin_read documentId is required");
        }
        if (!(details.get("visibility") instanceof String visibility)
                || java.util.Arrays.stream(KnowledgeVisibility.values())
                        .noneMatch(value -> value.name().equals(visibility))) {
            throw new IllegalArgumentException("admin_read visibility is invalid");
        }
        if (!(details.get("resultCount") instanceof Number resultCount) || resultCount.longValue() < 0) {
            throw new IllegalArgumentException("admin_read resultCount must be a non-negative number");
        }
    }

    /**
     * 校验必填文本键非空。
     *
     * @param details 已通过白名单校验的字段
     * @param key 字段名
     */
    private static void requireText(Map<String, Object> details, String key) {
        if (!(details.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("audit detail key is required: " + key);
        }
    }

    /** 返回运行状态枚举名集合，供取消动作的 previousStatus 校验使用。 */
    private static Set<String> runStatusValues() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (WorkflowRunStatus status : WorkflowRunStatus.values()) {
            values.add(status.name());
        }
        return Set.copyOf(values);
    }

    /** 返回审批动作类型枚举名集合，供 request 动作的 actionKind 校验使用。 */
    private static Set<String> approvalActionKindValues() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (WorkflowApprovalActionKind kind : WorkflowApprovalActionKind.values()) {
            values.add(kind.name());
        }
        return Set.copyOf(values);
    }

    /**
     * 校验值只能为可安全落库的基本类型或动作级受限数组。
     *
     * @param key 字段名
     * @param value 字段值
     * @param allowNull 是否允许 JSON null（仅动作级 nullable 白名单）
     * @param allowArray 是否允许受限 UUID 数组（仅动作级数组白名单）
     */
    private static void validateValue(String key, Object value, boolean allowNull, boolean allowArray) {
        if (value == null) {
            if (allowNull) {
                return;
            }
            throw new IllegalArgumentException("audit detail value must not be null: " + key);
        }
        if (value instanceof String text) {
            if (text.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("audit detail value is too long: " + key);
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> list) {
            if (!allowArray) {
                throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
            }
            validateUuidArray(key, list);
            return;
        }
        throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
    }

    /**
     * 校验受限 UUID 数组：最多 20 个 UUID 字符串，元素总长度不超过 2 KiB。
     *
     * @param key 字段名
     * @param values 数组值
     */
    private static void validateUuidArray(String key, List<?> values) {
        if (values.size() > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException("audit detail array has too many elements: " + key);
        }
        int totalLength = 0;
        for (Object element : values) {
            if (!(element instanceof String text)) {
                throw new IllegalArgumentException("audit detail array element type is not allowed: " + key);
            }
            try {
                UUID.fromString(text);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("audit detail array element must be a UUID: " + key, exception);
            }
            totalLength += text.length();
        }
        if (totalLength > MAX_ARRAY_TOTAL_LENGTH) {
            throw new IllegalArgumentException("audit detail array is too long: " + key);
        }
    }
}
