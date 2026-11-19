package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.repository.AuditQueryFilter;
import com.riccon.api.audit.service.AuditInvalidRequestException;
import com.riccon.api.audit.service.AuditPagination;
import com.riccon.api.audit.service.AuditQueryFilters;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计过滤器解析与分页边界单元测试（不启动 Spring）。
 */
class AuditQueryFiltersTest {

    /** 固定当前时间，避免依赖系统时钟。 */
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");

    /** 默认窗口为最近 24 小时。 */
    @Test
    @DisplayName("缺省时间窗口为最近 24 小时并统一 +08:00")
    void resolve_defaultsToLast24Hours() {
        AuditQueryFilter filter = AuditQueryFilters.resolve(null, null, null, null, null, null, null, NOW);
        assertThat(filter.occurredTo().toInstant()).isEqualTo(NOW);
        assertThat(filter.occurredFrom().toInstant()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(filter.occurredFrom().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(filter.occurredTo().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(filter.action()).isNull();
        assertThat(filter.actorId()).isNull();
        assertThat(filter.targetType()).isNull();
        assertThat(filter.targetId()).isNull();
        assertThat(filter.outcome()).isNull();
    }

    /** 仅提供结束时间时，向前取 24 小时。 */
    @Test
    @DisplayName("仅提供 occurredTo 时向前取 24 小时")
    void resolve_onlyToUses24HourWindow() {
        AuditQueryFilter filter =
                AuditQueryFilters.resolve(null, "2026-11-18T00:00:00Z", null, null, null, null, null, NOW);
        assertThat(filter.occurredTo().toInstant()).isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
        assertThat(filter.occurredFrom().toInstant()).isEqualTo(Instant.parse("2026-11-17T00:00:00Z"));
    }

    /** 时间必须带偏移；Z 视为合法偏移并转换到 +08:00。 */
    @Test
    @DisplayName("时间必须带偏移，Z 合法并转换到 +08:00")
    void resolve_requiresOffsetAndNormalizes() {
        AuditQueryFilter filter =
                AuditQueryFilters.resolve("2026-11-01T00:00:00Z", null, null, null, null, null, null, NOW);
        assertThat(filter.occurredFrom().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(filter.occurredFrom().toInstant()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        assertThatThrownBy(
                        () -> AuditQueryFilters.resolve("2026-11-01T00:00:00", null, null, null, null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
    }

    /** 最大跨度 180 天。 */
    @Test
    @DisplayName("跨度超过 180 天拒绝")
    void resolve_rejectsRangeOver180Days() {
        assertThatThrownBy(() ->
                        AuditQueryFilters.resolve("2026-05-01T00:00:00Z", null, null, null, null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
    }

    /** 结束时间不得晚于服务器时间 5 分钟以上。 */
    @Test
    @DisplayName("occurredTo 超过 5 分钟时钟偏移拒绝")
    void resolve_rejectsFutureTo() {
        assertThatThrownBy(() ->
                        AuditQueryFilters.resolve(null, "2026-11-19T02:10:00Z", null, null, null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
        AuditQueryFilter allowed =
                AuditQueryFilters.resolve(null, "2026-11-19T02:04:00Z", null, null, null, null, null, NOW);
        assertThat(allowed.occurredTo().toInstant()).isEqualTo(Instant.parse("2026-11-19T02:04:00Z"));
    }

    /** 起始时间不得晚于结束时间。 */
    @Test
    @DisplayName("occurredFrom 晚于 occurredTo 拒绝")
    void resolve_rejectsInvertedRange() {
        assertThatThrownBy(() -> AuditQueryFilters.resolve(
                        "2026-11-19T01:00:00Z", "2026-11-19T00:00:00Z", null, null, null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
    }

    /** 枚举与 UUID 非法时拒绝；合法值被解析。 */
    @Test
    @DisplayName("actorId/targetType/outcome 非法拒绝，合法值解析")
    void resolve_parsesEnumsAndActor() {
        AuditQueryFilter filter = AuditQueryFilters.resolve(
                null,
                null,
                " file.uploaded ",
                "018f5c6a-1234-7abc-8def-0123456789ab",
                "FILE",
                "target-1",
                "DENIED",
                NOW);
        assertThat(filter.action()).isEqualTo("file.uploaded");
        assertThat(filter.targetType()).isEqualTo(AuditTargetType.FILE);
        assertThat(filter.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(filter.actorId().toString()).isEqualTo("018f5c6a-1234-7abc-8def-0123456789ab");

        assertThatThrownBy(() -> AuditQueryFilters.resolve(null, null, null, "not-a-uuid", null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditQueryFilters.resolve(null, null, null, null, "NOPE", null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditQueryFilters.resolve(null, null, null, null, null, null, "NOPE", NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditQueryFilters.resolve(null, null, "x".repeat(101), null, null, null, null, NOW))
                .isInstanceOf(AuditInvalidRequestException.class);
    }

    /** 分页上限：page 1–200，size 1–100，默认 20。 */
    @Test
    @DisplayName("分页默认值与硬上限")
    void pagination_enforcesLimits() {
        assertThat(AuditPagination.of(null, null)).isEqualTo(new AuditPagination(1, 20));
        assertThat(AuditPagination.of(200, 100).offset()).isEqualTo(19_900);
        assertThat(AuditPagination.of(1, 20).totalPages(0)).isZero();
        assertThat(AuditPagination.of(1, 20).totalPages(21)).isEqualTo(2);
        assertThatThrownBy(() -> AuditPagination.of(0, 20)).isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditPagination.of(201, 20)).isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditPagination.of(1, 0)).isInstanceOf(AuditInvalidRequestException.class);
        assertThatThrownBy(() -> AuditPagination.of(1, 101)).isInstanceOf(AuditInvalidRequestException.class);
    }
}
