package com.riccon.api.audit.service;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计 details 读取策略。
 *
 * <p>读取与写入使用同一 action-aware 白名单（ADR-0020 第 1/6.1 节）：白名单外的键、未知动作的非空 details
 * 以及敏感键命中即 fail-closed {@code RICCON-AUDIT-500}，不静默剔除。禁止用 {@code token}/{@code secret}/
 * {@code content} 子串规则，{@code promptTokens}/{@code completionTokens} 等合法指标必须通过。
 * M06 的 {@code workflow.*} 动作由 {@link AuditDetailsPolicy} 同步登记，读取端无需第二份白名单。
 */
public final class AuditDetailsReader {

    /** 工具类禁止实例化。 */
    private AuditDetailsReader() {}

    /**
     * 解析并校验 details JSON。
     *
     * @param objectMapper JSON 解析器
     * @param action 动作名
     * @param detailsJson 数据库中的 details JSON，可为空
     * @return 通过白名单的 details；无 details 时为空映射
     * @throws AuditDetailsInvalidException JSON 损坏或命中读取策略
     */
    public static Map<String, Object> read(ObjectMapper objectMapper, String action, String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank() || "null".equals(detailsJson)) {
            return Map.of();
        }
        Map<String, Object> details;
        try {
            details = objectMapper.readValue(detailsJson, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException exception) {
            throw new AuditDetailsInvalidException();
        }
        if (details == null) {
            return Map.of();
        }
        try {
            AuditDetailsPolicy.validate(action, details);
        } catch (IllegalArgumentException exception) {
            throw new AuditDetailsInvalidException();
        }
        return details;
    }
}
