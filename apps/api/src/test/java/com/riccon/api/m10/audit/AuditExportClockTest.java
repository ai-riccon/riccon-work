package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * IT-AUDIT-104/106：Clock 与短 TTL 过期、导出行数上限。
 *
 * <p>用 {@code riccon.audit.export.ttl=PT2S} 与可变 Clock 验证过期，不使用 {@code Thread.sleep}；
 * 同一上下文把行数上限调为 3，验证预计行数超过上限返回 400。
 */
@TestPropertySource(properties = {"riccon.audit.export.ttl=PT2S", "riccon.audit.export.row-limit=3"})
@Import(AuditExportClockTest.ClockConfig.class)
class AuditExportClockTest extends AuditTestSupport {

    /** 测试时钟；每个用例前重置到当前时间。 */
    private static final MutableClock CLOCK = new MutableClock(Instant.now());

    /**
     * 每个用例重置时钟，避免用例顺序影响过期判断。
     */
    @BeforeEach
    void resetClock() {
        CLOCK.setInstant(Instant.now());
    }

    /**
     * 短 TTL：批准后可下载，推进 3 秒后 410，状态派生 EXPIRED。
     */
    @Test
    @DisplayName("IT-AUDIT-104 Clock 短 TTL：批准后可下载，过期 410")
    void download_shortTtlExpiresWithClock() {
        TenantFixture fixture = createFixture("at");
        LoginSession approver = createSecondAdmin(fixture);
        insertAuditEvent(
                fixture.tenantId(),
                Instant.now().minusSeconds(30),
                "USER",
                fixture.tenantAdminId().toString(),
                "file.uploaded",
                "FILE",
                "file-ttl",
                "SUCCESS",
                "{\"fileId\":\"file-ttl\"}");
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));

        JsonNode approved = client.json(decide(approver, exportId, "APPROVE", null));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        OffsetDateTime decidedAt =
                OffsetDateTime.parse(approved.path("decidedAt").asText());
        OffsetDateTime expiresAt =
                OffsetDateTime.parse(approved.path("expiresAt").asText());
        assertThat(Duration.between(decidedAt, expiresAt)).isEqualTo(Duration.ofSeconds(2));

        ApiTestClient.ApiResponse beforeExpiry = download(fixture.tenantAdmin(), exportId);
        assertThat(beforeExpiry.status()).as(beforeExpiry.body()).isEqualTo(200);

        CLOCK.setInstant(CLOCK.instant().plusSeconds(3));

        ApiTestClient.ApiResponse expired = download(fixture.tenantAdmin(), exportId);
        assertThat(expired.status()).as(expired.body()).isEqualTo(410);
        assertCode(expired, "RICCON-AUDIT-410");

        JsonNode detail = client.json(client.get(
                "/api/v1/audit-exports/" + exportId, fixture.tenantAdmin().sessionCookie(), java.util.Map.of()));
        assertThat(detail.path("status").asText()).isEqualTo("EXPIRED");

        JsonNode expiredList = client.json(client.get(
                "/api/v1/audit-exports?status=EXPIRED", fixture.tenantAdmin().sessionCookie(), java.util.Map.of()));
        assertThat(expiredList.path("total").asLong()).isEqualTo(1);
        assertThat(expiredList.path("items").get(0).path("status").asText()).isEqualTo("EXPIRED");

        JsonNode approvedList = client.json(client.get(
                "/api/v1/audit-exports?status=APPROVED", fixture.tenantAdmin().sessionCookie(), java.util.Map.of()));
        assertThat(approvedList.path("total").asLong()).isZero();

        // 过期下载不写成功审计；此前只有一次成功下载。
        assertThat(tenantAuditRows(fixture.tenantId(), "data.exported")).hasSize(1);
    }

    /**
     * 预计行数超过配置上限返回 400；等于上限允许。
     */
    @Test
    @DisplayName("IT-AUDIT-104 预计行数超过上限 400，等于上限允许")
    void createExport_rejectsOverConfiguredRowLimit() {
        TenantFixture fixture = createFixture("at");
        for (int index = 0; index < 4; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    Instant.now().minusSeconds(10 + index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.uploaded",
                    "FILE",
                    "file-" + index,
                    "SUCCESS",
                    "{\"fileId\":\"file-" + index + "\"}");
        }

        ApiTestClient.ApiResponse overLimit =
                createExport(fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));
        assertThat(overLimit.status()).as(overLimit.body()).isEqualTo(400);
        assertCode(overLimit, "RICCON-AUDIT-400");

        for (int index = 0; index < 3; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    Instant.now().minusSeconds(20 + index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.deleted",
                    "FILE",
                    "deleted-" + index,
                    "SUCCESS",
                    null);
        }
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.deleted", null, null, null, null));
        JsonNode body = client.json(client.get(
                "/api/v1/audit-exports/" + exportId, fixture.tenantAdmin().sessionCookie(), java.util.Map.of()));
        assertThat(body.path("estimatedRowCount").asInt()).isEqualTo(3);
        Instant occurredTo = OffsetDateTime.parse(
                        body.path("filter").path("occurredTo").asText())
                .toInstant();

        // 批准后新增一条匹配事件（落在固化的 occurredTo 之前），下载前重新计数 4 > 3，必须 409。
        LoginSession approver = createSecondAdmin(fixture);
        assertThat(decide(approver, exportId, "APPROVE", null).status()).isEqualTo(200);
        insertAuditEvent(
                fixture.tenantId(),
                occurredTo.minusSeconds(1),
                "USER",
                fixture.tenantAdminId().toString(),
                "file.deleted",
                "FILE",
                "deleted-late",
                "SUCCESS",
                null);
        ApiTestClient.ApiResponse overflow = download(fixture.tenantAdmin(), exportId);
        assertThat(overflow.status()).as(overflow.body()).isEqualTo(409);
        assertCode(overflow, "RICCON-AUDIT-409");

        JsonNode policy = client.json(
                client.get("/api/v1/audit-events/policy", fixture.tenantAdmin().sessionCookie(), java.util.Map.of()));
        assertThat(policy.path("exportLimits").path("rowLimit").asInt()).isEqualTo(3);
        assertThat(policy.path("exportLimits").path("ttlHours").asInt()).isEqualTo(1);
    }

    /** 可变时钟；测试通过推进时间验证 TTL，不依赖系统 sleep。 */
    private static final class MutableClock extends Clock {

        /** 当前时间。 */
        private volatile Instant instant;

        /**
         * 创建可变时钟。
         *
         * @param instant 初始时间
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 设置当前时间。
         *
         * @param instant 新时间
         */
        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock shifted = new MutableClock(instant);
            return shifted;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** 测试配置：用可变时钟替换系统时钟。 */
    @TestConfiguration
    static class ClockConfig {

        /**
         * 主时钟 Bean。
         *
         * @return 可变时钟
         */
        @Bean
        @Primary
        Clock auditTestClock() {
            return CLOCK;
        }
    }
}
