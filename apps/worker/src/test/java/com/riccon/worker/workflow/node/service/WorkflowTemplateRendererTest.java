package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 服务端封闭模板渲染（ADR-0024 §4.5）。
 *
 * <p>只允许 {@code input.*} 与节点 summary/classificationLabel/hitCount；缺失字段、非法占位符、
 * 非标量输入必须失败，禁止表达式、函数或静默空串。
 */
class WorkflowTemplateRendererTest {

    /** 渲染器。 */
    private final WorkflowTemplateRenderer renderer = new WorkflowTemplateRenderer();

    /** 输入与节点占位符替换。 */
    @Test
    @DisplayName("渲染输入与节点占位符")
    void render_replacesAllowedPlaceholders() {
        Map<String, WorkflowNodeOutput> outputs = Map.of(
                "r", WorkflowNodeTestFixtures.retrievalOutput(3, "命中三条"),
                "m", WorkflowNodeTestFixtures.modelOutput("正文", "正面", "摘要"));

        String rendered = renderer.render(
                "主题={{input.topic}}；检索={{node.r.summary}}；命中={{node.r.hitCount}}；"
                        + "分类={{node.m.classificationLabel}}；摘要={{node.m.summary}}",
                inputs("{\"topic\":\"市场\"}"),
                outputs);

        assertThat(rendered).isEqualTo("主题=市场；检索=命中三条；命中=3；分类=正面；摘要=摘要");
    }

    /** 缺少运行输入字段失败。 */
    @Test
    @DisplayName("缺少输入字段失败")
    void render_missingInput_fails() {
        assertThatThrownBy(() -> renderer.render("{{input.topic}}", inputs("{}"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
    }

    /** 非标量输入失败。 */
    @Test
    @DisplayName("非标量输入失败")
    void render_nonScalarInput_fails() {
        assertThatThrownBy(() -> renderer.render("{{input.topic}}", inputs("{\"topic\":{\"a\":1}}"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("不是标量");
    }

    /** 未知占位符失败。 */
    @Test
    @DisplayName("未知占位符失败")
    void render_unknownPlaceholder_fails() {
        assertThatThrownBy(() -> renderer.render("{{env.SECRET}}", inputs("{}"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("占位符");
    }

    /** 未闭合占位符失败。 */
    @Test
    @DisplayName("未闭合占位符失败")
    void render_unclosedPlaceholder_fails() {
        assertThatThrownBy(() -> renderer.render("{{input.topic", inputs("{}"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("占位符");
    }

    /** 引用未执行节点失败。 */
    @Test
    @DisplayName("引用未执行节点失败")
    void render_missingNodeOutput_fails() {
        assertThatThrownBy(() -> renderer.render("{{node.r.summary}}", inputs("{}"), Map.of()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("未执行");
    }

    /** 引用不存在字段失败。 */
    @Test
    @DisplayName("引用节点缺失字段失败")
    void render_missingNodeField_fails() {
        Map<String, WorkflowNodeOutput> outputs = Map.of("m", WorkflowNodeTestFixtures.modelOutput("正文", null, "摘要"));

        assertThatThrownBy(() -> renderer.render("{{node.m.classificationLabel}}", inputs("{}"), outputs))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("classificationLabel");
    }

    /** 收集输入字段引用；节点引用只校验语法。 */
    @Test
    @DisplayName("收集输入字段引用")
    void referencedInputFields_collectsInputs() {
        assertThat(renderer.referencedInputFields("{{input.topic}} {{input.region}} {{node.r.summary}}"))
                .containsExactly("topic", "region");
    }

    /** START 输入校验在缺字段时失败。 */
    @Test
    @DisplayName("START 输入校验缺字段失败")
    void requireInputs_missingField_fails() {
        com.riccon.worker.workflow.engine.domain.WorkflowGraph graph =
                com.riccon.worker.workflow.engine.domain.WorkflowGraphJson.parse(
                        "{\"schemaVersion\":1,\"nodes\":["
                                + "{\"id\":\"s\",\"type\":\"START\",\"name\":\"开始\",\"config\":{}},"
                                + "{\"id\":\"m\",\"type\":\"MODEL\",\"name\":\"模型\","
                                + "\"config\":{\"promptTemplate\":\"{{input.topic}}\"}},"
                                + "{\"id\":\"e\",\"type\":\"END\",\"name\":\"结束\",\"config\":{\"resultFrom\":\"m\"}}],"
                                + "\"edges\":[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"m\"},"
                                + "{\"id\":\"e2\",\"source\":\"m\",\"target\":\"e\"}]}",
                        50);

        assertThatThrownBy(() -> renderer.requireInputs(graph, inputs("{}")))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("topic");
    }

    /**
     * 解析运行输入。
     *
     * @param json JSON
     * @return 输入节点
     */
    private static tools.jackson.databind.JsonNode inputs(String json) {
        return new ObjectMapper().readTree(json);
    }
}
