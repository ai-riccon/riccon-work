package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Worker 侧 {@code workflow.approval.decided} 审计 details 白名单。
 *
 * <p>ADR-0024 §8.1 把该动作的写入方登记为 api；Worker 侧决定状态机（T07a）为了验证“决定与审计
 * 同事务、审计失败即回滚”的语义，需要在本部署物内写入同名动作。T01 维护的
 * {@code com.riccon.worker.audit.domain.AuditDetailsPolicy} 只登记了
 * {@code workflow.approval.requested}，且该文件不在 T07a 写入范围；因此这里按 ADR 字段清单
 * 提供一份只用于 decided 的本地校验，字段集合与 API 侧读取白名单一致：
 * {@code workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?/
 * decisionComment?}。新增字段必须先改 ADR 与两侧白名单，禁止在这里放宽。
 *
 * <p>拒绝路径（自批/范围外/账号停用）复用同一动作但 {@code outcome=denied}，用于满足 ADR
 * §5.2 “自批 403 并写拒绝审计”；拒绝理由不写入，避免把未决内容落审计。
 */
public final class WorkflowApprovalAuditPolicy {

    /** 允许的 details 键。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "workflowRunId",
            "workflowApprovalId",
            "nodeId",
            "decision",
            "approverUserId",
            "targetToolName",
            "decisionComment");

    /** 允许为空的键。 */
    private static final Set<String> NULLABLE_KEYS = Set.of("targetToolName", "decisionComment");

    /** 理由最大长度；与 {@code decision_comment} 列宽和 U-05 临时默认一致。 */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** 其他字符串值最大长度。 */
    private static final int MAX_VALUE_LENGTH = 200;

    /** 工具名快照列宽。 */
    private static final int MAX_TOOL_NAME_LENGTH = 128;

    /** 工具类禁止实例化。 */
    private WorkflowApprovalAuditPolicy() {}

    /**
     * 校验决定审计 details。
     *
     * @param details 待写入字段
     * @throws IllegalArgumentException 出现未授权键、非字符串值或非法枚举
     */
    public static void validateDecided(Map<String, Object> details) {
        Map<String, Object> safeDetails = details == null ? Map.of() : details;
        for (Map.Entry<String, Object> entry : safeDetails.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "audit detail key is not allowed for workflow.approval.decided: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (value == null) {
                if (!NULLABLE_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("audit detail value must not be null: " + entry.getKey());
                }
                continue;
            }
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("audit detail value must be a string: " + entry.getKey());
            }
            int maxLength = switch (entry.getKey()) {
                case "decisionComment" -> MAX_COMMENT_LENGTH;
                case "targetToolName" -> MAX_TOOL_NAME_LENGTH;
                default -> MAX_VALUE_LENGTH;
            };
            if (text.isBlank() || text.codePointCount(0, text.length()) > maxLength) {
                throw new IllegalArgumentException("audit detail value length is invalid: " + entry.getKey());
            }
        }
        requireUuid(safeDetails, "workflowRunId");
        requireUuid(safeDetails, "workflowApprovalId");
        requireText(safeDetails, "nodeId");
        requireUuid(safeDetails, "approverUserId");
        Object decision = safeDetails.get("decision");
        if (!(decision instanceof String text)
                || Arrays.stream(WorkflowApprovalDecision.values())
                        .noneMatch(value -> value.name().equals(text))) {
            throw new IllegalArgumentException("audit detail decision is invalid");
        }
    }

    /**
     * 校验必填 UUID 文本。
     *
     * @param details 字段
     * @param key 键
     */
    private static void requireUuid(Map<String, Object> details, String key) {
        if (!(details.get(key) instanceof String text)) {
            throw new IllegalArgumentException("audit detail key is required: " + key);
        }
        try {
            UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("audit detail value must be a UUID: " + key, exception);
        }
    }

    /**
     * 校验必填非空文本。
     *
     * @param details 字段
     * @param key 键
     */
    private static void requireText(Map<String, Object> details, String key) {
        if (!(details.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("audit detail key is required: " + key);
        }
    }
}
