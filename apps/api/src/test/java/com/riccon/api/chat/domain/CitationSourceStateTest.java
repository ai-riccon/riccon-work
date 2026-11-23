package com.riccon.api.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用可用性状态计算与快照复制规则。
 */
class CitationSourceStateTest {

    /** 可见文档按版本快照区分 AVAILABLE 与 UPDATED。 */
    @Test
    @DisplayName("状态：版本一致 AVAILABLE、版本不同 UPDATED、历史空版本 AVAILABLE")
    void statusFor_comparesSourceVersion() {
        CitationSourceState state = new CitationSourceState(true, false, "a".repeat(64));

        assertThat(state.statusFor("a".repeat(64))).isEqualTo(CitationSourceStatus.AVAILABLE);
        assertThat(state.statusFor("b".repeat(64))).isEqualTo(CitationSourceStatus.UPDATED);
        assertThat(state.statusFor(null)).isEqualTo(CitationSourceStatus.AVAILABLE);
    }

    /** 优先级固定为 NO_ACCESS > DELETED > UPDATED > AVAILABLE。 */
    @Test
    @DisplayName("状态：NO_ACCESS 优先于 DELETED")
    void statusFor_priority() {
        assertThat(new CitationSourceState(false, true, null).statusFor(null))
                .isEqualTo(CitationSourceStatus.NO_ACCESS);
        assertThat(new CitationSourceState(false, false, "x").statusFor("y")).isEqualTo(CitationSourceStatus.NO_ACCESS);
        assertThat(new CitationSourceState(true, true, "x").statusFor("y")).isEqualTo(CitationSourceStatus.DELETED);
        assertThat(CitationSourceState.denied().statusFor(null)).isEqualTo(CitationSourceStatus.NO_ACCESS);
    }

    /** 引用快照只允许知识库来源携带版本。 */
    @Test
    @DisplayName("引用快照：FILE 引用不得写 sourceVersion")
    void citationRecord_rejectsVersionForFileCitation() {
        assertThatThrownBy(() -> citation(CitationSourceType.FILE, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceVersion");
    }

    /** 复制引用只替换可用性，库内快照字段保持不变。 */
    @Test
    @DisplayName("引用快照：withSourceStatus 保留快照字段")
    void citationRecord_withSourceStatusKeepsSnapshot() {
        CitationRecord original = citation(CitationSourceType.KNOWLEDGE, null);

        CitationRecord masked = original.withSourceStatus(CitationSourceStatus.NO_ACCESS);

        assertThat(masked.title()).isEqualTo(original.title());
        assertThat(masked.snippet()).isEqualTo(original.snippet());
        assertThat(masked.sourceStatus()).isEqualTo(CitationSourceStatus.NO_ACCESS);
        assertThat(masked.sourceVersion()).isNull();
    }

    /**
     * 构造知识库引用。
     *
     * @param sourceType 来源类型
     * @param sourceVersion 版本快照
     * @return 引用
     */
    private static CitationRecord citation(CitationSourceType sourceType, String sourceVersion) {
        UUID documentId = UUID.randomUUID();
        return new CitationRecord(
                UUID.randomUUID(),
                1,
                sourceType,
                sourceType == CitationSourceType.FILE ? UUID.randomUUID() : null,
                sourceType == CitationSourceType.KNOWLEDGE ? documentId : null,
                UUID.randomUUID(),
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                1,
                "制度",
                "片段",
                new BigDecimal("0.5"),
                Instant.parse("2026-11-23T00:00:00Z"),
                sourceVersion,
                CitationSourceStatus.AVAILABLE);
    }
}
