package com.riccon.worker.connector.service;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worker 连接器审计 details 白名单。
 *
 * <p>与 API {@code AuditDetailsPolicy} 的连接器条目保持同一键集合；Worker 只写同步生命周期、
 * 状态变更与连接器文档删除传播，禁止凭据、对象正文、完整 externalId、bucket 之外的对象 key 或端点 URL。
 * 未列出的动作或键 fail-closed（ADR-0023 第 6.6 节、TM-64）。
 *
 * <p>注意：B1 的 Worker {@code AuditDetailsPolicy} 尚未登记连接器动作；本类在 B6 写范围内补齐同一份
 * 白名单，避免修改共享审计策略文件。评审时应与 API 侧逐键对齐。
 */
public final class ConnectorAuditPolicy {

    /** 同步计数与通用字段键。 */
    private static final Set<String> SYNC_COMPLETED_KEYS = Set.of(
            "connectorId",
            "syncId",
            "scanned",
            "created",
            "updated",
            "skipped",
            "failed",
            "deleted",
            "durationMs",
            "complete");

    /** 动作白名单。 */
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.ofEntries(
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED, Set.of("connectorId", "syncId", "trigger")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED, SYNC_COMPLETED_KEYS),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_FAILED,
                    Set.of("connectorId", "syncId", "errorCode", "retryable", "complete", "durationMs")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_RETRIED,
                    Set.of("connectorId", "syncId", "attempt", "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED,
                    Set.of(
                            "connectorId",
                            "type",
                            "endpointId",
                            "bucket",
                            "prefix",
                            "status",
                            "previousStatus",
                            "reason")),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, Set.of("sourceType", "visibility", "reason")));

    /** 封闭枚举。 */
    private static final Map<String, Map<String, Set<String>>> ENUM_VALUES = Map.ofEntries(
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                    Map.of(
                            "trigger",
                            Set.of(
                                    ConnectorSyncTrigger.MANUAL.name(),
                                    ConnectorSyncTrigger.SCHEDULE.name(),
                                    ConnectorSyncTrigger.RETRY.name()))),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED,
                    Map.of(
                            "status",
                                    Set.of(
                                            ConnectorStatus.ACTIVE.name(),
                                            ConnectorStatus.PAUSED.name(),
                                            ConnectorStatus.ERROR.name()),
                            "previousStatus",
                                    Set.of(
                                            ConnectorStatus.ACTIVE.name(),
                                            ConnectorStatus.PAUSED.name(),
                                            ConnectorStatus.ERROR.name()))),
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                    Map.of(
                            "sourceType", Set.of("CONNECTOR"),
                            "reason", Set.of("CONNECTOR"),
                            "visibility",
                                    Set.of(
                                            KnowledgeVisibility.TENANT.name(),
                                            KnowledgeVisibility.DEPT.name(),
                                            KnowledgeVisibility.USERS.name()))));

    /** 单值最大长度。 */
    private static final int MAX_VALUE_LENGTH = 200;

    /** 工具类禁止实例化。 */
    private ConnectorAuditPolicy() {}

    /**
     * 校验动作与 details。
     *
     * @param action 动作
     * @param details details
     * @throws IllegalArgumentException 未授权动作/键、非法类型或枚举
     */
    public static void validate(String action, Map<String, Object> details) {
        Set<String> allowed = ALLOWED_KEYS.get(action);
        if (allowed == null) {
            throw new IllegalArgumentException("worker connector audit action is not allowed: " + action);
        }
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "worker connector audit detail key is not allowed for " + action + ": " + entry.getKey());
            }
            validateValue(entry.getKey(), entry.getValue());
            validateEnum(action, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 校验值类型。
     *
     * @param key 键
     * @param value 值
     */
    private static void validateValue(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("worker connector audit detail must not be null: " + key);
        }
        if (value instanceof String text) {
            if (text.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("worker connector audit detail is too long: " + key);
            }
            return;
        }
        if (!(value instanceof Number) && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("worker connector audit detail type is not allowed: " + key);
        }
    }

    /**
     * 校验封闭枚举。
     *
     * @param action 动作
     * @param key 键
     * @param value 值
     */
    private static void validateEnum(String action, String key, Object value) {
        Set<String> allowed = ENUM_VALUES.getOrDefault(action, Map.of()).get(key);
        if (allowed == null || !(value instanceof String text)) {
            return;
        }
        if (!allowed.contains(text)) {
            throw new IllegalArgumentException(
                    "worker connector audit detail value is not allowed for " + action + ": " + key);
        }
    }

    /**
     * 返回允许的动作列表，仅供测试断言。
     *
     * @return 动作列表
     */
    public static List<String> actions() {
        return List.copyOf(ALLOWED_KEYS.keySet());
    }
}
