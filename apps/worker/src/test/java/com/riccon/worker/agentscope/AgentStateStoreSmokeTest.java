package com.riccon.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.agentscope.support.ApprovalEchoTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 冒烟 1：AgentScope 状态后端。
 *
 * <p>验证 {@link InMemoryAgentStateStore} 的保存、加载与删除，以及 {@link AgentState} 的 JSON
 * 序列化恢复；覆盖会话上下文、迭代计数与权限子状态，证明 M01 选定的 2.0.5 状态 API 可支撑 M02 的会话恢复。
 */
class AgentStateStoreSmokeTest {

    private static final String USER_ID = "user-state-smoke";
    private static final String SESSION_ID = "session-state-smoke";
    private static final String STATE_KEY = "agent_state";

    /** 内存状态存储保存后必须能按 (userId, sessionId) 原样加载，并在删除后不可见。 */
    @Test
    void inMemoryStore_saveThenLoad_roundTripsAgentState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AgentState original = buildState();

        store.save(USER_ID, SESSION_ID, STATE_KEY, original);

        assertThat(store.exists(USER_ID, SESSION_ID)).isTrue();
        assertThat(store.listSessionIds(USER_ID)).containsExactly(SESSION_ID);

        AgentState loaded =
                store.get(USER_ID, SESSION_ID, STATE_KEY, AgentState.class).orElseThrow();
        assertThat(loaded).isEqualTo(original);
        assertThat(loaded.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(loaded.getUserId()).isEqualTo(USER_ID);
        assertThat(loaded.getSummary()).isEqualTo("M01 状态后端冒烟");
        assertThat(loaded.getCurIter()).isEqualTo(2);
        assertThat(loaded.getContext()).hasSize(1);
        assertThat(loaded.getContext().get(0).getTextContent()).isEqualTo("请恢复这次会话");
        assertThat(loaded.getPermissionContext().getAskRules()).containsKey(ApprovalEchoTool.TOOL_NAME);

        store.delete(USER_ID, SESSION_ID);
        assertThat(store.exists(USER_ID, SESSION_ID)).isFalse();
    }

    /** AgentState 的 JSON 表示必须在反序列化后保留恢复所需字段与权限规则。 */
    @Test
    void agentState_jsonRoundTrip_preservesResumableFields() {
        AgentState restored = AgentState.fromJsonString(buildState().toJson());

        assertThat(restored.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(restored.getUserId()).isEqualTo(USER_ID);
        assertThat(restored.getSummary()).isEqualTo("M01 状态后端冒烟");
        assertThat(restored.getCurIter()).isEqualTo(2);
        assertThat(restored.getContext()).hasSize(1);
        assertThat(restored.getContext().get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(restored.getContext().get(0).getTextContent()).isEqualTo("请恢复这次会话");
        assertThat(restored.getPermissionContext().getAskRules()).containsKey(ApprovalEchoTool.TOOL_NAME);
    }

    /**
     * 构造包含消息、迭代计数与 ask 权限规则的最小可恢复状态，避免测试只覆盖空对象。
     *
     * @return 可序列化的 AgentState
     */
    private static AgentState buildState() {
        PermissionRule askRule =
                new PermissionRule(ApprovalEchoTool.TOOL_NAME, null, PermissionBehavior.ASK, "agentscope-smoke-test");
        PermissionContextState permissionContext = PermissionContextState.builder()
                .addAskRule(ApprovalEchoTool.TOOL_NAME, askRule)
                .build();
        Msg userMessage = Msg.builder()
                .id("msg-state-1")
                .name("user")
                .role(MsgRole.USER)
                .textContent("请恢复这次会话")
                .build();
        return AgentState.builder()
                .sessionId(SESSION_ID)
                .userId(USER_ID)
                .summary("M01 状态后端冒烟")
                .context(List.of(userMessage))
                .replyId("reply-state-1")
                .curIter(2)
                .permissionContext(permissionContext)
                .build();
    }
}
