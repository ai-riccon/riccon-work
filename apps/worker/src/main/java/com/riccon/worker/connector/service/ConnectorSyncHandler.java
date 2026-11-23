package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncRequest;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.OutboxEventHandler;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import com.riccon.worker.run.service.EventPayloadParser;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 连接器同步 outbox 处理器。
 *
 * <p>领取 {@code riccon.knowledge.connector.sync.requested.v1} 后再次校验功能开关与租户 ACTIVE，然后调用
 * {@link ConnectorSyncService}。租户停用只跳过（不把连接器标成 ERROR），恢复后下一次定时/手动同步继续。
 * 运行类别复用 {@code KNOWLEDGE_INDEX}，与 API 写入的 {@code agent_run.kind} 一致。
 */
@Component
public class ConnectorSyncHandler implements OutboxEventHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorSyncHandler.class);

    /** 同步服务。 */
    private final ConnectorSyncService syncService;

    /** 仓储，用于租户/连接器复核。 */
    private final ConnectorRepository connectorRepository;

    /** 配置。 */
    private final ConnectorProperties properties;

    /** payload 解析。 */
    private final EventPayloadParser payloadParser;

    /**
     * 创建处理器。
     *
     * @param syncService 同步服务
     * @param connectorRepository 仓储
     * @param properties 配置
     * @param payloadParser payload 解析
     */
    public ConnectorSyncHandler(
            ConnectorSyncService syncService,
            ConnectorRepository connectorRepository,
            ConnectorProperties properties,
            EventPayloadParser payloadParser) {
        this.syncService = Objects.requireNonNull(syncService, "syncService must not be null");
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.payloadParser = Objects.requireNonNull(payloadParser, "payloadParser must not be null");
    }

    /** 返回事件类型。 */
    @Override
    public String eventType() {
        return EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED;
    }

    /** 返回运行类别。 */
    @Override
    public RunKind runKind() {
        return RunKind.KNOWLEDGE_INDEX;
    }

    /**
     * 处理同步事件。
     *
     * @param event 已领取事件
     */
    @Override
    public void handle(ClaimedOutboxEvent event) {
        JsonNode payload = payloadParser.parse(event);
        UUID connectorId = payloadParser.requiredUuid(payload, "connectorId");
        UUID syncId = payloadParser.requiredUuid(payload, "syncId");
        Instant startedAt = readStartedAt(payload);
        ConnectorSyncTrigger trigger = readTrigger(payload);
        int retryAttempt = retryAttempt(event.idempotencyKey());
        TenantId tenantId = TenantContext.require();
        if (!properties.enabled()) {
            LOG.info("connector.sync.skipped connectorId={} reason=feature_disabled", connectorId);
            return;
        }
        var tenant = connectorRepository.findTenant(tenantId);
        if (tenant.isEmpty() || !"ACTIVE".equals(tenant.get().status())) {
            LOG.info("connector.sync.skipped connectorId={} reason=tenant_not_active", connectorId);
            return;
        }
        ConnectorRecord connector =
                connectorRepository.findConnector(tenantId, connectorId).orElse(null);
        if (connector == null || connector.deletedAt() != null) {
            LOG.info("connector.sync.skipped connectorId={} reason=connector_not_found", connectorId);
            return;
        }
        String traceId = traceId(syncId);
        ConnectorSyncRequest request = new ConnectorSyncRequest(connectorId, syncId, startedAt, trigger, retryAttempt);
        syncService.synchronize(tenantId, connector, request, traceId);
    }

    /**
     * 读取 startedAt；缺失时使用当前时间。
     *
     * @param payload payload
     * @return 开始时间
     */
    private static Instant readStartedAt(JsonNode payload) {
        JsonNode node = payload.get("startedAt");
        if (node == null || node.isNull()) {
            return Instant.now();
        }
        if (!node.isTextual()) {
            throw invalid("payload field startedAt must be an ISO-8601 string");
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException exception) {
            throw invalid("payload field startedAt is not a valid ISO-8601 instant");
        }
    }

    /**
     * 读取触发方式；缺省 MANUAL。
     *
     * @param payload payload
     * @return 触发方式
     */
    private static ConnectorSyncTrigger readTrigger(JsonNode payload) {
        JsonNode node = payload.get("trigger");
        if (node == null || node.isNull()) {
            return ConnectorSyncTrigger.MANUAL;
        }
        if (!node.isTextual()) {
            throw invalid("payload field trigger must be a string");
        }
        try {
            return ConnectorSyncTrigger.valueOf(node.asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("payload field trigger is not a known trigger");
        }
    }

    /**
     * 从幂等键解析重试序号：{@code ...:retry:n}。
     *
     * @param idempotencyKey 幂等键
     * @return 重试序号；首次为 0
     */
    private static int retryAttempt(String idempotencyKey) {
        if (idempotencyKey == null) {
            return 0;
        }
        int marker = idempotencyKey.lastIndexOf(":retry:");
        if (marker < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(idempotencyKey.substring(marker + ":retry:".length()));
        } catch (NumberFormatException exception) {
            throw invalid("connector sync idempotency key has an invalid retry suffix");
        }
    }

    /**
     * 审计/日志追踪 ID：不使用对象 key 或凭据。
     *
     * @param syncId 同步 ID
     * @return 追踪 ID
     */
    private static String traceId(UUID syncId) {
        return "conn-" + syncId.toString().replace("-", "");
    }

    /**
     * 构造不可重试 payload 错误。
     *
     * @param message 脱敏说明
     * @return 异常
     */
    private static EventProcessingException invalid(String message) {
        return new EventProcessingException(WorkerErrorCodes.EVENT_INVALID, false, message);
    }
}
