package com.riccon.worker.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
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
                    Set.of("modelId", "durationMs", "errorCode", "retryable", "emitted")),
            Map.entry(
                    AuditActions.MCP_TOOL_INVOKED,
                    Set.of("toolName", "paramsSha256", "resultSha256", "durationMs", "approvalId")),
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
                    Set.of("providerId", "credentialId", "maskedHint", "reason")));

    /**
     * 允许 JSON null 的动作级键；M04 Worker 写入的动作没有 nullable 字段，保留空表以与 API 侧
     * 校验语义一致，新增时必须显式登记。
     */
    private static final Map<String, Set<String>> NULLABLE_KEYS = Map.of();

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
        }
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
