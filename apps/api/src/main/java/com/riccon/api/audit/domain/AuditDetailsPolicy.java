package com.riccon.api.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 审计 details 白名单。
 *
 * <p>ADR-0007 第 5 节与 ADR-0018 第 5 节要求 details 只写入白名单字段，禁止密码、token、Cookie、密钥、
 * 提示词、原文、部门成员名单与完整 PII。本类在写入数据库前执行校验；未列出的动作只允许空 details，
 * 新增动作必须显式扩展白名单。
 *
 * <p>M10：本类同时是审计读取（B3）的 action-aware 白名单，因此必须包含 worker 写入的模型/MCP/产物动作，
 * 并允许 {@code promptTokens}/{@code completionTokens} 等白名单指标。判断顺序必须是“白名单优先”，
 * 不能再用 {@code token}/{@code secret}/{@code content} 子串规则误杀合法键（ADR-0020 F-003）。
 */
public final class AuditDetailsPolicy {

    /** 每个动作允许的 details 键。 */
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.ofEntries(
            Map.entry(AuditActions.AUTH_LOGIN_SUCCEEDED, Set.of("method", "sessionIdHash")),
            Map.entry(AuditActions.AUTH_LOGIN_FAILED, Set.of("reasonCode", "attemptCount")),
            Map.entry(AuditActions.AUTH_LOGOUT, Set.of()),
            Map.entry(AuditActions.TENANT_CREATED, Set.of("plan")),
            Map.entry(AdditionalAuditActions.TENANT_UPDATED, Set.of("status", "retentionDays")),
            Map.entry(AuditActions.USER_CREATED, Set.of("role", "targetTenantId")),
            Map.entry(AuditActions.USER_DISABLED, Set.of("enabled", "targetTenantId")),
            Map.entry(AuditActions.PERMISSION_CHANGED, Set.of("targetUserId", "role", "change", "targetTenantId")),
            Map.entry(AuditActions.FILE_UPLOADED, Set.of("fileId", "sizeBytes", "contentType", "sha256")),
            Map.entry(AuditActions.FILE_DELETED, Set.of("fileId", "objectKeyHash")),
            // ADR-0007 第 2 节规定新建会话的 details 只写 sessionId。
            Map.entry(AuditActions.SESSION_CREATED, Set.of("sessionId")),
            Map.entry(AdditionalAuditActions.SESSION_DELETED, Set.of("sessionId")),
            Map.entry(
                    AdditionalAuditActions.MESSAGE_SENT,
                    Set.of("userMessageId", "assistantMessageId", "runId", "attachmentCount")),
            Map.entry(AdditionalAuditActions.MESSAGE_STOPPED, Set.of("messageId", "runId", "reason")),
            Map.entry(
                    AdditionalAuditActions.RESEARCH_TASK_CREATED,
                    Set.of("researchTaskId", "workspaceId", "runId", "mcpToolId")),
            // ADR-0007 第 2 节：审批审计只允许审批 ID、决定、审批人与工具名。
            Map.entry(AuditActions.APPROVAL_DECIDED, Set.of("approvalId", "decision", "approverUserId", "toolName")),
            Map.entry(AdditionalAuditActions.ARTIFACT_DOWNLOADED, Set.of("artifactId", "version")),
            Map.entry(AdditionalAuditActions.TENANT_ACCESS_DENIED, Set.of("attemptedTenant", "reason")),
            // M03 部门元数据：只记录 UUID、枚举与部门名；targetTenantId 只用于平台跨租户事件。
            Map.entry(AuditActions.DEPARTMENT_CREATED, Set.of("targetTenantId", "departmentId", "name", "status")),
            Map.entry(
                    AuditActions.DEPARTMENT_RENAMED, Set.of("targetTenantId", "departmentId", "name", "previousName")),
            Map.entry(
                    AuditActions.DEPARTMENT_DISABLED,
                    Set.of("targetTenantId", "departmentId", "status", "memberCount")),
            Map.entry(
                    AuditActions.DEPARTMENT_ENABLED, Set.of("targetTenantId", "departmentId", "status", "memberCount")),
            Map.entry(
                    AuditActions.DEPARTMENT_MEMBER_ADDED,
                    Set.of("targetTenantId", "departmentId", "userId", "previousDepartmentId")),
            Map.entry(
                    AuditActions.DEPARTMENT_MEMBER_REMOVED,
                    Set.of("targetTenantId", "departmentId", "userId", "removedWorkspaceMembers")),
            Map.entry(
                    AuditActions.DEPARTMENT_MANAGER_CHANGED,
                    Set.of("targetTenantId", "departmentId", "managerUserId", "previousManagerUserId")),
            // M03 租户内空间管理：不写成员名单、空间内容或文件名，只写 ID、名称与可见性枚举。
            Map.entry(AuditActions.WORKSPACE_CREATED, Set.of("workspaceId", "departmentId", "visibility")),
            Map.entry(AuditActions.WORKSPACE_UPDATED, Set.of("workspaceId", "name", "previousName")),
            Map.entry(
                    AuditActions.WORKSPACE_VISIBILITY_CHANGED,
                    Set.of("workspaceId", "visibility", "previousVisibility", "departmentId")),
            Map.entry(AuditActions.WORKSPACE_MEMBER_ADDED, Set.of("workspaceId", "userId")),
            Map.entry(AuditActions.WORKSPACE_MEMBER_REMOVED, Set.of("workspaceId", "userId")),
            // M10 审计查询/导出（ADR-0020 第 6.1 节）：只写哈希、计数与 ID，不写 filters 原文或导出内容。
            Map.entry(AuditActions.AUDIT_QUERIED, Set.of("filtersHash", "rowCount")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_REQUESTED,
                    Set.of(
                            "exportId",
                            "filtersHash",
                            "estimatedRowCount",
                            "status",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_APPROVED,
                    Set.of(
                            "exportId",
                            "decision",
                            "estimatedRowCount",
                            "filtersHash",
                            "targetTenantId",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_REJECTED,
                    Set.of(
                            "exportId",
                            "decision",
                            "estimatedRowCount",
                            "filtersHash",
                            "targetTenantId",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(AuditActions.DATA_EXPORTED, Set.of("exportId", "scope", "rowCount", "filtersHash")),
            // M10 OIDC 绑定（ADR-0019 第 3 节）：只存用户 ID 与 identity 哈希，不存 subject 原文或邮箱。
            Map.entry(
                    AuditActions.USER_OIDC_BOUND, Set.of("userId", "issuerHash", "subjectHash", "previousAuthSource")),
            Map.entry(AuditActions.USER_OIDC_UNBOUND, Set.of("userId", "issuerHash")),
            // Worker 侧已写入的动作；API 读取策略必须识别同一白名单（ADR-0020 F-003/F-005）。
            Map.entry(AdditionalAuditActions.USER_ACCESS_DENIED, Set.of("userId", "reason")),
            Map.entry(AuditActions.MODEL_INVOCATION_STARTED, Set.of("modelId", "purpose")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_COMPLETED,
                    Set.of("modelId", "durationMs", "promptTokens", "completionTokens")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_FAILED,
                    Set.of("modelId", "durationMs", "errorCode", "retryable", "emitted")),
            Map.entry(
                    AuditActions.MCP_TOOL_INVOKED,
                    Set.of("toolName", "paramsSha256", "resultSha256", "durationMs", "approvalId")),
            Map.entry(AuditActions.ARTIFACT_SAVED, Set.of("artifactId", "reportRunId", "version")),
            // M04 模型路由/模型/降级/key 池/开关（ADR-0021 §7.4）：只写 ID、枚举、版本与掩码，不写
            // baseUrl、provider code、key、Authorization、提示词或原文。
            Map.entry(
                    AuditActions.MODEL_ROUTING_UPDATED,
                    Set.of("purpose", "modelIds", "version", "previousVersion", "result")),
            Map.entry(AuditActions.MODEL_UPDATED, Set.of("modelId", "status", "providerId")),
            Map.entry(
                    AuditActions.MODEL_FAILOVER_TRIGGERED,
                    Set.of("purpose", "fromModelId", "toModelId", "reason", "attempt")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_CREATED,
                    Set.of("providerId", "credentialId", "maskedHint", "status")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_ROTATED,
                    Set.of("providerId", "credentialId", "previousCredentialId", "maskedHint", "status")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_DISABLED,
                    Set.of("providerId", "credentialId", "maskedHint", "status", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_ENABLED,
                    Set.of("providerId", "credentialId", "maskedHint", "status", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_INVALIDATED,
                    Set.of("providerId", "credentialId", "maskedHint", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_RECOVERED,
                    Set.of("providerId", "credentialId", "maskedHint", "reason")),
            Map.entry(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED, Set.of("switch", "value", "previousValue", "source")),
            // M04 配额（ADR-0022 §3.4）：scopeId 是租户代码或用户 UUID；不接受用户名单、金额或提示词。
            Map.entry(
                    AuditActions.QUOTA_LIMIT_CHANGED,
                    Set.of("scopeType", "scopeId", "window", "previousLimit", "limit")),
            Map.entry(
                    AuditActions.QUOTA_EXCEEDED,
                    Set.of("scopeType", "scopeId", "window", "limit", "used", "resetsAt")));

    /**
     * 允许 JSON null 的动作级键。
     *
     * <p>只用于“首次设置”确实不存在前值的情况（{@code quota.limit.changed.previousLimit}）；
     * 其他键继续 fail-closed。不得放宽为任意 nullable，避免空值掩盖写入缺陷。
     */
    private static final Map<String, Set<String>> NULLABLE_KEYS =
            Map.of(AuditActions.QUOTA_LIMIT_CHANGED, Set.of("previousLimit"));

    /**
     * 允许受限数组的动作级键。
     *
     * <p>{@code model.routing.updated.modelIds} 是唯一数组；元素必须是 UUID 字符串，最多 20 个，
     * 数组元素总长度不超过 2 KiB。数组之外的类型仍按基本类型校验，禁止任意 JSON。
     */
    private static final Map<String, Set<String>> ARRAY_KEYS =
            Map.of(AuditActions.MODEL_ROUTING_UPDATED, Set.of("modelIds"));

    /** 任何情况下都不允许出现的敏感键（大小写不敏感）。 */
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

    /** 单个数组最多元素数；ADR-0021 §7.4 规定最多 20 个 UUID。 */
    private static final int MAX_ARRAY_ELEMENTS = 20;

    /** 单个数组元素总长度上限；ADR-0021 §7.4 规定总长不超过 2 KiB。 */
    private static final int MAX_ARRAY_TOTAL_LENGTH = 2048;

    /** 工具类禁止实例化。 */
    private AuditDetailsPolicy() {}

    /**
     * 校验动作与 details 是否符合白名单。
     *
     * @param action 审计动作名
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
            // 白名单优先：promptTokens/completionTokens/sessionIdHash/contentType 等合法指标不能被
            // "token"/"content" 子串规则误杀；只有白名单外的键才执行敏感词检查（ADR-0020 F-003）。
            if (allowed.contains(key)) {
                validateValue(key, entry.getValue(), nullable.contains(key), arrays.contains(key));
                continue;
            }
            String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_KEYS.stream().anyMatch(normalizedKey::contains)) {
                throw new IllegalArgumentException("audit detail key is forbidden: " + key);
            }
            throw new IllegalArgumentException("audit detail key is not allowed for " + action + ": " + key);
        }
    }

    /**
     * 校验 details 值只包含可安全落库的基本类型或动作级受限数组。
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
     * <p>只允许 {@link List}，不接受 Map/嵌套数组或任意 JSON；非法类型继续 fail-closed。
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
