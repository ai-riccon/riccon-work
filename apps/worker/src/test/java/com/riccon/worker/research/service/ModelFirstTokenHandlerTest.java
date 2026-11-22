package com.riccon.worker.research.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 两个报表 handler 首字事件循环的单元测试。
 *
 * <p>首字口径由 handler 的私有静态事件循环决定；这里通过反射驱动生产实现，既覆盖真实分支，
 * 又不为测试放宽生产可见性。事件 createdAt 全部固定，不依赖真实时钟。
 */
class ModelFirstTokenHandlerTest {

    /** 只有模型调用开始、没有文本增量时，GENERATION 不记录首字。 */
    @Test
    void awaitGeneration_modelCallStartOnly_returnsNullFirstToken() throws ReflectiveOperationException {
        assertThat(generationFirstToken(modelCallStart("2026-11-22T10:00:00Z"), agentEnd()))
                .isNull();
    }

    /** 只有模型调用开始、没有文本增量时，TOOL 不记录首字。 */
    @Test
    void awaitApprovalRequest_modelCallStartOnly_returnsNullFirstToken() throws ReflectiveOperationException {
        assertThat(approvalFirstToken(modelCallStart("2026-11-22T10:00:00Z"), agentEnd()))
                .isNull();
    }

    /** 先 tool-only 调用、再第二次模型调用输出文本时，从第二次调用开始计。 */
    @Test
    void awaitGeneration_toolOnlyStartThenText_usesSecondModelCallStart() throws ReflectiveOperationException {
        assertThat(generationFirstToken(
                        modelCallStart("2026-11-22T10:00:00Z"),
                        modelCallStart("2026-11-22T10:00:05Z"),
                        textDelta("2026-11-22T10:00:05.250Z", "市场"),
                        agentEnd()))
                .isEqualTo(250);
    }

    /** TOOL 阶段先 tool-only 调用、再文本输出时，同样从第二次调用开始计。 */
    @Test
    void awaitApprovalRequest_toolOnlyStartThenText_usesSecondModelCallStart() throws ReflectiveOperationException {
        assertThat(approvalFirstToken(
                        modelCallStart("2026-11-22T10:00:00Z"),
                        modelCallStart("2026-11-22T10:00:05Z"),
                        textDelta("2026-11-22T10:00:05.250Z", "市场"),
                        agentEnd()))
                .isEqualTo(250);
    }

    /** 首字已记录后再次出现模型调用开始，保留第一个值而不是重新计时。 */
    @Test
    void awaitGeneration_textThenSecondStart_keepsFirstToken() throws ReflectiveOperationException {
        assertThat(generationFirstToken(
                        modelCallStart("2026-11-22T10:00:00Z"),
                        textDelta("2026-11-22T10:00:00.100Z", "市场"),
                        modelCallStart("2026-11-22T10:00:05Z"),
                        textDelta("2026-11-22T10:00:05.250Z", "继续"),
                        agentEnd()))
                .isEqualTo(100);
    }

    /** TOOL 阶段首字已记录后再次出现模型调用开始，同样保留第一个值。 */
    @Test
    void awaitApprovalRequest_textThenSecondStart_keepsFirstToken() throws ReflectiveOperationException {
        assertThat(approvalFirstToken(
                        modelCallStart("2026-11-22T10:00:00Z"),
                        textDelta("2026-11-22T10:00:00.100Z", "市场"),
                        modelCallStart("2026-11-22T10:00:05Z"),
                        textDelta("2026-11-22T10:00:05.250Z", "继续"),
                        agentEnd()))
                .isEqualTo(100);
    }

    /**
     * 驱动生成阶段事件循环并读取首字。
     *
     * @param events 事件序列
     * @return 首字毫秒；没有可见文本时为空
     * @throws ReflectiveOperationException 反射调用失败
     */
    private static Integer generationFirstToken(AgentEvent... events) throws ReflectiveOperationException {
        Method method = AgentRunResumeHandler.class.getDeclaredMethod("awaitGeneration", Iterator.class);
        method.setAccessible(true);
        Object result = method.invoke(null, List.of(events).iterator());
        return firstTokenMs(result);
    }

    /**
     * 驱动工具阶段事件循环并读取首字。
     *
     * @param events 事件序列
     * @return 首字毫秒；没有可见文本时为空
     * @throws ReflectiveOperationException 反射调用失败
     */
    private static Integer approvalFirstToken(AgentEvent... events) throws ReflectiveOperationException {
        Method method = AgentRunRequestedHandler.class.getDeclaredMethod("awaitApprovalRequest", Iterator.class);
        method.setAccessible(true);
        Object result = method.invoke(null, List.of(events).iterator());
        return firstTokenMs(result);
    }

    /**
     * 读取 handler 结果记录中的首字字段。
     *
     * @param result 私有记录实例
     * @return 首字毫秒；没有可见文本时为空
     * @throws ReflectiveOperationException 反射调用失败
     */
    private static Integer firstTokenMs(Object result) throws ReflectiveOperationException {
        Method accessor = result.getClass().getDeclaredMethod("firstTokenMs");
        accessor.setAccessible(true);
        return (Integer) accessor.invoke(result);
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
    private static TextBlockDeltaEvent textDelta(String createdAt, String text) {
        return new TextBlockDeltaEvent("e-delta", createdAt, "r1", "b1", text);
    }

    /**
     * 构造 Agent 结束事件；事件时间不参与首字计算。
     *
     * @return Agent 结束事件
     */
    private static AgentEndEvent agentEnd() {
        return new AgentEndEvent("r1");
    }
}
