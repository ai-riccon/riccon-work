package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.OutboxLane;
import com.riccon.worker.run.persistence.OutboxClaimer;
import com.riccon.worker.support.TestDocuments;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbox 通道领取与租约续期集成测试（ADR-0023 第 7.3 节）。
 *
 * <p>测试放在 {@code com.riccon.worker.outbox}，避免修改并行任务拥有的 worker run 测试目录；
 * 使用共享一次性容器与唯一租户，验证：通道过滤只领取本通道事件、空通道不退化为领取全部、
 * 续期只允许当前租约持有者。
 */
class OutboxLaneClaimIntegrationTest extends WorkerIntegrationTestBase {

    /** 被测领取器。 */
    @Autowired
    private OutboxClaimer outboxClaimer;

    /** 每个测试先清空残留 outbox，保证精确计数。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** 默认与重通道互不领取对方事件。 */
    @Test
    @DisplayName("通道过滤只领取本通道事件")
    void claim_laneFilter_returnsOnlyOwnEvents() {
        Published published = publishMixedEvents("lane-filter");

        List<ClaimedOutboxEvent> defaultEvents =
                outboxClaimer.claim("owner-default", 10, Duration.ofSeconds(30), OutboxLane.DEFAULT.eventTypes());
        assertThat(defaultEvents)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.eventType()).isEqualTo(EventTypes.AGENT_RUN_REQUESTED));

        List<ClaimedOutboxEvent> heavyEvents =
                outboxClaimer.claim("owner-heavy", 10, Duration.ofSeconds(30), OutboxLane.HEAVY.eventTypes());
        assertThat(heavyEvents)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.eventType()).isEqualTo(EventTypes.FILE_PARSE_REQUESTED));

        assertThat(outboxStatus(published.defaultEventId())).isEqualTo("DISPATCHED");
        assertThat(outboxStatus(published.heavyEventId())).isEqualTo("DISPATCHED");
    }

    /** 空事件集合不能退化为“领取全部”，否则重通道会吞掉 chat/report。 */
    @Test
    @DisplayName("空通道过滤不领取任何事件")
    void claim_emptyFilter_claimsNothing() {
        Published published = publishMixedEvents("lane-empty");

        assertThat(outboxClaimer.claim("owner-empty", 10, Duration.ofSeconds(30), List.of()))
                .isEmpty();
        assertThat(outboxStatus(published.defaultEventId())).isEqualTo("PENDING");
        assertThat(outboxStatus(published.heavyEventId())).isEqualTo("PENDING");
    }

    /** 续期只允许当前租约持有者；成功后续期时间延后。 */
    @Test
    @DisplayName("租约续期只允许当前持有者")
    void renew_onlyCurrentOwnerExtendsLease() {
        Published published = publishMixedEvents("lane-renew");
        ClaimedOutboxEvent event = outboxClaimer
                .claim("owner-renew", 10, Duration.ofSeconds(30), OutboxLane.DEFAULT.eventTypes())
                .get(0);
        LocalDateTime before = leaseUntil(event.id());

        assertThat(outboxClaimer.renew(event.tenantId(), event.id(), "other-owner", Duration.ofSeconds(60)))
                .isZero();
        assertThat(outboxClaimer.renew(event.tenantId(), event.id(), "owner-renew", Duration.ofSeconds(60)))
                .isEqualTo(1);
        assertThat(leaseUntil(event.id())).isAfter(before);

        // 续期不改变状态与 attempts。
        assertThat(outboxStatus(event.id())).isEqualTo("DISPATCHED");
        assertThat(outboxAttempts(event.id())).isEqualTo(1);
    }

    /**
     * 发布一条默认通道事件与一条重通道事件。
     *
     * @param tenantPrefix 租户前缀
     * @return 事件 ID
     */
    private Published publishMixedEvents(String tenantPrefix) {
        String tenant = uniqueTenant(tenantPrefix);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        // 通道领取只校验 agent_run 外键与租约，不要求事件类型与运行类别匹配；用
        // KNOWLEDGE_INDEX（允许 file_id 为空）构造默认通道事件的运行，避免额外的报告任务夹具。
        UUID defaultRunId = data.createRun(tenant, RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        UUID defaultEventId = data.createOutbox(
                tenant, defaultRunId, EventTypes.AGENT_RUN_REQUESTED, "{}", "run:" + defaultRunId + ":requested");

        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(1));
        UUID parseId = data.createParse(tenant, fileId, 1);
        UUID heavyEventId =
                data.publishFileParse(tenant, fileId, parseId, userId, 1).eventId();
        return new Published(defaultEventId, heavyEventId);
    }

    /**
     * 查询事件状态。
     *
     * @param eventId 事件 ID
     * @return 状态
     */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /**
     * 查询租约到期时间。
     *
     * @param eventId 事件 ID
     * @return UTC 时间
     */
    private LocalDateTime leaseUntil(UUID eventId) {
        return jdbcClient
                .sql("SELECT lease_until FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(LocalDateTime.class)
                .single();
    }

    /**
     * 查询领取次数。
     *
     * @param eventId 事件 ID
     * @return attempts
     */
    private int outboxAttempts(UUID eventId) {
        return jdbcClient
                .sql("SELECT attempts FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(Integer.class)
                .single();
    }

    /**
     * 测试内部事件包装。
     *
     * @param defaultEventId 默认通道事件
     * @param heavyEventId 重通道事件
     */
    private record Published(UUID defaultEventId, UUID heavyEventId) {}
}
