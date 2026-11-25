package com.riccon.worker.workflow.engine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发布图严格解析与运行入口结构校验。
 *
 * <p>覆盖未知节点类型/字段、条件分支完整性、入度/出度、不可达节点、resultFrom 祖先关系、
 * 节点上限与 schema 版本；这些规则是 TM-72“发布/运行双处拒绝”的 Worker 侧证据。
 */
class WorkflowGraphJsonTest {

    /** 串行图解析出节点、边与条件配置。 */
    @Test
    @DisplayName("合法串行图解析并校验通过")
    void parse_validGraph() {
        WorkflowGraph graph = WorkflowGraphJson.parse(serialGraph(), 50);

        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.startNode().id()).isEqualTo("s");
        assertThat(graph.findNode("m").orElseThrow().type()).isEqualTo(WorkflowNodeType.MODEL);
        assertThat(graph.findNode("e").orElseThrow().config().resultFrom()).isEqualTo("m");
        graph.validateForRun();
    }

    /** 条件图解析 TRUE/FALSE 分支并校验。 */
    @Test
    @DisplayName("合法条件图解析两条分支")
    void parse_validConditionGraph() {
        WorkflowGraph graph = WorkflowGraphJson.parse(conditionGraph(), 50);

        graph.validateForRun();
        assertThat(graph.branchEdge("c", WorkflowEdgeBranch.TRUE).orElseThrow().target())
                .isEqualTo("t");
        assertThat(graph.branchEdge("c", WorkflowEdgeBranch.FALSE).orElseThrow().target())
                .isEqualTo("f");
        assertThat(graph.findNode("c").orElseThrow().config().condition().operator())
                .isEqualTo(WorkflowConditionOperator.GTE);
    }

    /** 未知节点类型拒绝。 */
    @Test
    @DisplayName("未知节点类型拒绝")
    void parse_unknownNodeType_rejected() {
        String graph = graph(
                List.of(node("s", "START", "{}"), node("x", "SCRIPT", "{}")), List.of(edge("e1", "s", "x", null)));

        assertThatThrownBy(() -> WorkflowGraphJson.parse(graph, 50))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("不是受支持的值");
    }

    /** 未知配置字段拒绝（TM-72）。 */
    @Test
    @DisplayName("未知配置字段拒绝")
    void parse_unknownConfigField_rejected() {
        String graph = graph(
                List.of(node("s", "START", "{\"script\":\"rm -rf\"}"), node("e", "END", "{\"resultFrom\":\"s\"}")),
                List.of(edge("e1", "s", "e", null)));

        assertThatThrownBy(() -> WorkflowGraphJson.parse(graph, 50))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("未知字段");
    }

    /** schemaVersion 不受支持拒绝。 */
    @Test
    @DisplayName("schemaVersion 不受支持拒绝")
    void parse_unsupportedSchema_rejected() {
        String graph = "{\"schemaVersion\":2,\"nodes\":[],\"edges\":[]}";

        assertThatThrownBy(() -> WorkflowGraphJson.parse(graph, 50))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("schemaVersion");
    }

    /** 节点数超过部署上限拒绝。 */
    @Test
    @DisplayName("节点数超过上限拒绝")
    void parse_nodeLimit_rejected() {
        assertThatThrownBy(() -> WorkflowGraphJson.parse(conditionGraph(), 2))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("节点数超过部署上限");
    }

    /** 入度大于 1（合并节点）拒绝。 */
    @Test
    @DisplayName("入度大于 1 拒绝")
    void validate_indegree_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"),
                        node(
                                "c",
                                "CONDITION",
                                "{\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"n\","
                                        + "\"valueType\":\"NUMBER\"},\"operator\":\"EQ\","
                                        + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}}"),
                        node("b", "MODEL", "{\"promptTemplate\":\"b\"}"),
                        node("e", "END", "{\"resultFrom\":\"b\"}")),
                List.of(
                        edge("e1", "s", "c", null), edge("e2", "c", "b", "TRUE"),
                        edge("e3", "c", "b", "FALSE"), edge("e4", "b", "e", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("入度");
    }

    /** 条件节点缺少一条分支拒绝。 */
    @Test
    @DisplayName("条件节点缺少 FALSE 分支拒绝")
    void validate_conditionMissingBranch_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"),
                        node(
                                "c",
                                "CONDITION",
                                "{\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"n\","
                                        + "\"valueType\":\"NUMBER\"},\"operator\":\"EQ\","
                                        + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}}"),
                        node("e", "END", "{\"resultFrom\":\"c\"}")),
                List.of(edge("e1", "s", "c", null), edge("e2", "c", "e", "TRUE")));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("两条出边");
    }

    /** 不可达节点拒绝。 */
    @Test
    @DisplayName("不可达节点拒绝")
    void validate_unreachableNode_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"),
                        node("m", "MODEL", "{\"promptTemplate\":\"m\"}"),
                        node("e", "END", "{\"resultFrom\":\"m\"}"),
                        node("b", "MODEL", "{\"promptTemplate\":\"b\"}"),
                        node("e2", "END", "{\"resultFrom\":\"b\"}")),
                List.of(edge("e1", "s", "m", null), edge("e2", "m", "e", null), edge("e3", "b", "e2", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("不可达");
    }

    /** resultFrom 指向旁支节点拒绝。 */
    @Test
    @DisplayName("resultFrom 非祖先拒绝")
    void validate_resultFromNotAncestor_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"),
                        node(
                                "c",
                                "CONDITION",
                                "{\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"n\","
                                        + "\"valueType\":\"NUMBER\"},\"operator\":\"EQ\","
                                        + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}}"),
                        node("t", "MODEL", "{\"promptTemplate\":\"t\"}"),
                        node("f", "MODEL", "{\"promptTemplate\":\"f\"}"),
                        node("et", "END", "{\"resultFrom\":\"f\"}"),
                        node("ef", "END", "{\"resultFrom\":\"f\"}")),
                List.of(
                        edge("e1", "s", "c", null),
                        edge("e2", "c", "t", "TRUE"),
                        edge("e3", "c", "f", "FALSE"),
                        edge("e4", "t", "et", null),
                        edge("e5", "f", "ef", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("上游节点");
    }

    /** END 缺少 resultFrom 拒绝。 */
    @Test
    @DisplayName("END 缺少 resultFrom 拒绝")
    void validate_endMissingResultFrom_rejected() {
        String graph =
                graph(List.of(node("s", "START", "{}"), node("e", "END", "{}")), List.of(edge("e1", "s", "e", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("resultFrom");
    }

    /** 重复节点 key 拒绝。 */
    @Test
    @DisplayName("重复节点 key 拒绝")
    void parse_duplicateNodeKey_rejected() {
        String graph =
                graph(List.of(node("s", "START", "{}"), node("s", "MODEL", "{\"promptTemplate\":\"m\"}")), List.of());

        assertThatThrownBy(() -> WorkflowGraphJson.parse(graph, 50))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("节点 key 重复");
    }

    /** 边引用不存在节点拒绝。 */
    @Test
    @DisplayName("边引用不存在节点拒绝")
    void parse_edgeUnknownNode_rejected() {
        String graph = graph(
                List.of(node("s", "START", "{}"), node("e", "END", "{\"resultFrom\":\"s\"}")),
                List.of(edge("e1", "s", "missing", null)));

        assertThatThrownBy(() -> WorkflowGraphJson.parse(graph, 50))
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("不存在的节点");
    }

    /** END 有出边拒绝。 */
    @Test
    @DisplayName("END 有出边拒绝")
    void validate_endWithOutgoing_rejected() {
        String graph = graph(
                List.of(node("s", "START", "{}"), node("e", "END", "{\"resultFrom\":\"s\"}")),
                List.of(edge("e1", "s", "e", null), edge("e2", "e", "s", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("END 节点不能有出边");
    }

    /** 非 END 节点缺少出边拒绝。 */
    @Test
    @DisplayName("非 END 节点缺少出边拒绝")
    void validate_nonEndWithoutOutgoing_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"),
                        node("m", "MODEL", "{\"promptTemplate\":\"m\"}"),
                        node("e", "END", "{\"resultFrom\":\"m\"}")),
                List.of(edge("e1", "s", "m", null)));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("恰好一条出边");
    }

    /** 条件节点缺少 condition 配置拒绝。 */
    @Test
    @DisplayName("条件节点缺少 condition 配置拒绝")
    void validate_conditionMissingConfig_rejected() {
        String graph = graph(
                List.of(
                        node("s", "START", "{}"), node("c", "CONDITION", "{}"),
                        node("e", "END", "{\"resultFrom\":\"s\"}"), node("e2", "END", "{\"resultFrom\":\"s\"}")),
                List.of(edge("e1", "s", "c", null), edge("e2", "c", "e", "TRUE"), edge("e3", "c", "e2", "FALSE")));
        WorkflowGraph parsed = WorkflowGraphJson.parse(graph, 50);

        assertThatThrownBy(parsed::validateForRun)
                .isInstanceOf(WorkflowGraphFormatException.class)
                .hasMessageContaining("condition 配置");
    }

    /**
     * 构造串行图。
     *
     * @return 图 JSON
     */
    private static String serialGraph() {
        return graph(
                List.of(
                        node("s", "START", "{}"),
                        node("m", "MODEL", "{\"promptTemplate\":\"写报告\",\"outputKind\":\"TEXT\"}"),
                        node("e", "END", "{\"resultFrom\":\"m\"}")),
                List.of(edge("e1", "s", "m", null), edge("e2", "m", "e", null)));
    }

    /**
     * 构造条件图。
     *
     * @return 图 JSON
     */
    private static String conditionGraph() {
        return graph(
                List.of(
                        node("s", "START", "{}"),
                        node("r", "RETRIEVAL", "{\"queryTemplate\":\"{{input.topic}}\",\"topK\":3}"),
                        node(
                                "c",
                                "CONDITION",
                                "{\"condition\":{\"left\":{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\","
                                        + "\"path\":\"hitCount\",\"valueType\":\"NUMBER\"},\"operator\":\"GTE\","
                                        + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}}"),
                        node("t", "MODEL", "{\"promptTemplate\":\"t\"}"),
                        node("f", "MODEL", "{\"promptTemplate\":\"f\"}"),
                        node("et", "END", "{\"resultFrom\":\"t\"}"),
                        node("ef", "END", "{\"resultFrom\":\"f\"}")),
                List.of(
                        edge("e1", "s", "r", null), edge("e2", "r", "c", null),
                        edge("e3", "c", "t", "TRUE"), edge("e4", "c", "f", "FALSE"),
                        edge("e5", "t", "et", null), edge("e6", "f", "ef", null)));
    }

    /**
     * 节点 JSON。
     *
     * @param id 节点 key
     * @param type 类型
     * @param config 配置
     * @return JSON
     */
    private static String node(String id, String type, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + id + "\",\"config\":" + config + "}";
    }

    /**
     * 边 JSON。
     *
     * @param id 边 ID
     * @param source 源
     * @param target 目标
     * @param branch 分支
     * @return JSON
     */
    private static String edge(String id, String source, String target, String branch) {
        String branchField = branch == null ? "" : ",\"branch\":\"" + branch + "\"";
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"" + branchField + "}";
    }

    /**
     * 图 JSON。
     *
     * @param nodes 节点
     * @param edges 边
     * @return JSON
     */
    private static String graph(List<String> nodes, List<String> edges) {
        return "{\"schemaVersion\":1,\"nodes\":[" + String.join(",", nodes) + "],\"edges\":[" + String.join(",", edges)
                + "]}";
    }
}
