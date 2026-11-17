package com.riccon.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.agentscope.support.ApprovalEchoTool;
import com.riccon.worker.agentscope.support.FakeChatModel;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 冒烟 4：权限引擎的 allow/ask/deny 三态与人工审批（HITL）暂停恢复。
 *
 * <p>先直接验证 {@link PermissionEngine} 的三态判定，再用 {@link ReActAgent#streamEvents} 触发 ask 规则：
 * 第一段流应停在 {@link RequireUserConfirmEvent} 且工具未执行；回填 {@link ConfirmResult} 后第二段流应执行工具、
 * 重新调用模型并正常结束。
 */
class HitlPermissionSmokeTest {

    private static final String USER_ID = "user-hitl-smoke";
    private static final String SESSION_ID = "session-hitl-smoke";
    private static final String TOOL_CALL_ID = "call-hitl-1";
    private static final String INPUT_TEXT = "ping";

    /** 权限引擎必须按规则返回 ALLOW、ASK、DENY 三态。 */
    @Test
    void permissionEngine_returnsAllowAskAndDeny() {
        ApprovalEchoTool tool = new ApprovalEchoTool();
        Map<String, Object> input = Map.of("text", INPUT_TEXT);

        assertThat(decisionFor(PermissionBehavior.ALLOW, tool, input)).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decisionFor(PermissionBehavior.ASK, tool, input)).isEqualTo(PermissionBehavior.ASK);
        assertThat(decisionFor(PermissionBehavior.DENY, tool, input)).isEqualTo(PermissionBehavior.DENY);
    }

    /** ask 规则使 Agent 挂起，批准后工具只执行一次并完成最终回答。 */
    @Test
    @Timeout(90)
    void askPermission_agentSuspendsAndResumesAfterConfirmation() {
        ApprovalEchoTool tool = new ApprovalEchoTool();
        FakeChatModel model = new FakeChatModel(List.of(List.of(toolCallResponse()), List.of(textResponse("批准后完成"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);

        ReActAgent agent = ReActAgent.builder()
                .name("hitl-smoke-agent")
                .sysPrompt("你是 Riccon HITL 冒烟测试 Agent")
                .model(model)
                .toolkit(toolkit)
                .maxIters(4)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissionContext(PermissionBehavior.ASK))
                .build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();

        try {
            List<AgentEvent> suspendedEvents = agent.streamEvents(
                            List.of(new UserMessage("请执行 approval_echo")), runtimeContext)
                    .collectList()
                    .block(Duration.ofSeconds(30));
            assertThat(suspendedEvents).isNotNull();

            RequireUserConfirmEvent confirmEvent = suspendedEvents.stream()
                    .filter(RequireUserConfirmEvent.class::isInstance)
                    .map(RequireUserConfirmEvent.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未收到 RequireUserConfirmEvent"));
            assertThat(confirmEvent.getToolCalls()).hasSize(1);
            ToolUseBlock pendingCall = confirmEvent.getToolCalls().get(0);
            assertThat(pendingCall.getId()).isEqualTo(TOOL_CALL_ID);
            assertThat(pendingCall.getName()).isEqualTo(ApprovalEchoTool.TOOL_NAME);
            assertThat(pendingCall.getInput()).containsEntry("text", INPUT_TEXT);
            assertThat(tool.executionCount()).isZero();

            AgentResultEvent pausedResult = lastResultEvent(suspendedEvents);
            assertThat(pausedResult.getResult().getGenerateReason()).isEqualTo(GenerateReason.PERMISSION_ASKING);

            AgentState pausedState = agent.getAgentState(USER_ID, SESSION_ID);
            assertThat(pausedState.getContext().stream()
                            .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                            .filter(block -> ApprovalEchoTool.TOOL_NAME.equals(block.getName()))
                            .map(ToolUseBlock::getState))
                    .containsExactly(ToolCallState.ASKING);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, pendingCall)));
            Msg resumeMessage = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent("批准执行")
                    .metadata(metadata)
                    .build();

            List<AgentEvent> resumedEvents = agent.streamEvents(List.of(resumeMessage), runtimeContext)
                    .collectList()
                    .block(Duration.ofSeconds(30));
            assertThat(resumedEvents).isNotNull();
            assertThat(resumedEvents)
                    .anySatisfy(event -> assertThat(event.getType()).isEqualTo(AgentEventType.USER_CONFIRM_RESULT));
            assertThat(resumedEvents.stream()
                            .filter(UserConfirmResultEvent.class::isInstance)
                            .count())
                    .isEqualTo(1);
            ToolResultEndEvent toolResultEnd = resumedEvents.stream()
                    .filter(ToolResultEndEvent.class::isInstance)
                    .map(ToolResultEndEvent.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("批准后未收到工具结果结束事件"));
            assertThat(toolResultEnd.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(tool.executionCount()).isEqualTo(1);

            AgentResultEvent finalResult = lastResultEvent(resumedEvents);
            assertThat(finalResult.getResult().getTextContent()).isEqualTo("批准后完成");
            assertThat(finalResult.getResult().getGenerateReason()).isEqualTo(GenerateReason.MODEL_STOP);
            assertThat(model.invocationCount()).isEqualTo(2);
        } finally {
            agent.close();
        }
    }

    /** 构造单一规则键的权限上下文；行为决定注册到 allow/ask/deny 哪张表。 */
    private static PermissionContextState permissionContext(PermissionBehavior behavior) {
        PermissionRule rule = new PermissionRule(ApprovalEchoTool.TOOL_NAME, null, behavior, "hitl-smoke-test");
        PermissionContextState.Builder builder = PermissionContextState.builder();
        switch (behavior) {
            case ALLOW -> builder.addAllowRule(ApprovalEchoTool.TOOL_NAME, rule);
            case ASK -> builder.addAskRule(ApprovalEchoTool.TOOL_NAME, rule);
            case DENY -> builder.addDenyRule(ApprovalEchoTool.TOOL_NAME, rule);
            case PASSTHROUGH -> {
                // PASSTHROUGH 不注册规则，本测试不使用。
            }
        }
        return builder.build();
    }

    /** 通过权限引擎解析一次工具调用的行为。 */
    private static PermissionBehavior decisionFor(
            PermissionBehavior behavior, ApprovalEchoTool tool, Map<String, Object> input) {
        PermissionDecision decision = new PermissionEngine(permissionContext(behavior))
                .checkPermission(tool, input)
                .block(Duration.ofSeconds(5));
        assertThat(decision).isNotNull();
        return decision.getBehavior();
    }

    /** 构造模型返回的单个工具调用响应块。 */
    private static ChatResponse toolCallResponse() {
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id(TOOL_CALL_ID)
                .name(ApprovalEchoTool.TOOL_NAME)
                .input(Map.of("text", INPUT_TEXT))
                .build();
        return ChatResponse.builder()
                .id("hitl-response-1")
                .content(List.of(toolCall))
                .finishReason("tool_calls")
                .build();
    }

    /** 构造模型返回的纯文本响应块。 */
    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("hitl-response-2")
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }

    /** 取流中最后一个 AgentResultEvent，缺失时以明确断言失败。 */
    private static AgentResultEvent lastResultEvent(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("事件流中缺少 AgentResultEvent"));
    }
}
