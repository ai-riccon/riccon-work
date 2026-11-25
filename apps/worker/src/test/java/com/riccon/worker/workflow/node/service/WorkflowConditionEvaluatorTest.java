package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 条件节点封闭求值（ADR-0024 §4.5，AC-09）。
 *
 * <p>覆盖数字/字符串/布尔、EQ/NE/GT/GTE/LT/LTE/IN、运行输入与节点输出、缺值/类型不符/
 * 算子不匹配/右值形态错误；所有非法条件都必须 {@code WF_CONDITION_INVALID}，不默认分支。
 */
class WorkflowConditionEvaluatorTest {

    /** 求值器。 */
    private final WorkflowConditionEvaluator evaluator = new WorkflowConditionEvaluator();

    /** 节点输出 hitCount GTE 命中 TRUE。 */
    @Test
    @DisplayName("hitCount GTE 命中 TRUE")
    void evaluate_nodeOutputGte_true() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\",\"path\":\"hitCount\","
                                        + "\"valueType\":\"NUMBER\"}",
                                "GTE",
                                "{\"valueType\":\"NUMBER\",\"value\":2}"),
                        "{\"n\":1}",
                        Map.of("r", WorkflowNodeTestFixtures.retrievalOutput(2, "两条"))))
                .isEqualTo(WorkflowEdgeBranch.TRUE);
    }

    /** hitCount LT 不命中 FALSE。 */
    @Test
    @DisplayName("hitCount LT 不命中 FALSE")
    void evaluate_nodeOutputLt_false() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\",\"path\":\"hitCount\","
                                        + "\"valueType\":\"NUMBER\"}",
                                "LT",
                                "{\"valueType\":\"NUMBER\",\"value\":1}"),
                        "{}",
                        Map.of("r", WorkflowNodeTestFixtures.retrievalOutput(2, "两条"))))
                .isEqualTo(WorkflowEdgeBranch.FALSE);
    }

    /** 运行输入数字 EQ。 */
    @Test
    @DisplayName("运行输入数字 EQ")
    void evaluate_inputNumberEq_true() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"RUN_INPUT\",\"path\":\"n\",\"valueType\":\"NUMBER\"}",
                                "EQ",
                                "{\"valueType\":\"NUMBER\",\"value\":2}"),
                        "{\"n\":2}",
                        Map.of()))
                .isEqualTo(WorkflowEdgeBranch.TRUE);
    }

    /** 运行输入字符串 IN。 */
    @Test
    @DisplayName("运行输入字符串 IN")
    void evaluate_inputStringIn_true() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"RUN_INPUT\",\"path\":\"s\",\"valueType\":\"STRING\"}",
                                "IN",
                                "{\"valueType\":\"STRING\",\"values\":[\"a\",\"abc\"]}"),
                        "{\"s\":\"abc\"}",
                        Map.of()))
                .isEqualTo(WorkflowEdgeBranch.TRUE);
    }

    /** 布尔 NE。 */
    @Test
    @DisplayName("布尔 NE")
    void evaluate_booleanNe_true() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"RUN_INPUT\",\"path\":\"flag\",\"valueType\":\"BOOLEAN\"}",
                                "NE",
                                "{\"valueType\":\"BOOLEAN\",\"value\":false}"),
                        "{\"flag\":true}",
                        Map.of()))
                .isEqualTo(WorkflowEdgeBranch.TRUE);
    }

    /** 模型分类标签相等。 */
    @Test
    @DisplayName("模型分类标签相等")
    void evaluate_modelClassification_true() {
        assertThat(evaluate(
                        condition(
                                "{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"m\",\"path\":\"classificationLabel\","
                                        + "\"valueType\":\"STRING\"}",
                                "EQ",
                                "{\"valueType\":\"STRING\",\"value\":\"正面\"}"),
                        "{}",
                        Map.of("m", WorkflowNodeTestFixtures.modelOutput("正文", "正面", "摘要"))))
                .isEqualTo(WorkflowEdgeBranch.TRUE);
    }

    /** 缺少运行输入失败。 */
    @Test
    @DisplayName("缺少运行输入失败")
    void evaluate_missingInput_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"missing\",\"valueType\":\"NUMBER\"}",
                        "EQ",
                        "{\"valueType\":\"NUMBER\",\"value\":1}"),
                "{}",
                Map.of());
    }

    /** 左值类型不符失败。 */
    @Test
    @DisplayName("左值类型不符失败")
    void evaluate_leftTypeMismatch_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"s\",\"valueType\":\"NUMBER\"}",
                        "EQ",
                        "{\"valueType\":\"NUMBER\",\"value\":1}"),
                "{\"s\":\"abc\"}",
                Map.of());
    }

    /** 左右类型不一致失败。 */
    @Test
    @DisplayName("左右类型不一致失败")
    void evaluate_rightTypeMismatch_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"n\",\"valueType\":\"NUMBER\"}",
                        "EQ",
                        "{\"valueType\":\"STRING\",\"value\":\"1\"}"),
                "{\"n\":1}",
                Map.of());
    }

    /** 非白名单输出字段失败。 */
    @Test
    @DisplayName("非白名单输出字段失败")
    void evaluate_unknownPath_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\",\"path\":\"content\",\"valueType\":\"STRING\"}",
                        "EQ",
                        "{\"valueType\":\"STRING\",\"value\":\"x\"}"),
                "{}",
                Map.of("r", WorkflowNodeTestFixtures.retrievalOutput(1, "摘要")));
    }

    /** 上游节点未执行失败。 */
    @Test
    @DisplayName("上游节点未执行失败")
    void evaluate_missingOutput_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"NODE_OUTPUT\",\"nodeId\":\"r\",\"path\":\"hitCount\","
                                + "\"valueType\":\"NUMBER\"}",
                        "EQ",
                        "{\"valueType\":\"NUMBER\",\"value\":1}"),
                "{}",
                Map.of());
    }

    /** 字符串大小比较失败（只允许 NUMBER）。 */
    @Test
    @DisplayName("字符串大小比较失败")
    void evaluate_stringGt_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"s\",\"valueType\":\"STRING\"}",
                        "GT",
                        "{\"valueType\":\"STRING\",\"value\":\"a\"}"),
                "{\"s\":\"b\"}",
                Map.of());
    }

    /** 非 IN 算子带 values 失败。 */
    @Test
    @DisplayName("非 IN 算子带 values 失败")
    void evaluate_valuesWithoutIn_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"s\",\"valueType\":\"STRING\"}",
                        "EQ",
                        "{\"valueType\":\"STRING\",\"values\":[\"a\"]}"),
                "{\"s\":\"a\"}",
                Map.of());
    }

    /** IN 缺少 values 失败。 */
    @Test
    @DisplayName("IN 缺少 values 失败")
    void evaluate_inWithoutValues_fails() {
        assertInvalid(
                condition(
                        "{\"source\":\"RUN_INPUT\",\"path\":\"s\",\"valueType\":\"STRING\"}",
                        "IN",
                        "{\"valueType\":\"STRING\",\"value\":\"a\"}"),
                "{\"s\":\"a\"}",
                Map.of());
    }

    /** 条件缺失失败。 */
    @Test
    @DisplayName("条件配置缺失失败")
    void evaluate_missingCondition_fails() {
        assertInvalid(null, "{}", Map.of());
    }

    /**
     * 执行求值。
     *
     * @param conditionJson 条件 JSON
     * @param inputsJson 运行输入
     * @param outputs 上游输出
     * @return 分支
     */
    private WorkflowEdgeBranch evaluate(
            String conditionJson, String inputsJson, Map<String, WorkflowNodeOutput> outputs) {
        return evaluator.evaluate(execution(conditionJson, inputsJson, outputs));
    }

    /**
     * 断言条件非法。
     *
     * @param conditionJson 条件 JSON
     * @param inputsJson 运行输入
     * @param outputs 上游输出
     */
    private void assertInvalid(String conditionJson, String inputsJson, Map<String, WorkflowNodeOutput> outputs) {
        assertThatThrownBy(() -> evaluate(conditionJson, inputsJson, outputs))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_CONDITION_INVALID));
    }

    /**
     * 构造执行上下文。
     *
     * @param conditionJson 条件 JSON（为空表示不配置条件）
     * @param inputsJson 运行输入
     * @param outputs 上游输出
     * @return 执行上下文
     */
    private static WorkflowNodeExecution execution(
            String conditionJson, String inputsJson, Map<String, WorkflowNodeOutput> outputs) {
        String conditionConfig =
                conditionJson == null || conditionJson.isBlank() ? "{}" : "{\"condition\":" + conditionJson + "}";
        String graph = "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"r\",\"type\":\"RETRIEVAL\",\"name\":\"检索\","
                + "\"config\":{\"queryTemplate\":\"q\",\"topK\":3}},"
                + "{\"id\":\"m\",\"type\":\"MODEL\",\"name\":\"模型\","
                + "\"config\":{\"promptTemplate\":\"m\",\"outputKind\":\"TEXT\"}},"
                + "{\"id\":\"c\",\"type\":\"CONDITION\",\"name\":\"条件\",\"config\":" + conditionConfig + "}"
                + "],\"edges\":[]}";
        return WorkflowNodeTestFixtures.execution(
                graph,
                "c",
                inputsJson,
                outputs,
                List.of(),
                WorkflowNodeTestFixtures.futureDeadline(),
                WorkflowNodeTestFixtures.activeGuard());
    }

    /**
     * 构造条件 JSON。
     *
     * @param left 左值 JSON
     * @param operator 算子
     * @param right 右值 JSON
     * @return 条件 JSON
     */
    private static String condition(String left, String operator, String right) {
        return "{\"left\":" + left + ",\"operator\":\"" + operator + "\",\"right\":" + right + "}";
    }
}
