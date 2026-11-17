package com.riccon.api.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
import java.util.Map;
import java.util.Set;

/**
 * 审计 details 白名单。
 *
 * <p>ADR-0007 第 5 节要求 details 只写入白名单字段，禁止密码、token、Cookie、密钥、提示词、原文与完整 PII。
 * 本类在写入数据库前执行校验；未列出的动作只允许空 details，新增动作必须显式扩展白名单。
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
            Map.entry(AdditionalAuditActions.TENANT_ACCESS_DENIED, Set.of("attemptedTenant", "reason")));

    /** 任何情况下都不允许出现的敏感键（大小写不敏感）。 */
    private static final Set<String> FORBIDDEN_KEYS =
            Set.of("password", "token", "cookie", "secret", "credential", "prompt", "content", "email", "phone");

    /** ADR-0007 明确允许的文件审计键；它包含 forbidden 子串 "content"，但只表示 MIME，不含原文。 */
    private static final Set<String> FORBIDDEN_KEY_EXCEPTIONS = Set.of("contenttype");

    /** details 单值最大长度，避免把原文写入审计。 */
    private static final int MAX_VALUE_LENGTH = 200;

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
        for (Map.Entry<String, Object> entry : safeDetails.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("audit detail key must not be blank");
            }
            String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_KEYS.stream().anyMatch(normalizedKey::contains)
                    && !FORBIDDEN_KEY_EXCEPTIONS.contains(normalizedKey)) {
                throw new IllegalArgumentException("audit detail key is forbidden: " + key);
            }
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("audit detail key is not allowed for " + action + ": " + key);
            }
            validateValue(key, entry.getValue());
        }
    }

    /**
     * 校验 details 值只包含可安全落库的基本类型。
     *
     * @param key 字段名
     * @param value 字段值
     */
    private static void validateValue(String key, Object value) {
        if (value == null) {
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
        throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
    }
}
