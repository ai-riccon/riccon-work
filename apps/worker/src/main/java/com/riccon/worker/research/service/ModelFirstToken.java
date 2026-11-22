package com.riccon.worker.research.service;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 报表阶段首字观测。
 *
 * <p>AgentScope 的 {@code streamEvents} 会发出 {@link io.agentscope.core.event.ModelCallStartEvent}
 * 与文本块事件；这里使用事件自带的 {@code createdAt} 计算“模型调用开始 → 首个可见文本增量”的延迟，
 * 避免把 Agent 构建、队列等待与消费线程调度时间计入。工具调用不是可见首字
 *（ADR-0021 §1），因此仅返回 tool_calls 的 TOOL 阶段保持 null；若模型在工具调用前先输出
 * 文本，仍会记录首字（ADR-0022 §2.1/§2.3）。
 *
 * <p>可见首字只认非空且非纯空白的 {@link TextBlockDeltaEvent}；
 * {@link io.agentscope.core.event.TextBlockStartEvent} 只表示文本块开始，不代表已有可见内容，
 * 不得计入首字。本类保持无状态：调用方在首个非空结果出现后冻结，后续
 * {@code ModelCallStartEvent} 不得覆盖已记录的首字。
 */
final class ModelFirstToken {

    /** 工具类，禁止实例化。 */
    private ModelFirstToken() {}

    /**
     * 读取事件创建时间。
     *
     * @param event AgentScope 事件
     * @return 时间；缺失或格式非法时为空
     */
    static Instant eventInstant(AgentEvent event) {
        String createdAt = event.getCreatedAt();
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(createdAt);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * 计算首个可见文本增量相对模型调用起点的延迟。
     *
     * @param modelCallStartedAt 模型调用起点，可为空
     * @param event 当前事件
     * @return 毫秒；不是可见文本增量或时间戳缺失时为空；早于起点时按 0 计
     */
    static Integer firstTokenMs(Instant modelCallStartedAt, AgentEvent event) {
        if (modelCallStartedAt == null || !isVisibleText(event)) {
            return null;
        }
        Instant observedAt = eventInstant(event);
        if (observedAt == null) {
            return null;
        }
        return (int)
                Math.max(0, Duration.between(modelCallStartedAt, observedAt).toMillis());
    }

    /**
     * 判断事件是否为可见文本首字。
     *
     * @param event 事件
     * @return 仅当事件为非空且非纯空白 TextBlockDeltaEvent 时返回 true
     */
    private static boolean isVisibleText(AgentEvent event) {
        return event instanceof TextBlockDeltaEvent delta
                && delta.getDelta() != null
                && !delta.getDelta().isBlank();
    }
}
