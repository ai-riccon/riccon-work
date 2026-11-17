package com.riccon.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.agentscope.support.FakeChatModel;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 冒烟 5：假 ChatModel 的流式事件。
 *
 * <p>固定输出两段文本，调用 {@link ReActAgent#streamEvents} 后断言事件类型序列完全稳定，
 * 并校验文本 delta 拼接结果与最终结果一致；不接触真实模型，也不依赖 sleep。
 */
class StreamingEventsSmokeTest {

    private static final String FIRST_CHUNK = "Hello, ";
    private static final String SECOND_CHUNK = "Riccon!";

    /** 两个文本块应产生 start → delta ×2 → end，并以 AgentResult/AgentEnd 收尾。 */
    @Test
    @Timeout(60)
    void streamEvents_textChunks_emitStableOrderAndComplete() {
        FakeChatModel model = new FakeChatModel(List.of(List.of(
                textChunk("stream-response-1", FIRST_CHUNK, null),
                textChunk("stream-response-1", SECOND_CHUNK, "stop"))));
        ReActAgent agent = ReActAgent.builder()
                .name("stream-smoke-agent")
                .sysPrompt("你是 Riccon 流式冒烟测试 Agent")
                .model(model)
                .maxIters(1)
                .build();

        try {
            List<AgentEvent> events = agent.streamEvents("请流式回答").collectList().block(Duration.ofSeconds(20));
            assertThat(events).isNotNull();
            assertThat(events.stream().map(AgentEvent::getType).toList())
                    .containsExactly(
                            AgentEventType.AGENT_START,
                            AgentEventType.MODEL_CALL_START,
                            AgentEventType.TEXT_BLOCK_START,
                            AgentEventType.TEXT_BLOCK_DELTA,
                            AgentEventType.TEXT_BLOCK_DELTA,
                            AgentEventType.TEXT_BLOCK_END,
                            AgentEventType.MODEL_CALL_END,
                            AgentEventType.AGENT_RESULT,
                            AgentEventType.AGENT_END);

            String streamedText = events.stream()
                    .filter(TextBlockDeltaEvent.class::isInstance)
                    .map(TextBlockDeltaEvent.class::cast)
                    .map(TextBlockDeltaEvent::getDelta)
                    .collect(Collectors.joining());
            assertThat(streamedText).isEqualTo(FIRST_CHUNK + SECOND_CHUNK);

            AgentResultEvent resultEvent = events.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("事件流中缺少 AgentResultEvent"));
            assertThat(resultEvent.getResult().getTextContent()).isEqualTo(FIRST_CHUNK + SECOND_CHUNK);
            assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEndEvent.class);
            assertThat(model.invocationCount()).isEqualTo(1);
        } finally {
            agent.close();
        }
    }

    /** 构造单个文本块响应；finishReason 仅最后一个块需要。 */
    private static ChatResponse textChunk(String responseId, String text, String finishReason) {
        return ChatResponse.builder()
                .id(responseId)
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(finishReason)
                .build();
    }
}
