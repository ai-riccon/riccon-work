package com.riccon.api.knowledge.connector.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 连接器审计事件构造。
 *
 * <p>details 严格落在 {@code AuditDetailsPolicy} 白名单内：只写连接器 ID、类型、端点 ID、bucket/prefix 摘要、
 * 状态、凭据 keyId/掩码、同步 ID 与计数；不含凭据明文/密文、DEK、对象正文、完整 externalId 或端点 URL。
 * bucket/prefix 是管理员配置的定位信息，prefix 截断到 200 字符（ADR-0023 第 6.5/6.6 节）。
 */
public final class ConnectorAuditEvents {

    /** details 单值最大长度，与审计策略一致。 */
    private static final int MAX_DETAIL_LENGTH = 200;

    /** 工具类禁止实例化。 */
    private ConnectorAuditEvents() {}

    /**
     * 连接器创建审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connector 创建后的连接器
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent created(
            Instant now, String tenantId, UUID actorId, ConnectorRecord connector, String traceId, String requestId) {
        return lifecycle(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_CREATED,
                connector,
                null,
                connector.status(),
                "CREATED",
                traceId,
                requestId);
    }

    /**
     * 连接器更新审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connector 更新后的连接器
     * @param previousStatus 更新前状态
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent updated(
            Instant now,
            String tenantId,
            UUID actorId,
            ConnectorRecord connector,
            ConnectorStatus previousStatus,
            String traceId,
            String requestId) {
        return lifecycle(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_UPDATED,
                connector,
                previousStatus,
                connector.status(),
                "UPDATED",
                traceId,
                requestId);
    }

    /**
     * 连接器状态变更审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connector 更新后的连接器
     * @param previousStatus 更新前状态
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent statusChanged(
            Instant now,
            String tenantId,
            UUID actorId,
            ConnectorRecord connector,
            ConnectorStatus previousStatus,
            String traceId,
            String requestId) {
        return lifecycle(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED,
                connector,
                previousStatus,
                connector.status(),
                "STATUS_CHANGED",
                traceId,
                requestId);
    }

    /**
     * 连接器软删除审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connector 删除前连接器
     * @param documentsDisposition 文档处置说明
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent deleted(
            Instant now,
            String tenantId,
            UUID actorId,
            ConnectorRecord connector,
            String documentsDisposition,
            String traceId,
            String requestId) {
        return lifecycle(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_DELETED,
                connector,
                connector.status(),
                connector.status(),
                documentsDisposition,
                traceId,
                requestId);
    }

    /**
     * 凭据写入/轮换审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connector 连接器
     * @param credentialKeyId 新 DEK 标识
     * @param maskedHint 掩码
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent credentialChanged(
            Instant now,
            String tenantId,
            UUID actorId,
            ConnectorRecord connector,
            String credentialKeyId,
            String maskedHint,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connector.id().toString());
        details.put("type", connector.type().name());
        details.put("credentialKeyId", credentialKeyId);
        details.put("maskedHint", maskedHint);
        return event(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED,
                connector.id(),
                traceId,
                requestId,
                details);
    }

    /**
     * 同步受理审计；Worker 侧的定时同步由 Worker 写同名事件。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param trigger 触发方式
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent syncStarted(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID connectorId,
            UUID syncId,
            ConnectorSyncTrigger trigger,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connectorId.toString());
        details.put("syncId", syncId.toString());
        details.put("trigger", trigger.name());
        return event(
                now,
                tenantId,
                actorId,
                AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                connectorId,
                traceId,
                requestId,
                details);
    }

    /**
     * 连接器文档删除审计（软删除或删除传播）。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param documentId 文档 ID
     * @param visibility 可见范围
     * @param reason 原因（{@code CONNECTOR}）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    public static AuditEvent documentDeleted(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID documentId,
            KnowledgeVisibility visibility,
            String reason,
            String traceId,
            String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceType", "CONNECTOR");
        details.put("visibility", visibility.name());
        details.put("reason", reason);
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                AuditTargetType.KNOWLEDGE_DOCUMENT,
                documentId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }

    /**
     * 构造连接器生命周期事件。
     *
     * @param now 时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param action 动作
     * @param connector 连接器
     * @param previousStatus 变更前状态；可为空
     * @param status 变更后状态
     * @param reason 原因
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @return 审计事件
     */
    private static AuditEvent lifecycle(
            Instant now,
            String tenantId,
            UUID actorId,
            String action,
            ConnectorRecord connector,
            ConnectorStatus previousStatus,
            ConnectorStatus status,
            String reason,
            String traceId,
            String requestId) {
        Objects.requireNonNull(connector, "connector must not be null");
        ConnectorConfig config = connector.config();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connector.id().toString());
        details.put("type", ConnectorType.S3.name());
        details.put("endpointId", connector.endpointId());
        details.put("bucket", truncate(config.bucket()));
        details.put("prefix", truncate(config.prefix()));
        details.put("status", status.name());
        details.put("previousStatus", previousStatus == null ? null : previousStatus.name());
        details.put("reason", reason);
        return event(now, tenantId, actorId, action, connector.id(), traceId, requestId, details);
    }

    /**
     * 统一构造连接器目标事件。
     *
     * @param now 时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param action 动作
     * @param connectorId 连接器 ID
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param details 白名单字段
     * @return 审计事件
     */
    private static AuditEvent event(
            Instant now,
            String tenantId,
            UUID actorId,
            String action,
            UUID connectorId,
            String traceId,
            String requestId,
            Map<String, Object> details) {
        return AuditEvent.create(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                action,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connectorId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }

    /**
     * 截断 details 文本。
     *
     * @param value 原值
     * @return 最长 200 字符；空值返回空串
     */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH);
    }
}
