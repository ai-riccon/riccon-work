package com.riccon.worker.connector;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.connector.service.ConnectorSyncHandler;
import com.riccon.worker.connector.service.ConnectorSyncService;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.service.EventPayloadParser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接器关闭时 Worker 仍可能领到历史 outbox 事件：处理器必须直接跳过，不调用同步服务（ADR-0023 §7.5/§7.6）。
 */
class ConnectorDisabledFeatureVerifyTest {

    @Test
    void handle_featureDisabled_skipsWithoutTouchingRepositoryOrSyncService() {
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), new OutboxLaneProperties());
        ConnectorSyncService syncService = mock(ConnectorSyncService.class);
        ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        ConnectorSyncHandler handler = new ConnectorSyncHandler(
                syncService, connectorRepository, properties, new EventPayloadParser(new ObjectMapper()));
        UUID connectorId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                UUID.randomUUID(),
                "tenant-disabled",
                UUID.randomUUID(),
                "riccon.knowledge.connector.sync.requested.v1",
                1,
                "connector:" + connectorId + ":sync:" + syncId,
                "{\"connectorId\":\""
                        + connectorId
                        + "\",\"syncId\":\""
                        + syncId
                        + "\",\"startedAt\":\""
                        + Instant.now()
                        + "\"}",
                1,
                Instant.now(),
                Instant.now().plusSeconds(90));

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of("tenant-disabled"))) {
            handler.handle(event);
        }

        verifyNoInteractions(syncService, connectorRepository);
    }
}
