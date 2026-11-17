package com.riccon.worker.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
import java.util.Map;
import java.util.Set;

/**
 * Worker 审计 details 白名单。
 *
 * <p>ADR-0007 第 5 节要求 details 只包含白名单字段。Worker 写模型、MCP 与产物保存动作；
 * 提示词、回答、文件原文、工具请求/响应原文、凭据与完整 PII 一律禁止。
 */
public final class AuditDetailsPolicy {

    /** 每个动作允许的 details 键。 */
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.of(
            AuditActions.MODEL_INVOCATION_STARTED, Set.of("modelId", "purpose"),
            AuditActions.MODEL_INVOCATION_COMPLETED,
                    Set.of("modelId", "durationMs", "promptTokens", "completionTokens"),
            AuditActions.MODEL_INVOCATION_FAILED, Set.of("modelId", "durationMs", "errorCode", "retryable"),
            AuditActions.MCP_TOOL_INVOKED,
                    Set.of("toolName", "paramsSha256", "resultSha256", "durationMs", "approvalId"),
            AuditActions.ARTIFACT_SAVED, Set.of("artifactId", "reportRunId", "version"),
            WorkerAuditActions.TENANT_ACCESS_DENIED, Set.of("attemptedTenant", "reason"),
            WorkerAuditActions.USER_ACCESS_DENIED, Set.of("userId", "reason"));

    /** 禁止出现的敏感键片段（大小写不敏感）。 */
    private static final Set<String> FORBIDDEN_KEYS =
            Set.of("password", "token", "cookie", "secret", "credential", "prompt", "content", "email", "phone");

    /** details 单值最大长度，避免把原文写入审计。 */
    private static final int MAX_VALUE_LENGTH = 200;

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
            validateValue(key, entry.getValue());
        }
    }

    /**
     * 校验值只能为可安全落库的基本类型。
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
