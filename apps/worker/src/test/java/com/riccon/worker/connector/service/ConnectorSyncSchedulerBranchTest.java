package com.riccon.worker.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.DueConnector;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.run.config.OutboxLaneProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

/**
 * ConnectorSyncScheduler 分支矩阵：开关、扫描失败、领取竞争、挂起/删除/无 actor、重复键与成功投递。
 */
class ConnectorSyncSchedulerBranchTest {

    /** 固定时钟。 */
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void scheduleDue_respectsFeatureAndSchedulerSwitches() {
        Fixture fixture = fixture();
        fixture.properties.setEnabled(false);
        fixture.scheduler.scheduleDue();
        verifyNoInteractions(fixture.repository, fixture.publisher);

        Fixture schedulerOff = fixture();
        schedulerOff.properties.setSchedulerEnabled(false);
        schedulerOff.scheduler.scheduleDue();
        verifyNoInteractions(schedulerOff.repository, schedulerOff.publisher);
    }

    @Test
    void scheduleDue_swallowsScanFailure() {
        Fixture fixture = fixture();
        when(fixture.repository.findDue(any(), anyInt())).thenThrow(new IllegalStateException("db"));
        assertThatCode(fixture.scheduler::scheduleDue).doesNotThrowAnyException();
        verifyNoInteractions(fixture.publisher);
    }

    @Test
    void scheduleOne_skipsClaimLossPendingEventMissingDeletedAndNoActor() {
        Fixture claimLoss = fixture();
        when(claimLoss.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(false);
        claimLoss.scheduler.scheduleDue();
        verifyNoInteractions(claimLoss.publisher);

        Fixture pending = fixture();
        when(pending.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(pending.repository.hasPendingSyncEvent(any(), any())).thenReturn(true);
        pending.scheduler.scheduleDue();
        verifyNoInteractions(pending.publisher);

        Fixture missing = fixture();
        when(missing.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(missing.repository.findConnector(any(), any())).thenReturn(Optional.empty());
        missing.scheduler.scheduleDue();
        verifyNoInteractions(missing.publisher);

        Fixture deleted = fixture();
        when(deleted.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(deleted.repository.findConnector(any(), any())).thenReturn(Optional.of(connector(null, NOW)));
        deleted.scheduler.scheduleDue();
        verifyNoInteractions(deleted.publisher);

        Fixture noActor = fixture();
        when(noActor.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(noActor.repository.findConnector(any(), any())).thenReturn(Optional.of(connector(null, null)));
        when(noActor.repository.findFirstActiveUserId(any())).thenReturn(Optional.empty());
        noActor.scheduler.scheduleDue();
        verifyNoInteractions(noActor.publisher);
    }

    @Test
    void scheduleOne_swallowsDuplicateAndContinuesAfterCandidateFailure() {
        Fixture duplicate = fixture();
        when(duplicate.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(duplicate.publisher.enqueueSync(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("dup"));
        assertThatCode(duplicate.scheduler::scheduleDue).doesNotThrowAnyException();

        Fixture continues = fixture();
        when(continues.repository.findDue(any(), anyInt()))
                .thenReturn(List.of(due("tenant-a", UUID.randomUUID()), due("tenant-b", UUID.randomUUID())));
        when(continues.repository.claimDue(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("first"))
                .thenReturn(true);
        continues.scheduler.scheduleDue();
        verify(continues.publisher).enqueueSync(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void scheduleOne_enqueuesScheduleSyncWithActorAndTrigger() {
        Fixture fixture = fixture();
        when(fixture.repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(fixture.repository.findConnector(any(), any()))
                .thenReturn(Optional.of(connector(UUID.randomUUID(), null)));
        when(fixture.publisher.enqueueSync(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        fixture.scheduler.scheduleDue();

        ArgumentCaptor<ConnectorSyncTrigger> trigger = ArgumentCaptor.forClass(ConnectorSyncTrigger.class);
        verify(fixture.publisher).enqueueSync(any(), any(), any(), trigger.capture(), any(), any(), eq(NOW));
        assertThat(trigger.getValue()).isEqualTo(ConnectorSyncTrigger.SCHEDULE);
    }

    /**
     * 构造测试夹具。
     *
     * @return 夹具
     */
    private static Fixture fixture() {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        ConnectorEventPublisher publisher = mock(ConnectorEventPublisher.class);
        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), new OutboxLaneProperties());
        properties.setEnabled(true);
        properties.setSchedulerEnabled(true);
        properties.setSyncIntervalMinutes(60);
        when(repository.findDue(any(), anyInt())).thenReturn(List.of(due("tenant-a", UUID.randomUUID())));
        when(repository.claimDue(any(), any(), any(), any(), any())).thenReturn(true);
        when(repository.hasPendingSyncEvent(any(), any())).thenReturn(false);
        when(repository.findConnector(any(), any())).thenReturn(Optional.of(connector(null, null)));
        when(repository.findFirstActiveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        ConnectorSyncScheduler scheduler =
                new ConnectorSyncScheduler(repository, properties, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(scheduler, repository, publisher, properties);
    }

    /**
     * 构造到期项。
     *
     * @param tenant 租户
     * @param connectorId 连接器
     * @return 到期项
     */
    private static DueConnector due(String tenant, UUID connectorId) {
        return new DueConnector(tenant, connectorId, NOW.minusSeconds(60));
    }

    /**
     * 构造连接器。
     *
     * @param createdBy 创建人
     * @param deletedAt 删除时间
     * @return 连接器
     */
    private static ConnectorRecord connector(UUID createdBy, Instant deletedAt) {
        return new ConnectorRecord(
                UUID.randomUUID(),
                "connector",
                ConnectorType.S3,
                ConnectorStatus.ACTIVE,
                "endpoint",
                new ConnectorConfig("bucket", "prefix", "us-east-1", null, null),
                KnowledgeVisibility.TENANT,
                null,
                null,
                null,
                true,
                NOW,
                ConnectorSyncStatus.SUCCESS,
                createdBy,
                NOW,
                deletedAt);
    }

    /**
     * 测试夹具。
     *
     * @param scheduler 调度器
     * @param repository 仓储
     * @param publisher 发布器
     * @param properties 配置
     */
    private record Fixture(
            ConnectorSyncScheduler scheduler,
            ConnectorRepository repository,
            ConnectorEventPublisher publisher,
            ConnectorProperties properties) {}
}
