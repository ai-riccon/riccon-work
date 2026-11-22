package com.riccon.worker.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 报表阶段首字观测：事件时间解析与可见文本口径的单元测试。 */
class ModelFirstTokenTest {

    /** 固定模型调用起点，避免依赖真实时间。 */
    private static final Instant START = Instant.parse("2026-11-22T10:00:00Z");

    /** 只有模型调用开始事件、没有文本增量时，首字为空。 */
    @Test
    void firstTokenMs_modelCallStartOnly_returnsNull() {
        AgentEvent event = modelCallStart("2026-11-22T10:00:00Z");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isNull();
    }

    /** TextBlockStartEvent 只表示文本块开始，不再计为可见首字（REV-TASK-0140-001 F-002）。 */
    @Test
    void firstTokenMs_textBlockStartOnly_returnsNull() {
        AgentEvent event = new TextBlockStartEvent("e-start", "2026-11-22T10:00:00.100Z", "r1", "b1");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isNull();
    }

    /** null、空串与纯空白增量都不是可见文本。 */
    @Test
    void firstTokenMs_blankDelta_returnsNull() {
        assertThat(ModelFirstToken.firstTokenMs(START, delta("2026-11-22T10:00:00.100Z", null)))
                .isNull();
        assertThat(ModelFirstToken.firstTokenMs(START, delta("2026-11-22T10:00:00.100Z", "")))
                .isNull();
        assertThat(ModelFirstToken.firstTokenMs(START, delta("2026-11-22T10:00:00.100Z", "   ")))
                .isNull();
    }

    /** 非空白增量按事件 createdAt 与模型调用起点的差返回毫秒。 */
    @Test
    void firstTokenMs_nonBlankDelta_returnsElapsedMillis() {
        AgentEvent event = delta("2026-11-22T10:00:00.250Z", "市场");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isEqualTo(250);
    }

    /** 没有模型调用起点时，即使有非空增量也无法计算首字。 */
    @Test
    void firstTokenMs_withoutModelCallStart_returnsNull() {
        AgentEvent event = delta("2026-11-22T10:00:00.250Z", "市场");

        assertThat(ModelFirstToken.firstTokenMs(null, event)).isNull();
    }

    /** 增量事件缺失 createdAt 时无法计算首字。 */
    @Test
    void firstTokenMs_deltaWithoutCreatedAt_returnsNull() {
        AgentEvent event = delta(null, "市场");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isNull();
    }

    /** 增量事件 createdAt 非法时无法计算首字。 */
    @Test
    void firstTokenMs_deltaWithInvalidCreatedAt_returnsNull() {
        AgentEvent event = delta("not-an-instant", "市场");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isNull();
    }

    /** 增量早于模型调用起点时按 0 计，避免负首字。 */
    @Test
    void firstTokenMs_deltaBeforeStart_clampsToZero() {
        AgentEvent event = delta("2026-11-22T09:59:59.500Z", "市场");

        assertThat(ModelFirstToken.firstTokenMs(START, event)).isZero();
    }

    /** 模型调用开始事件缺失 createdAt 或只有空白时解析为空。 */
    @Test
    void eventInstant_missingCreatedAt_returnsNull() {
        assertThat(ModelFirstToken.eventInstant(modelCallStart(null))).isNull();
        assertThat(ModelFirstToken.eventInstant(modelCallStart("  "))).isNull();
    }

    /** 模型调用开始事件 createdAt 非法时解析为空。 */
    @Test
    void eventInstant_invalidCreatedAt_returnsNull() {
        assertThat(ModelFirstToken.eventInstant(modelCallStart("2026-13-40T25:61:00Z")))
                .isNull();
    }

    /** 合法 ISO-8601 时间按 Instant 解析。 */
    @Test
    void eventInstant_isoInstant_parses() {
        assertThat(ModelFirstToken.eventInstant(modelCallStart("2026-11-22T10:00:00.250Z")))
                .isEqualTo(Instant.parse("2026-11-22T10:00:00.250Z"));
    }

    /**
     * 构造模型调用开始事件。
     *
     * @param createdAt 事件创建时间
     * @return 模型调用开始事件
     */
    private static ModelCallStartEvent modelCallStart(String createdAt) {
        return new ModelCallStartEvent("e-start", createdAt, "r1");
    }

    /**
     * 构造文本增量事件。
     *
     * @param createdAt 事件创建时间
     * @param text 增量文本
     * @return 文本增量事件
     */
    private static TextBlockDeltaEvent delta(String createdAt, String text) {
        return new TextBlockDeltaEvent("e-delta", createdAt, "r1", "b1", text);
    }
}
