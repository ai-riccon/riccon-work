package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecutor;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 节点执行器分派。
 *
 * <p>同一类型重复注册启动失败；缺失类型的节点在运行入口明确失败 {@code WF_INTERNAL}，
 * 不静默跳过或卡在 RUNNING（T07a/T07b 落地前的预期行为）。
 */
class WorkflowNodeDispatcherTest {

    /** 正常分派到对应执行器。 */
    @Test
    @DisplayName("按节点类型分派执行器")
    void execute_dispatchesByType() {
        WorkflowNodeDispatcher dispatcher = new WorkflowNodeDispatcher(
                List.of(new StartNodeExecutor(new WorkflowTemplateRenderer()), stub(WorkflowNodeType.MODEL)));

        WorkflowNodeResult result = dispatcher.execute(execution(WorkflowNodeType.MODEL));

        assertThat(result.outputSummary()).isEqualTo("ok");
    }

    /** 同一类型重复注册启动失败。 */
    @Test
    @DisplayName("重复注册节点类型启动失败")
    void constructor_duplicateType_fails() {
        assertThatThrownBy(() ->
                        new WorkflowNodeDispatcher(List.of(stub(WorkflowNodeType.MODEL), stub(WorkflowNodeType.MODEL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    /** 缺失类型明确失败。 */
    @Test
    @DisplayName("缺失执行器明确失败")
    void execute_missingExecutor_fails() {
        WorkflowNodeDispatcher dispatcher = new WorkflowNodeDispatcher(List.of());

        assertThatThrownBy(() -> dispatcher.execute(execution(WorkflowNodeType.MODEL)))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INTERNAL));
    }

    /**
     * 构造桩执行器。
     *
     * @param type 类型
     * @return 执行器
     */
    private static WorkflowNodeExecutor stub(WorkflowNodeType type) {
        return new WorkflowNodeExecutor() {
            @Override
            public WorkflowNodeType nodeType() {
                return type;
            }

            @Override
            public WorkflowNodeResult execute(WorkflowNodeExecution execution) {
                return WorkflowNodeResult.of("in", "ok", Map.of(), List.of());
            }
        };
    }

    /**
     * 构造指定类型的执行上下文。
     *
     * @param type 类型
     * @return 上下文
     */
    private static WorkflowNodeExecution execution(WorkflowNodeType type) {
        String graph = "{\"schemaVersion\":1,\"nodes\":[" + "{\"id\":\"n\",\"type\":\"" + type.name()
                + "\",\"name\":\"节点\",\"config\":{}}],\"edges\":[]}";
        return WorkflowNodeTestFixtures.execution(
                graph,
                "n",
                "{}",
                Map.of(),
                List.of(),
                WorkflowNodeTestFixtures.futureDeadline(),
                WorkflowNodeTestFixtures.activeGuard());
    }
}
