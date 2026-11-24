package com.riccon.api.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphCondition;
import com.riccon.api.workflow.domain.WorkflowGraphConditionRight;
import com.riccon.api.workflow.domain.WorkflowGraphConditionSource;
import com.riccon.api.workflow.domain.WorkflowGraphEdge;
import com.riccon.api.workflow.domain.WorkflowGraphNode;
import com.riccon.api.workflow.domain.WorkflowGraphNodeConfig;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图领域对象的本地不变量。
 *
 * <p>这些校验在程序化构造时也必须生效，不能只依赖 JSON 解析器；覆盖空值、ID/名称格式、节点/边上限、
 * 配置范围与工具参数模板上限（TM-72）。
 */
class WorkflowGraphDomainTest {

    /** 图版本、节点/边上限与 ID 唯一性。 */
    @Test
    @DisplayName("WorkflowGraph：版本/上限/唯一 ID")
    void graph_invariants() {
        WorkflowGraphNode start = node("start");
        assertThat(new WorkflowGraph(1, List.of(start), List.of()).nodeCount()).isEqualTo(1);
        assertThatThrownBy(() -> new WorkflowGraph(2, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraph(1, null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraph(1, List.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraph(1, List.of(start, node("start")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<WorkflowGraphNode> fiftyOne = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            fiftyOne.add(node("n" + index));
        }
        assertThatThrownBy(() -> new WorkflowGraph(1, fiftyOne, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<WorkflowGraphEdge> hundredOne = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            hundredOne.add(new WorkflowGraphEdge("e" + index, "start", "end", null));
        }
        assertThatThrownBy(() -> new WorkflowGraph(1, List.of(start), hundredOne))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraph(
                        1,
                        List.of(start, node("end")),
                        List.of(
                                new WorkflowGraphEdge("e1", "start", "end", null),
                                new WorkflowGraphEdge("e1", "end", "start", null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 节点与边的必填字段和格式。 */
    @Test
    @DisplayName("节点/边：必填、ID 与名称格式")
    void nodeAndEdge_invariants() {
        assertThatThrownBy(() -> new WorkflowGraphNode(null, WorkflowNodeType.START, "x", emptyConfig(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphNode("s", null, "x", emptyConfig(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphNode("s", WorkflowNodeType.START, null, emptyConfig(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphNode("s", WorkflowNodeType.START, "x", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphNode("bad id", WorkflowNodeType.START, "x", emptyConfig(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphNode("s", WorkflowNodeType.START, " ", emptyConfig(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new WorkflowGraphNode("s", WorkflowNodeType.START, "a".repeat(201), emptyConfig(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorkflowGraphEdge(null, "s", "t", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphEdge("e", null, "t", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphEdge("e", "s", null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphEdge("bad id", "s", "t", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphEdge("e", "bad id", "t", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphEdge("e", "s", "bad id", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 条件左值、右值与组合的必填字段。 */
    @Test
    @DisplayName("条件：左值/右值/算子必填与上限")
    void condition_invariants() {
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(null, null, "a", WorkflowConditionValueType.NUMBER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(
                        WorkflowConditionSource.RUN_INPUT, null, null, WorkflowConditionValueType.NUMBER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(WorkflowConditionSource.RUN_INPUT, null, "a", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(
                        WorkflowConditionSource.RUN_INPUT, null, " ", WorkflowConditionValueType.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(
                        WorkflowConditionSource.RUN_INPUT, null, "a".repeat(65), WorkflowConditionValueType.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionSource(
                        WorkflowConditionSource.RUN_INPUT, " ", "a", WorkflowConditionValueType.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorkflowGraphConditionRight(null, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowGraphConditionRight(
                        WorkflowConditionValueType.NUMBER, null, java.util.Collections.nCopies(21, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WorkflowGraphCondition(null, WorkflowConditionOperator.EQ, null))
                .isInstanceOf(NullPointerException.class);
        WorkflowGraphCondition condition = new WorkflowGraphCondition(
                new WorkflowGraphConditionSource(
                        WorkflowConditionSource.RUN_INPUT, null, "topic", WorkflowConditionValueType.STRING),
                WorkflowConditionOperator.IN,
                new WorkflowGraphConditionRight(WorkflowConditionValueType.STRING, null, List.of("a", "b")));
        assertThat(condition.right().values()).hasSize(2);
    }

    /** 节点配置的长度、范围与集合上限。 */
    @Test
    @DisplayName("节点配置：长度/范围/集合上限")
    void nodeConfig_invariants() {
        assertThat(emptyConfig()).isNotNull();
        assertThatThrownBy(() -> config("a".repeat(8001), null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, "a".repeat(2001), null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, 0, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, 21, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> config(null, null, null, java.util.Collections.nCopies(11, "x"), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, List.of(" "), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, List.of("a".repeat(65)), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, null, args(17), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, null, null, "OWNER", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, null, null, null, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, null, null, null, "a".repeat(65), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(null, null, null, null, null, null, null, "a".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphNodeConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.Collections.nCopies(51, UUID.randomUUID()),
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowGraphNodeConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "a".repeat(65),
                        "a".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new WorkflowGraphNodeConfig(
                                null,
                                null,
                                1,
                                null,
                                null,
                                List.of("a"),
                                null,
                                args(16),
                                null,
                                "TENANT_ADMIN",
                                List.of(UUID.randomUUID()),
                                null,
                                "node",
                                null,
                                "start",
                                "标题")
                        .labels())
                .hasSize(1);
    }

    /**
     * 构造节点配置。
     *
     * @param prompt 提示词
     * @param query 查询
     * @param topK 命中数
     * @param labels 标签
     * @param args 工具参数
     * @param role 审批角色
     * @param target 目标节点
     * @param resultTitle 结果标题
     * @return 配置
     */
    private static WorkflowGraphNodeConfig config(
            String prompt,
            String query,
            Integer topK,
            List<String> labels,
            Map<String, Object> args,
            String role,
            String target,
            String resultTitle) {
        return new WorkflowGraphNodeConfig(
                prompt,
                query,
                topK,
                null,
                null,
                labels,
                null,
                args,
                null,
                role,
                null,
                null,
                target,
                null,
                null,
                resultTitle);
    }

    /**
     * 构造空配置。
     *
     * @return 空配置
     */
    private static WorkflowGraphNodeConfig emptyConfig() {
        return new WorkflowGraphNodeConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 构造 N 键工具参数模板。
     *
     * @param count 键数
     * @return 参数模板
     */
    private static Map<String, Object> args(int count) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            args.put("k" + index, "v" + index);
        }
        return args;
    }

    /**
     * 构造最小 START 节点。
     *
     * @param id 节点 ID
     * @return 节点
     */
    private static WorkflowGraphNode node(String id) {
        return new WorkflowGraphNode(id, WorkflowNodeType.START, "节点", emptyConfig(), null);
    }
}
