package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphJson;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.node.domain.WorkflowResultFormat;
import com.riccon.worker.workflow.node.domain.WorkflowResultPayload;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * END 结果组装（ADR-0024 §4.4）。
 *
 * <p>覆盖 TEXT/CLASSIFICATION/RETRIEVAL/CONDITION/默认 JSON 形态，以及 resultFrom 缺失、
 * 非祖先、来源未执行等 fail-closed 路径；5 MiB 上限由引擎负责，不在组装器截断。
 */
class WorkflowResultAssemblerTest {

    /** 组装器。 */
    private final WorkflowResultAssembler assembler = new WorkflowResultAssembler(new ObjectMapper());

    /** 模型文本结果。 */
    @Test
    @DisplayName("模型文本结果为 TEXT")
    void assemble_modelText() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("m", "MODEL", "{\"promptTemplate\":\"m\",\"outputKind\":\"TEXT\"}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"m\"}"));
        WorkflowResultPayload payload = assembler.assemble(
                graph, graph.requireNode("e"), Map.of("m", WorkflowNodeTestFixtures.modelOutput("报告正文", null, "摘要")));

        assertThat(payload.format()).isEqualTo(WorkflowResultFormat.TEXT);
        assertThat(payload.text()).isEqualTo("报告正文");
        assertThat(payload.sizeBytes()).isEqualTo("报告正文".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /** 模型分类结果为 JSON。 */
    @Test
    @DisplayName("模型分类结果为 JSON")
    void assemble_modelClassification() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("m", "MODEL", "{\"promptTemplate\":\"m\",\"outputKind\":\"CLASSIFICATION\"}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"m\"}"));
        WorkflowResultPayload payload = assembler.assemble(
                graph, graph.requireNode("e"), Map.of("m", WorkflowNodeTestFixtures.modelOutput("正面", "正面", "分类：正面")));

        assertThat(payload.format()).isEqualTo(WorkflowResultFormat.JSON);
        assertThat(payload.text()).contains("classificationLabel").contains("正面");
    }

    /** 检索结果为 JSON 命中数。 */
    @Test
    @DisplayName("检索结果为 JSON 命中数")
    void assemble_retrieval() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("r", "RETRIEVAL", "{\"queryTemplate\":\"q\",\"topK\":3}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"r\"}"));
        WorkflowResultPayload payload = assembler.assemble(
                graph, graph.requireNode("e"), Map.of("r", WorkflowNodeTestFixtures.retrievalOutput(2, "命中两条")));

        assertThat(payload.format()).isEqualTo(WorkflowResultFormat.JSON);
        assertThat(payload.text()).isEqualTo("{\"hitCount\":2}");
    }

    /** 条件结果为 JSON 分支。 */
    @Test
    @DisplayName("条件结果为 JSON 分支")
    void assemble_condition() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node(
                        "c",
                        "CONDITION",
                        "{\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"n\","
                                + "\"valueType\":\"NUMBER\"},\"operator\":\"EQ\","
                                + "\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"c\"}"));
        ObjectNode output = new ObjectMapper().createObjectNode();
        output.put("branch", "TRUE");
        output.put("result", true);
        WorkflowResultPayload payload =
                assembler.assemble(graph, graph.requireNode("e"), Map.of("c", new WorkflowNodeOutput(output, "条件成立")));

        assertThat(payload.format()).isEqualTo(WorkflowResultFormat.JSON);
        assertThat(payload.text()).isEqualTo("{\"branch\":\"TRUE\"}");
    }

    /** resultFrom 缺失失败。 */
    @Test
    @DisplayName("resultFrom 缺失失败")
    void assemble_missingResultFrom_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}") + "," + node("e", "END", "{}"));

        assertThatThrownBy(() -> assembler.assemble(graph, graph.requireNode("e"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
    }

    /** 来源节点未执行失败。 */
    @Test
    @DisplayName("来源节点未执行失败")
    void assemble_sourceNotExecuted_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("m", "MODEL", "{\"promptTemplate\":\"m\"}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"m\"}"));

        assertThatThrownBy(() -> assembler.assemble(graph, graph.requireNode("e"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("尚未执行");
    }

    /** 来源节点不在图中失败。 */
    @Test
    @DisplayName("来源节点不在图中失败")
    void assemble_sourceNotInGraph_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}") + "," + node("e", "END", "{\"resultFrom\":\"ghost\"}"));

        assertThatThrownBy(() -> assembler.assemble(graph, graph.requireNode("e"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("不在发布图");
    }

    /** 检索输出缺少 hitCount 失败。 */
    @Test
    @DisplayName("检索输出缺少 hitCount 失败")
    void assemble_retrievalWithoutHitCount_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("r", "RETRIEVAL", "{\"queryTemplate\":\"q\",\"topK\":3}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"r\"}"));
        ObjectNode output = new ObjectMapper().createObjectNode();

        assertThatThrownBy(() -> assembler.assemble(
                        graph, graph.requireNode("e"), Map.of("r", new WorkflowNodeOutput(output, "空"))))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("hitCount");
    }

    /** 模型输出缺少文本失败。 */
    @Test
    @DisplayName("模型输出缺少文本失败")
    void assemble_modelWithoutText_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("m", "MODEL", "{\"promptTemplate\":\"m\",\"outputKind\":\"TEXT\"}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"m\"}"));
        ObjectNode output = new ObjectMapper().createObjectNode();

        assertThatThrownBy(() -> assembler.assemble(
                        graph, graph.requireNode("e"), Map.of("m", new WorkflowNodeOutput(output, "空"))))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("文本");
    }

    /** 分类输出缺少标签失败。 */
    @Test
    @DisplayName("分类输出缺少标签失败")
    void assemble_classificationWithoutLabel_fails() {
        WorkflowGraph graph = graph(node("s", "START", "{}")
                + ","
                + node("m", "MODEL", "{\"promptTemplate\":\"m\",\"outputKind\":\"CLASSIFICATION\"}")
                + ","
                + node("e", "END", "{\"resultFrom\":\"m\"}"));
        ObjectNode output = new ObjectMapper().createObjectNode();
        output.put("text", "正面");

        assertThatThrownBy(() -> assembler.assemble(
                        graph, graph.requireNode("e"), Map.of("m", new WorkflowNodeOutput(output, "分类"))))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("分类标签");
    }

    /**
     * 构造图。
     *
     * @param nodes 节点 JSON
     * @return 图
     */
    private static WorkflowGraph graph(String nodes) {
        return WorkflowGraphJson.parse("{\"schemaVersion\":1,\"nodes\":[" + nodes + "],\"edges\":[]}", 50);
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
}
