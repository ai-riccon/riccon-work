package com.riccon.api.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流程图 JSON 编解码与规范化哈希。
 *
 * <p>覆盖 IT-WF-101/TM-72 的图形状部分：未知字段、未知节点类型、布尔 branch、节点/边上限、重复 ID 在
 * 保存解析时拒绝；规范化哈希与键顺序无关，供草稿落后判定与发布快照复用。
 */
class WorkflowGraphJsonTest {

    /** 完整图：开始、检索、条件、模型、结束，含坐标与工具参数模板。 */
    private static final String FULL_GRAPH = """
            {
              "schemaVersion": 1,
              "nodes": [
                {"id":"start","type":"START","name":"开始","config":{},"position":{"x":10,"y":20}},
                {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{"queryTemplate":"{{input.topic}}","topK":5}},
                {"id":"condition","type":"CONDITION","name":"判断","config":{"condition":{
                  "left":{"source":"NODE_OUTPUT","nodeId":"retrieval","path":"hitCount","valueType":"NUMBER"},
                  "operator":"GTE",
                  "right":{"valueType":"NUMBER","value":1}}}},
                {"id":"model","type":"MODEL","name":"生成","config":{"promptTemplate":"整理主题","outputKind":"TEXT"}},
                {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model","resultTitle":"报告"}}
              ],
              "edges": [
                {"id":"e1","source":"start","target":"retrieval"},
                {"id":"e2","source":"retrieval","target":"condition"},
                {"id":"e3","source":"condition","target":"model","branch":"TRUE"},
                {"id":"e4","source":"condition","target":"end","branch":"FALSE"}
              ]
            }
            """;

    /** 图可往返，节点、边、条件与坐标保持一致。 */
    @Test
    @DisplayName("图 JSON 往返：节点/边/条件/坐标保持一致")
    void parse_roundTrip_preservesGraph() {
        WorkflowGraph graph = WorkflowGraphJson.parse(FULL_GRAPH);
        assertThat(graph.nodeCount()).isEqualTo(5);
        assertThat(graph.edges()).hasSize(4);
        assertThat(graph.nodes().get(1).type()).isEqualTo(WorkflowNodeType.RETRIEVAL);
        assertThat(graph.nodes().get(1).config().topK()).isEqualTo(5);
        assertThat(graph.nodes().get(0).position().x()).isEqualTo(10.0);
        assertThat(graph.edges().get(2).branch()).isEqualTo(WorkflowEdgeBranch.TRUE);
        assertThat(graph.edges().get(0).branch()).isNull();
        assertThat(graph.nodes().get(2).config().condition().operator()).isEqualTo(WorkflowConditionOperator.GTE);
        assertThat(graph.nodes().get(2).config().condition().left().source())
                .isEqualTo(WorkflowConditionSource.NODE_OUTPUT);
        assertThat(graph.nodes().get(2).config().condition().right().valueType())
                .isEqualTo(WorkflowConditionValueType.NUMBER);

        WorkflowGraph reparsed = WorkflowGraphJson.parse(WorkflowGraphJson.toJson(graph));
        assertThat(reparsed).isEqualTo(graph);
    }

    /** 规范化哈希与对象键顺序无关，内容变化则不同。 */
    @Test
    @DisplayName("规范化哈希：键顺序无关、内容变化敏感")
    void sha256_isCanonical() {
        String reordered = """
                {
                  "edges":[{"target":"retrieval","source":"start","id":"e1"}],
                  "nodes":[
                    {"name":"开始","config":{},"type":"START","id":"start"},
                    {"config":{"topK":1},"name":"检索","type":"RETRIEVAL","id":"retrieval"}
                  ],
                  "schemaVersion":1
                }
                """;
        String original = """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{"topK":1}}
                ],"edges":[{"id":"e1","source":"start","target":"retrieval"}]}
                """;
        assertThat(WorkflowGraphJson.sha256(WorkflowGraphJson.parse(reordered)))
                .isEqualTo(WorkflowGraphJson.sha256(WorkflowGraphJson.parse(original)));
        assertThat(WorkflowGraphJson.sha256(WorkflowGraphJson.parse(FULL_GRAPH)))
                .isNotEqualTo(WorkflowGraphJson.sha256(WorkflowGraphJson.parse(original)));
    }

    /** 未知字段、未知类型、布尔 branch、重复 ID 与超限都被拒绝。 */
    @Test
    @DisplayName("形状校验：未知字段/类型、布尔 branch、重复 ID、上限")
    void parse_rejectsInvalidShape() {
        assertThatThrownBy(() -> WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[{"id":"s","type":"SCRIPT","name":"x","config":{}}],"edges":[]}
                """)).isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[
                  {"id":"s","type":"START","name":"x","config":{}}
                ],"edges":[{"id":"e1","source":"s","target":"s","branch":true}]}
                """)).isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[
                  {"id":"s","type":"START","name":"x","config":{"script":"rm -rf /"}}
                ],"edges":[]}
                """)).isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[],"edges":[],"unknown":1}
                """)).isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[
                  {"id":"s","type":"START","name":"x","config":{}},
                  {"id":"s","type":"END","name":"y","config":{}}
                ],"edges":[]}
                """)).isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse(tooManyNodes()))
                .isInstanceOf(WorkflowGraphFormatException.class);
        assertThatThrownBy(() -> WorkflowGraphJson.parse("not-json")).isInstanceOf(WorkflowGraphFormatException.class);
    }

    /** 契约封闭字段与上限的负向用例集合。 */
    @Test
    @DisplayName("形状校验：字段白名单/类型/枚举/长度/上限负向集合")
    void parse_rejectsContractViolations() {
        assertInvalid("{\"schemaVersion\":2,\"nodes\":[],\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":{},\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[],\"edges\":{}}");
        assertInvalid(
                "{\"schemaVersion\":1,\"nodes\":[{\"type\":\"START\",\"name\":\"x\",\"config\":{}}],\"edges\":[]}");
        assertInvalid(
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"bad id\",\"type\":\"START\",\"name\":\"x\",\"config\":{}}],\"edges\":[]}");
        assertInvalid(
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"\",\"config\":{}}],\"edges\":[]}");
        assertInvalid(
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\"}],\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\","
                + "\"config\":{},\"position\":{\"x\":1}}],\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\","
                + "\"config\":{},\"position\":{\"x\":\"1\",\"y\":2}}],\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[{\"id\":\"e1\",\"source\":\"s\"}]}");
        assertInvalid(
                "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[{\"id\":\"bad id\",\"source\":\"s\",\"target\":\"t\"}]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"t\","
                + "\"branch\":\"MAYBE\"}]}");
        assertInvalid(config("\"promptTemplate\":\"" + "a".repeat(8001) + "\""));
        assertInvalid(config("\"queryTemplate\":\"" + "a".repeat(2001) + "\""));
        assertInvalid(config("\"topK\":0"));
        assertInvalid(config("\"topK\":21"));
        assertInvalid(config("\"topK\":\"5\""));
        assertInvalid(config("\"modelId\":\"not-uuid\""));
        assertInvalid(config("\"outputKind\":\"JSON\""));
        assertInvalid(config("\"labels\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]"));
        assertInvalid(config("\"labels\":[\"  \"]"));
        assertInvalid(config("\"labels\":[\"" + "a".repeat(65) + "\"]"));
        assertInvalid(config("\"argsTemplate\":[]"));
        assertInvalid(config("\"assigneeType\":\"GROUP\""));
        assertInvalid(config("\"assigneeRole\":\"OWNER\""));
        assertInvalid(config("\"assigneeUserIds\":[\"bad\"]"));
        assertInvalid(config("\"assigneeUserIds\":["
                + IntStream.range(0, 51)
                        .mapToObj(index -> "\"00000000-0000-7000-8000-" + String.format("%012d", index) + "\"")
                        .collect(Collectors.joining(","))
                + "]"));
        assertInvalid(config("\"targetNodeKey\":\"\""));
        assertInvalid(config("\"resultTitle\":\"" + "a".repeat(201) + "\""));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"EQ\"}"));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"USER_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"EQ\",\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}"));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"EQ\",\"right\":{\"valueType\":\"NUMBER\",\"value\":1}}"));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"EQ\",\"right\":{\"valueType\":\"NUMBER\",\"value\":{}}}"));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"IN\",\"right\":{\"valueType\":\"NUMBER\",\"values\":[1,null]}}"));
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"IN\",\"right\":{\"valueType\":\"NUMBER\",\"values\":["
                        + IntStream.range(0, 21).mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]}}"));
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\","
                + "\"config\":{},\"extra\":1}],\"edges\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"t\","
                + "\"extra\":1}]}");
        assertInvalid(
                config("\"condition\":{\"left\":{\"source\":\"RUN_INPUT\",\"path\":\"a\",\"valueType\":\"NUMBER\"},"
                        + "\"operator\":\"EQ\",\"right\":{\"valueType\":\"NUMBER\",\"value\":1},\"extra\":1}"));
    }

    /**
     * 构造带单个 START 节点的图，并注入指定配置字段。
     *
     * @param configFields 配置字段 JSON 片段
     * @return 图 JSON
     */
    private static String config(String configFields) {
        return "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\"," + "\"config\":{"
                + configFields + "}}],\"edges\":[]}";
    }

    /**
     * 断言图 JSON 被形状校验拒绝。
     *
     * @param json 图 JSON
     */
    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> WorkflowGraphJson.parse(json)).isInstanceOf(WorkflowGraphFormatException.class);
    }

    /** 序列化省略空字段，避免保存后往返产生大量 null。 */
    @Test
    @DisplayName("序列化：省略 null 配置字段")
    void toJson_omitsNullFields() {
        WorkflowGraph graph = WorkflowGraphJson.parse("""
                {"schemaVersion":1,"nodes":[{"id":"start","type":"START","name":"开始","config":{}}],"edges":[]}
                """);
        String json = WorkflowGraphJson.toJson(graph);
        assertThat(json)
                .doesNotContain("promptTemplate")
                .doesNotContain("position")
                .doesNotContain("null");
        assertThat(json).contains("\"schemaVersion\":1");
    }

    /** 工具参数模板最多 16 个顶层键。 */
    @Test
    @DisplayName("argsTemplate：最多 16 个键")
    void parse_argsTemplate_limit() {
        String sixteen = argsTemplate(16);
        assertThat(WorkflowGraphJson.parse(sixteen).nodes().get(0).config().argsTemplate())
                .hasSize(16);
        assertThatThrownBy(() -> WorkflowGraphJson.parse(argsTemplate(17)))
                .isInstanceOf(WorkflowGraphFormatException.class);
    }

    /**
     * 生成带 N 个参数的 MCP 工具节点图。
     *
     * @param count 参数个数
     * @return 图 JSON
     */
    private static String argsTemplate(int count) {
        String args = IntStream.range(0, count)
                .mapToObj(index -> "\"k" + index + "\":\"v" + index + "\"")
                .collect(Collectors.joining(","));
        return "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"tool\",\"type\":\"MCP_TOOL\",\"name\":\"工具\","
                + "\"config\":{\"argsTemplate\":{" + args + "}}}],\"edges\":[]}";
    }

    /**
     * 生成 51 个节点的图，用于上限断言。
     *
     * @return 图 JSON
     */
    private static String tooManyNodes() {
        String nodes = IntStream.range(0, 51)
                .mapToObj(index -> "{\"id\":\"n" + index + "\",\"type\":\"MODEL\",\"name\":\"节点\",\"config\":{}}")
                .collect(Collectors.joining(","));
        return "{\"schemaVersion\":1,\"nodes\":[" + nodes + "],\"edges\":[]}";
    }
}
