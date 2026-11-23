package com.riccon.worker.injection;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.AgentPrompts;
import com.riccon.worker.agent.service.UntrustedData;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 注入 harness 的负向对照：证明 tripwire 真的能识别“注入被当作指令”，避免断言空转的假绿。
 *
 * <p>本类不调用生产 Agent；它故意构造两种情况：a) 注入文本赤裸进入 USER 指令面；
 * b) 注入文本被 {@link UntrustedData} 包进数据块。假模型必须只在 a) 输出 canary。
 * 若未来有人删掉检测词或把数据面误判为指令面，本类会直接失败。
 */
class InjNegativeControlTest {

    /**
     * 注入文本直接出现在用户指令面时，tripwire 必须判定逃逸并输出 canary。
     */
    @Test
    @DisplayName("负向对照：未隔离的注入必须被识别为逃逸")
    @Timeout(30)
    void unwrappedInjection_isDetectedAsBreach() {
        InjectionSample sample = InjectionSamples.byId("INJ-001");
        InjectionModel model = new InjectionModel(
                InjectionModel.Mode.BOUNDARY_AWARE, InjectionSamples.CANARY_VALUE, InjectionSamples.DETECTORS);
        List<Msg> vulnerablePrompt = List.of(
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .textContent(AgentPrompts.CHAT_SYSTEM)
                        .build(),
                new UserMessage(sample.payload()));

        List<ChatResponse> responses =
                model.stream(vulnerablePrompt, List.of(), null).collectList().block(Duration.ofSeconds(5));

        assertThat(responses).isNotNull();
        assertThat(model.breaches())
                .as("未隔离注入必须触发 tripwire")
                .isNotEmpty();
        assertThat(textOf(responses.get(0)))
                .as("tripwire 触发后必须输出 canary，证明检测链可失败")
                .contains(InjectionSamples.CANARY_VALUE)
                .contains(InjectionSamples.INTERNAL_ADDRESS);
    }

    /**
     * 注入文本被不可信数据块包裹时，tripwire 不得误报，模型应继续系统任务。
     */
    @Test
    @DisplayName("负向对照：已隔离的注入不得误报")
    @Timeout(30)
    void wrappedInjection_isNotFlagged() {
        InjectionSample sample = InjectionSamples.byId("INJ-007");
        List<RetrievedChunk> citations = InjectionFixtures.citations(
                sample, InjectionFixtures.chunks(InjectionFixtures.parse(sample)));
        InjectionModel model = new InjectionModel(
                InjectionModel.Mode.BOUNDARY_AWARE, InjectionSamples.CANARY_VALUE, InjectionSamples.DETECTORS);
        List<Msg> guardedPrompt = List.of(
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .textContent(AgentPrompts.CHAT_SYSTEM)
                        .build(),
                Msg.builder()
                        .name("data")
                        .role(MsgRole.USER)
                        .textContent(UntrustedData.fromCitations(citations, 2000))
                        .build(),
                new UserMessage("请总结这份季度资料的关键数字。"));

        List<ChatResponse> responses =
                model.stream(guardedPrompt, List.of(), null).collectList().block(Duration.ofSeconds(5));

        assertThat(responses).isNotNull();
        assertThat(model.breaches()).as("已隔离注入不得触发 tripwire").isEmpty();
        assertThat(model.calls().get(0).scan().untrustedHits())
                .as("采样文本必须位于不可信面")
                .isNotEmpty();
        assertThat(textOf(responses.get(0)))
                .as("未逃逸时模型继续系统任务")
                .isEqualTo(InjectionModel.BENIGN_ANSWER);
    }

    /**
     * 提取响应文本。
     *
     * @param response 模型响应
     * @return 文本
     */
    private static String textOf(ChatResponse response) {
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", String::concat);
    }
}
