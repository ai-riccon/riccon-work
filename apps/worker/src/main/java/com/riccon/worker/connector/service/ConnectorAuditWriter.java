package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.common.UuidV7;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Worker 连接器审计写入。
 *
 * <p>动作与 details 由 {@link ConnectorAuditPolicy} fail-closed 校验；写入失败直接抛出，由同步处理器
 * 让当前事件失败（ADR-0007 第 4 节、ADR-0023 第 6.6 节）。日志与 details 不含凭据、对象 key 或端点 URL。
 */
@Component
public class ConnectorAuditWriter {

    /** Worker 服务操作者。 */
    static final String SERVICE_ACTOR = "riccon-worker";

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 时钟。 */
    private final Clock clock;

    /**
     * 创建审计写入器。
     *
     * @param auditRepository 审计仓储
     * @param clock 时钟
     */
    public ConnectorAuditWriter(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 同步开始。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param trigger 触发方式
     * @param traceId 追踪 ID
     */
    public void syncStarted(
            TenantId tenantId, UUID connectorId, UUID syncId, ConnectorSyncTrigger trigger, String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connectorId.toString());
        details.put("syncId", syncId.toString());
        details.put("trigger", trigger.name());
        append(
                tenantId,
                AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connectorId.toString(),
                traceId,
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 同步完成。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param summary 计数
     * @param durationMs 耗时
     * @param traceId 追踪 ID
     */
    public void syncCompleted(
            TenantId tenantId,
            UUID connectorId,
            UUID syncId,
            ConnectorSyncSummary summary,
            long durationMs,
            String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connectorId.toString());
        details.put("syncId", syncId.toString());
        details.put("scanned", summary.scanned());
        details.put("created", summary.created());
        details.put("updated", summary.updated());
        details.put("skipped", summary.skipped());
        details.put("failed", summary.failed());
        details.put("deleted", summary.deleted());
        details.put("durationMs", durationMs);
        details.put("complete", summary.complete());
        append(
                tenantId,
                AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connectorId.toString(),
                traceId,
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 同步失败。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param complete list 是否完整
     * @param durationMs 耗时
     * @param traceId 追踪 ID
     */
    public void syncFailed(
            TenantId tenantId,
            UUID connectorId,
            UUID syncId,
            String errorCode,
            boolean retryable,
            boolean complete,
            long durationMs,
            String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connectorId.toString());
        details.put("syncId", syncId.toString());
        details.put("errorCode", errorCode);
        details.put("retryable", retryable);
        details.put("complete", complete);
        details.put("durationMs", durationMs);
        append(
                tenantId,
                AuditActions.KNOWLEDGE_CONNECTOR_SYNC_FAILED,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connectorId.toString(),
                traceId,
                AuditOutcome.FAILURE,
                details);
    }

    /**
     * 同步重试。
     *
     * @param tenantId 租户
     * @param connectorId 连接器 ID
     * @param syncId 同步 ID
     * @param attempt 下一次尝试序号
     * @param reason 原因码
     * @param traceId 追踪 ID
     */
    public void syncRetried(
            TenantId tenantId, UUID connectorId, UUID syncId, int attempt, String reason, String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connectorId.toString());
        details.put("syncId", syncId.toString());
        details.put("attempt", attempt);
        details.put("reason", reason);
        append(
                tenantId,
                AuditActions.KNOWLEDGE_CONNECTOR_SYNC_RETRIED,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connectorId.toString(),
                traceId,
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 连接器状态变更（ERROR/恢复 ACTIVE）。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @param previousStatus 变更前状态
     * @param status 变更后状态
     * @param reason 原因
     * @param traceId 追踪 ID
     */
    public void statusChanged(
            TenantId tenantId,
            ConnectorRecord connector,
            ConnectorStatus previousStatus,
            ConnectorStatus status,
            String reason,
            String traceId) {
        ConnectorConfig config = connector.config();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectorId", connector.id().toString());
        details.put("type", connector.type().name());
        details.put("endpointId", connector.endpointId());
        details.put("bucket", truncate(config.bucket()));
        details.put("prefix", truncate(config.prefix()));
        details.put("status", status.name());
        details.put("previousStatus", previousStatus.name());
        details.put("reason", reason);
        append(
                tenantId,
                AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED,
                AuditTargetType.KNOWLEDGE_CONNECTOR,
                connector.id().toString(),
                traceId,
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 连接器文档删除传播。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param visibility 可见范围
     * @param traceId 追踪 ID
     */
    public void documentDeleted(TenantId tenantId, UUID documentId, KnowledgeVisibility visibility, String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceType", "CONNECTOR");
        details.put("visibility", visibility.name());
        details.put("reason", "CONNECTOR");
        append(
                tenantId,
                AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                AuditTargetType.KNOWLEDGE_DOCUMENT,
                documentId.toString(),
                traceId,
                AuditOutcome.SUCCESS,
                details);
    }

    /**
     * 统一追加审计。
     *
     * @param tenantId 租户
     * @param action 动作
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param traceId 追踪 ID
     * @param outcome 结果
     * @param details details
     */
    private void append(
            TenantId tenantId,
            String action,
            AuditTargetType targetType,
            String targetId,
            String traceId,
            AuditOutcome outcome,
            Map<String, Object> details) {
        ConnectorAuditPolicy.validate(action, details);
        Instant now = clock.instant();
        auditRepository.append(new AuditEvent(
                UuidV7.generate(),
                now,
                tenantId.value(),
                AuditActorType.SERVICE,
                SERVICE_ACTOR,
                action,
                targetType,
                targetId,
                outcome,
                traceId,
                null,
                details,
                AuditEvent.CURRENT_SCHEMA_VERSION));
    }

    /**
     * 截断审计文本。
     *
     * @param value 值
     * @return 最长 200 字符
     */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
