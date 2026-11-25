package com.riccon.platform.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M06 编排 Studio 共享词汇守卫。
 *
 * <p>本测试是契约测试而非业务测试：workflow 包的枚举名同时出现在 Flyway CHECK 约束、OpenAPI 枚举、
 * Worker 写库与 API 响应中，改名或增删必须先改 ADR-0024、迁移与契约。这里锁定 18 个封闭枚举的
 * 取值顺序与命名格式，并验证关闭集合对未知值、空值与空引用的拒绝边界，避免有代码把节点类型或状态
 * 写成字符串字面量绕过契约。
 */
class WorkflowVocabularyTest {

    /** 工作流全部共享枚举；参数化加载同时验证每个枚举都被真实初始化并含常量。 */
    private static Stream<Class<? extends Enum<?>>> workflowEnums() {
        return Stream.of(
                WorkflowNodeType.class,
                WorkflowNodeStatus.class,
                WorkflowRunStatus.class,
                WorkflowRunScope.class,
                WorkflowApprovalStatus.class,
                WorkflowApprovalAssigneeType.class,
                WorkflowApprovalActionKind.class,
                WorkflowApprovalDecision.class,
                WorkflowConditionOperator.class,
                WorkflowConditionSource.class,
                WorkflowConditionValueType.class,
                WorkflowEdgeBranch.class,
                WorkflowDefinitionStatus.class,
                WorkflowResultStatus.class,
                WorkflowErrorCode.class,
                WorkflowValidationCode.class,
                WorkflowTemplateSource.class,
                WorkflowTemplateVisibility.class);
    }

    /** 读取枚举常量名，便于逐项断言。 */
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** 把枚举常量数组转成列表，便于对 {@code Enum<?>} 做统一断言。 */
    private static List<Enum<?>> constants(Class<? extends Enum<?>> enumType) {
        return Arrays.asList(enumType.getEnumConstants());
    }

    /** 每个工作流枚举都是非空封闭集合：常量名唯一、大写 + 下划线，且 toString 与 name 一致（禁止自定义展示值漂移契约）。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("workflowEnums")
    void workflowEnums_areClosedNameSets(Class<? extends Enum<?>> enumType) {
        List<Enum<?>> values = constants(enumType);
        assertThat(values).as(enumType.getSimpleName()).isNotEmpty();
        assertThat(names(enumType.getEnumConstants()))
                .as(enumType.getSimpleName())
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("^[A-Z][A-Z0-9_]*$"));
        assertThat(values).allSatisfy(value -> assertThat(value.toString()).isEqualTo(value.name()));
    }

    /** 每个常量都能通过 valueOf 往返，保证 API/Worker 反序列化与数据库回读不会出现“未知枚举”。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("workflowEnums")
    void workflowEnums_roundTripThroughValueOf(Class<? extends Enum<?>> enumType) throws Exception {
        Method valueOf = enumType.getMethod("valueOf", String.class);
        for (Enum<?> value : constants(enumType)) {
            assertThat(valueOf.invoke(null, value.name()))
                    .as("%s.%s", enumType.getSimpleName(), value.name())
                    .isSameAs(value);
        }
    }

    /** 封闭集合必须拒绝未登记的值（含空串）与 null，防止运行时静默落入某个分支或默认节点。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("workflowEnums")
    void workflowEnums_rejectUnknownAndNullValues(Class<? extends Enum<?>> enumType) throws Exception {
        Method valueOf = enumType.getMethod("valueOf", String.class);
        assertThatThrownBy(() -> valueOf.invoke(null, "NOT_A_WORKFLOW_VALUE"))
                .as("%s 拒绝未知值", enumType.getSimpleName())
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valueOf.invoke(null, ""))
                .as("%s 拒绝空串", enumType.getSimpleName())
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valueOf.invoke(null, (Object) null))
                .as("%s 拒绝 null", enumType.getSimpleName())
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(NullPointerException.class);
    }

    /** 节点类型必须与 ADR-0024 §4.1/§9.3 的 7 类最小集完全一致。 */
    @Test
    void nodeType_matchesClosedAdrSet() {
        assertThat(names(WorkflowNodeType.values()))
                .containsExactly("START", "END", "RETRIEVAL", "MODEL", "MCP_TOOL", "APPROVAL", "CONDITION");
    }

    /** M06 明确不支持代码执行、脚本、任意 HTTP、并行、循环、子流程与定时节点；这些值必须被拒绝。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CODE", "SCRIPT", "HTTP", "PARALLEL", "LOOP", "SUBFLOW", "CRON"})
    void nodeType_rejectsOutOfScopeNodeKinds(String outOfScopeType) {
        assertThatThrownBy(() -> WorkflowNodeType.valueOf(outOfScopeType)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 运行状态必须与 {@code workflow_run.status} CHECK 约束一致：审批等待占用准入并发但不占执行槽位。 */
    @Test
    void runStatus_matchesStateMachine() {
        assertThat(names(WorkflowRunStatus.values()))
                .containsExactly("PENDING", "RUNNING", "WAITING_APPROVAL", "SUCCEEDED", "FAILED", "CANCELLED");
    }

    /** 节点状态必须与 {@code workflow_node_run.status} CHECK 约束一致，未走分支用 SKIPPED 而不是 FAILED。 */
    @Test
    void nodeStatus_matchesStateMachine() {
        assertThat(names(WorkflowNodeStatus.values()))
                .containsExactly(
                        "PENDING", "RUNNING", "WAITING_APPROVAL", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");
    }

    /** 审批状态与 ADR-0024 §5.4 一致：M06 审批不过期，取消运行只作废 PENDING 记录。 */
    @Test
    void approvalStatus_matchesStateMachine() {
        assertThat(names(WorkflowApprovalStatus.values()))
                .containsExactly("PENDING", "APPROVED", "REJECTED", "CANCELLED");
    }

    /** 流程定义与结果保存状态必须稳定：M06 不允许删除流程，结果超限不得静默截断。 */
    @Test
    void definitionAndResultStatuses_areStable() {
        assertThat(names(WorkflowDefinitionStatus.values())).containsExactly("ACTIVE", "DISABLED");
        assertThat(names(WorkflowResultStatus.values())).containsExactly("NONE", "SAVED", "OVER_LIMIT");
    }

    /** 条件词汇是封闭白名单：算子、左值来源、值类型与出边分支不得扩展为表达式或三值分支。 */
    @Test
    void conditionVocabulary_isClosed() {
        assertThat(names(WorkflowConditionOperator.values()))
                .containsExactly("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN");
        assertThat(names(WorkflowConditionSource.values())).containsExactly("RUN_INPUT", "NODE_OUTPUT");
        assertThat(names(WorkflowConditionValueType.values())).containsExactly("NUMBER", "STRING", "BOOLEAN");
        assertThat(names(WorkflowEdgeBranch.values())).containsExactly("TRUE", "FALSE");
    }

    /** 运行范围与审批词汇必须稳定：范围是发布快照，审批人解析与解锁动作决定是否暂停运行。 */
    @Test
    void runScopeAndApprovalVocabulary_areStable() {
        assertThat(names(WorkflowRunScope.values())).containsExactly("ONLY_SELF", "DEPARTMENT", "TENANT");
        assertThat(names(WorkflowApprovalAssigneeType.values())).containsExactly("USER", "ROLE");
        assertThat(names(WorkflowApprovalActionKind.values())).containsExactly("MCP_TOOL", "MODEL", "CONTINUE");
        assertThat(names(WorkflowApprovalDecision.values())).containsExactly("APPROVE", "REJECT");
    }

    /** 模板来源与可见范围必须区分全局内置目录与租户用户模板（M06b 才开放 TENANT 共享）。 */
    @Test
    void templateVocabulary_isStable() {
        assertThat(names(WorkflowTemplateSource.values())).containsExactly("BUILT_IN", "TENANT");
        assertThat(names(WorkflowTemplateVisibility.values())).containsExactly("BUILT_IN", "PRIVATE", "TENANT");
    }

    /** 异步错误码必须与契约完全一致、以 {@code WF_} 命名空间标识，且不是 HTTP 状态码或 RICCON 前缀码。 */
    @Test
    void errorCodes_matchContractVocabulary() {
        List<String> codes = names(WorkflowErrorCode.values());
        assertThat(codes)
                .containsExactly(
                        "WF_INVALID_GRAPH",
                        "WF_NODE_LIMIT",
                        "WF_STEP_LIMIT",
                        "WF_RUN_TIMEOUT",
                        "WF_NODE_TIMEOUT",
                        "WF_MODEL_UNAVAILABLE",
                        "WF_MODEL_OUTPUT_INVALID",
                        "WF_RETRIEVAL_FAILED",
                        "WF_CONDITION_INVALID",
                        "WF_TOOL_NOT_AUTHORIZED",
                        "WF_TOOL_RESULT_LIMIT",
                        "WF_APPROVAL_REQUIRED",
                        "WF_NO_APPROVER",
                        "WF_APPROVAL_REJECTED",
                        "WF_SIDE_EFFECT_UNKNOWN",
                        "WF_PLAN_DRIFT",
                        "WF_RESULT_LIMIT",
                        "WF_FEATURE_DISABLED",
                        "WF_CANCELLED",
                        "WF_INTERNAL")
                .doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).startsWith("WF_").doesNotStartWith("RICCON-"));
    }

    /**
     * 校验问题码必须与契约 {@code WorkflowValidationCode} 完全一致。
     *
     * <p>服务端在保存/发布/运行前三处逐项返回这些码，UI 据此定位画布节点；漏项会让问题无法渲染。
     */
    @Test
    void validationCodes_matchContractVocabulary() {
        assertThat(names(WorkflowValidationCode.values()))
                .containsExactly(
                        "GRAPH_CYCLE",
                        "MISSING_START",
                        "MULTIPLE_START",
                        "MISSING_END",
                        "ORPHAN_NODE",
                        "INDEGREE_EXCEEDED",
                        "EDGE_INVALID",
                        "CONDITION_BRANCH_INVALID",
                        "REQUIRED_CONFIG",
                        "NODE_LIMIT",
                        "EDGE_LIMIT",
                        "MODEL_UNAVAILABLE",
                        "TOOL_NOT_AUTHORIZED",
                        "TOOL_DISABLED",
                        "APPROVAL_CONFIG",
                        "NO_APPROVER",
                        "RESULT_INVALID",
                        "TEMPLATE_INVALID")
                .doesNotHaveDuplicates();
    }

    /**
     * 内置模板键与固定 ID 必须稳定，且 ID 是 UUIDv7。
     *
     * <p>V15 种子、API 读取与模板复制共用该 ID；升级改变它会让已有租户引用的内置模板失联。
     */
    @Test
    void templateKeys_areStableAndUuidV7() {
        assertThat(WorkflowTemplateKeys.MARKET_RESEARCH)
                .isEqualTo("market-research")
                .matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
        assertThat(WorkflowTemplateKeys.MARKET_RESEARCH_ID).isEqualTo("01990000-0000-7000-8000-000000000601");

        UUID templateId = UUID.fromString(WorkflowTemplateKeys.MARKET_RESEARCH_ID);
        assertThat(templateId.version()).as("内置模板 ID 必须是 UUIDv7").isEqualTo(7);
        assertThat(templateId.variant()).as("内置模板 ID 必须是 IETF 变体").isEqualTo(2);
        assertThat(WorkflowTemplateKeys.MARKET_RESEARCH_ID).isNotEqualTo(WorkflowTemplateKeys.MARKET_RESEARCH);
    }

    /** 模板键工具类必须 final 且只有私有构造器，禁止被实例化或继承后写入可变状态。 */
    @Test
    void templateKeys_cannotBeInstantiated() {
        assertThat(Modifier.isFinal(WorkflowTemplateKeys.class.getModifiers())).isTrue();
        Constructor<?>[] constructors = WorkflowTemplateKeys.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }
}
