package com.riccon.worker.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncRequest;
import com.riccon.worker.connector.domain.ConnectorTenantInfo;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.service.EventPayloadParser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ConnectorSyncHandler 分支矩阵：payload 时间/重试后缀、租户/连接器跳过与成功同步调用。
 */
class ConnectorSyncHandlerBranchTest {

    /** 测试租户。 */
    private static final String TENANT = "tenant-handler";

    /** 测试对象映射。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void handle_missingStartedAtUsesNowAndManualTrigger() {
        Fixture fixture = fixture("{}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            fixture.handler.handle(fixture.event("connector:sync"));
        }
        ConnectorSyncRequest request = captureRequest(fixture.syncService);
        assertThat(request.trigger()).isEqualTo(ConnectorSyncTrigger.MANUAL);
        assertThat(request.retryAttempt()).isZero();
        assertThat(request.startedAt()).isNotNull();
    }

    @Test
    void handle_retrySuffixSetsRetryAndAttempt() {
        Fixture fixture = fixture("{\"startedAt\":\"2026-01-01T00:00:00Z\"}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            fixture.handler.handle(fixture.event("connector:sync:retry:2"));
        }
        ConnectorSyncRequest request = captureRequest(fixture.syncService);
        assertThat(request.trigger()).isEqualTo(ConnectorSyncTrigger.RETRY);
        assertThat(request.retryAttempt()).isEqualTo(2);
    }

    @Test
    void handle_rejectsMalformedStartedAtAndRetrySuffix() {
        Fixture nonText = fixture("{\"startedAt\":123}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            assertThatThrownBy(() -> nonText.handler.handle(nonText.event("connector:sync")))
                    .isInstanceOf(EventProcessingException.class);
        }
        Fixture badDate = fixture("{\"startedAt\":\"not-date\"}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            assertThatThrownBy(() -> badDate.handler.handle(badDate.event("connector:sync")))
                    .isInstanceOf(EventProcessingException.class);
        }
        Fixture badRetry = fixture("{}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            assertThatThrownBy(() -> badRetry.handler.handle(badRetry.event("connector:sync:retry:abc")))
                    .isInstanceOf(EventProcessingException.class);
        }
    }

    @Test
    void handle_skipsInactiveTenantAndMissingConnector() {
        Fixture noTenant = fixture("{}");
        when(noTenant.repository.findTenant(any())).thenReturn(Optional.empty());
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            noTenant.handler.handle(noTenant.event("connector:sync"));
        }
        verifyNoInteractions(noTenant.syncService);

        Fixture suspended = fixture("{}");
        when(suspended.repository.findTenant(any())).thenReturn(Optional.of(new ConnectorTenantInfo("SUSPENDED", 180)));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            suspended.handler.handle(suspended.event("connector:sync"));
        }
        verifyNoInteractions(suspended.syncService);

        Fixture missing = fixture("{}");
        when(missing.repository.findTenant(any())).thenReturn(Optional.of(new ConnectorTenantInfo("ACTIVE", 180)));
        when(missing.repository.findConnector(any(), any())).thenReturn(Optional.empty());
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            missing.handler.handle(missing.event("connector:sync"));
        }
        verify(missing.syncService, never()).synchronize(any(), any(), any(), any());
    }

    @Test
    void handle_skipsDeletedConnectorAndNullRetryKey() {
        Fixture deleted = fixture("{}");
        when(deleted.repository.findConnector(any(), any())).thenReturn(Optional.of(connector(Instant.now())));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            deleted.handler.handle(deleted.event("connector:sync"));
        }
        verify(deleted.syncService, never()).synchronize(any(), any(), any(), any());

        Fixture nullKey = fixture("{}");
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(TENANT))) {
            nullKey.handler.handle(nullKey.event(null));
        }
        ConnectorSyncRequest request = captureRequest(nullKey.syncService);
        assertThat(request.trigger()).isEqualTo(ConnectorSyncTrigger.MANUAL);
        assertThat(request.retryAttempt()).isZero();
    }

    /**
     * 构造测试夹具。
     *
     * @param payloadSuffix payload 附加字段
     * @return 夹具
     */
    private static Fixture fixture(String payloadSuffix) {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        ConnectorSyncService syncService = mock(ConnectorSyncService.class);
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), new OutboxLaneProperties());
        properties.setEnabled(true);
        when(repository.findTenant(any())).thenReturn(Optional.of(new ConnectorTenantInfo("ACTIVE", 180)));
        when(repository.findConnector(any(), any())).thenReturn(Optional.of(connector(null)));
        ConnectorSyncHandler handler =
                new ConnectorSyncHandler(syncService, repository, properties, new EventPayloadParser(JSON));
        return new Fixture(handler, repository, syncService, payloadSuffix);
    }

    private static ConnectorSyncRequest captureRequest(ConnectorSyncService service) {
        ArgumentCaptor<ConnectorSyncRequest> captor = ArgumentCaptor.forClass(ConnectorSyncRequest.class);
        verify(service).synchronize(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    /**
     * 构造连接器快照。
     *
     * @param deletedAt 删除时间
     * @return 连接器
     */
    private static ConnectorRecord connector(Instant deletedAt) {
        return new ConnectorRecord(
                UUID.randomUUID(),
                "connector",
                ConnectorType.S3,
                ConnectorStatus.PAUSED,
                "endpoint",
                new ConnectorConfig("bucket", "prefix", "us-east-1", null, null),
                KnowledgeVisibility.TENANT,
                null,
                null,
                null,
                false,
                null,
                ConnectorSyncStatus.SUCCESS,
                UUID.randomUUID(),
                Instant.now(),
                deletedAt);
    }

    /**
     * 测试夹具。
     *
     * @param handler 处理器
     * @param repository 仓储
     * @param syncService 同步服务
     * @param payloadSuffix payload 后缀
     */
    private record Fixture(
            ConnectorSyncHandler handler,
            ConnectorRepository repository,
            ConnectorSyncService syncService,
            String payloadSuffix) {

        /**
         * 构造事件。
         *
         * @param idempotencyKey 幂等键
         * @return 事件
         */
        ClaimedOutboxEvent event(String idempotencyKey) {
            UUID connectorId = UUID.randomUUID();
            UUID syncId = UUID.randomUUID();
            ObjectNode payload = JSON.createObjectNode();
            payload.put("connectorId", connectorId.toString());
            payload.put("syncId", syncId.toString());
            if (!"{}".equals(payloadSuffix)) {
                ObjectNode suffix;
                try {
                    suffix = (ObjectNode) JSON.readTree(payloadSuffix);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                suffix.properties().forEach(entry -> payload.set(entry.getKey(), entry.getValue()));
            }
            return new ClaimedOutboxEvent(
                    UUID.randomUUID(),
                    TENANT,
                    UUID.randomUUID(),
                    "riccon.knowledge.connector.sync.requested.v1",
                    1,
                    idempotencyKey,
                    JSON.writeValueAsString(payload),
                    1,
                    Instant.now(),
                    Instant.now().plusSeconds(90));
        }
    }
}
