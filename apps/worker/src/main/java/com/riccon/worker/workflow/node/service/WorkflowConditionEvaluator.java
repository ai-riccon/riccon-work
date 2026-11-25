package com.riccon.worker.workflow.node.service;

import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowConditionSource;
import com.riccon.platform.domain.workflow.WorkflowConditionValueType;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphCondition;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphConditionRight;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphConditionSource;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphNode;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 条件节点封闭求值（ADR-0024 §4.5，AC-09）。
 *
 * <p>只支持 {@code EQ/NE/GT/GTE/LT/LTE/IN} 与受控数据源：
 * <ul>
 *   <li>运行输入字段（{@code RUN_INPUT}）；
 *   <li>已执行上游节点的受控输出字段：{@code hitCount}/{@code classificationLabel}/
 *       {@code decision}/{@code status}。
 * </ul>
 * 左右类型必须一致且与声明匹配；缺值、类型不符、算子与右值形态不匹配一律
 * {@code WF_CONDITION_INVALID}，不默认走任一分支。没有表达式、脚本或隐式类型转换。
 */
@Component
public class WorkflowConditionEvaluator {

    /** 受控节点输出字段。 */
    private static final Set<String> NODE_OUTPUT_PATHS =
            Set.of("hitCount", "classificationLabel", "decision", "status");

    /**
     * 求值条件。
     *
     * @param execution 节点执行上下文
     * @return 命中的分支
     * @throws WorkflowNodeFailureException 条件非法（{@code WF_CONDITION_INVALID}）
     */
    public WorkflowEdgeBranch evaluate(WorkflowNodeExecution execution) {
        WorkflowGraphCondition condition = execution.node().config().condition();
        if (condition == null) {
            throw invalid("条件节点缺少 condition 配置");
        }
        WorkflowGraphConditionSource left = condition.left();
        Object leftValue = leftValue(execution, left);
        Object rightValue = rightValue(condition.right(), left.valueType());
        boolean matched = compare(condition.operator(), left.valueType(), leftValue, rightValue, condition.right());
        return matched ? WorkflowEdgeBranch.TRUE : WorkflowEdgeBranch.FALSE;
    }

    /**
     * 读取左值。
     *
     * @param execution 执行上下文
     * @param left 左值配置
     * @return 标量值（BigDecimal/String/Boolean）
     */
    private Object leftValue(WorkflowNodeExecution execution, WorkflowGraphConditionSource left) {
        if (left.source() == WorkflowConditionSource.RUN_INPUT) {
            JsonNode value = execution.inputs().get(left.path());
            if (value == null || value.isNull()) {
                throw invalid("条件引用的运行输入缺失：" + left.path());
            }
            return convertJson(value, left.valueType(), left.path());
        }
        String nodeId = left.nodeId();
        if (nodeId == null || nodeId.isBlank()) {
            throw invalid("NODE_OUTPUT 条件必须指定上游节点");
        }
        if (!NODE_OUTPUT_PATHS.contains(left.path())) {
            throw invalid("条件输出字段不在白名单内：" + left.path());
        }
        WorkflowGraphNode referenced = execution.graph().findNode(nodeId).orElse(null);
        if (referenced == null) {
            throw invalid("条件引用的节点不在发布图中：" + nodeId);
        }
        requirePathNodeType(left.path(), referenced.type());
        WorkflowNodeOutput output = execution.upstreamOutputs().get(nodeId);
        if (output == null) {
            throw invalid("条件引用的上游节点尚未执行：" + nodeId);
        }
        JsonNode value = output.output().get(left.path());
        if (value == null || value.isNull()) {
            throw invalid("上游节点输出缺少条件字段：" + left.path());
        }
        return convertJson(value, left.valueType(), left.path());
    }

    /**
     * 校验受控字段与节点类型匹配，防止把任意输出当作条件源。
     *
     * @param path 字段
     * @param type 节点类型
     */
    private static void requirePathNodeType(String path, WorkflowNodeType type) {
        boolean matches = switch (path) {
            case "hitCount" -> type == WorkflowNodeType.RETRIEVAL;
            case "classificationLabel" -> type == WorkflowNodeType.MODEL;
            case "decision" -> type == WorkflowNodeType.APPROVAL;
            case "status" -> type == WorkflowNodeType.MCP_TOOL;
            default -> false;
        };
        if (!matches) {
            throw invalid("条件字段与上游节点类型不匹配：" + path);
        }
    }

    /**
     * 读取右值。
     *
     * @param right 右值配置
     * @param leftType 左值声明类型
     * @return 标量值
     */
    private Object rightValue(WorkflowGraphConditionRight right, WorkflowConditionValueType leftType) {
        if (right.valueType() != leftType) {
            throw invalid("条件左右值类型不一致");
        }
        if (right.values() != null) {
            // IN 的 values 由 compare 统一转换；这里只占位。
            return null;
        }
        if (right.value() == null) {
            throw invalid("条件右值缺失");
        }
        return convertRightScalar(right.value(), right.valueType());
    }

    /**
     * 执行比较。
     *
     * @param operator 算子
     * @param valueType 值类型
     * @param left 左值
     * @param right 右值
     * @param rightConfig 右值配置
     * @return 是否命中 TRUE
     */
    private boolean compare(
            WorkflowConditionOperator operator,
            WorkflowConditionValueType valueType,
            Object left,
            Object right,
            WorkflowGraphConditionRight rightConfig) {
        if (operator == WorkflowConditionOperator.IN) {
            if (rightConfig.value() != null) {
                throw invalid("IN 算子不能使用单值 value");
            }
            List<Object> values = rightConfig.values();
            if (values == null || values.isEmpty()) {
                throw invalid("IN 算子需要非空 values");
            }
            List<Object> converted = new ArrayList<>(values.size());
            for (Object value : values) {
                converted.add(convertRightScalar(value, valueType));
            }
            return converted.stream().anyMatch(candidate -> equalsValue(valueType, left, candidate));
        }
        if (rightConfig.values() != null) {
            throw invalid("非 IN 算子不能使用 values");
        }
        if (operator == WorkflowConditionOperator.GT
                || operator == WorkflowConditionOperator.GTE
                || operator == WorkflowConditionOperator.LT
                || operator == WorkflowConditionOperator.LTE) {
            if (valueType != WorkflowConditionValueType.NUMBER) {
                throw invalid("大小比较只支持 NUMBER");
            }
            int compared = ((BigDecimal) left).compareTo((BigDecimal) right);
            return switch (operator) {
                case GT -> compared > 0;
                case GTE -> compared >= 0;
                case LT -> compared < 0;
                case LTE -> compared <= 0;
                default -> throw invalid("条件算子不受支持");
            };
        }
        boolean equals = equalsValue(valueType, left, right);
        return switch (operator) {
            case EQ -> equals;
            case NE -> !equals;
            default -> throw invalid("条件算子不受支持");
        };
    }

    /**
     * 类型安全相等比较。
     *
     * @param valueType 值类型
     * @param left 左值
     * @param right 右值
     * @return 是否相等
     */
    private static boolean equalsValue(WorkflowConditionValueType valueType, Object left, Object right) {
        return switch (valueType) {
            case NUMBER -> ((BigDecimal) left).compareTo((BigDecimal) right) == 0;
            case STRING, BOOLEAN -> left.equals(right);
        };
    }

    /**
     * 把 JSON 标量转换为声明类型的值。
     *
     * @param value JSON 值
     * @param type 声明类型
     * @param field 字段名（仅用于错误）
     * @return 标量值
     */
    private static Object convertJson(JsonNode value, WorkflowConditionValueType type, String field) {
        return switch (type) {
            case NUMBER -> {
                if (!value.isNumber()) {
                    throw invalid("条件字段不是数字：" + field);
                }
                yield value.decimalValue();
            }
            case STRING -> {
                if (!value.isTextual()) {
                    throw invalid("条件字段不是字符串：" + field);
                }
                yield value.asString();
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw invalid("条件字段不是布尔值：" + field);
                }
                yield value.booleanValue();
            }
        };
    }

    /**
     * 把配置右值转换为声明类型的值。
     *
     * @param value 配置值
     * @param type 声明类型
     * @return 标量值
     */
    private static Object convertRightScalar(Object value, WorkflowConditionValueType type) {
        Objects.requireNonNull(value, "condition right value must not be null");
        return switch (type) {
            case NUMBER -> {
                if (!(value instanceof Number number)) {
                    throw invalid("条件右值不是数字");
                }
                yield new BigDecimal(number.toString());
            }
            case STRING -> {
                if (!(value instanceof String text)) {
                    throw invalid("条件右值不是字符串");
                }
                yield text;
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean flag)) {
                    throw invalid("条件右值不是布尔值");
                }
                yield flag;
            }
        };
    }

    /**
     * 构造条件非法失败。
     *
     * @param message 可读原因
     * @return 失败异常
     */
    private static WorkflowNodeFailureException invalid(String message) {
        return new WorkflowNodeFailureException(WorkflowErrorCode.WF_CONDITION_INVALID, message);
    }
}
