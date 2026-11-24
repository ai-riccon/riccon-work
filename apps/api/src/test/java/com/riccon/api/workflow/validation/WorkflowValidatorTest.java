package com.riccon.api.workflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphFormatException;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.mcp.McpToolRisk;
import com.riccon.platform.domain.workflow.WorkflowValidationCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图校验器单元测试（IT-WF-111、SEC-WF-005 的规则层）。
 *
 * <p>覆盖 AC-02/06/09/18/24：结构（START/END、环、孤立、入度/出度、条件分支、上限）、必填参数、
 * targetNodeKey pattern、模型/工具/审批/条件/结果与发布级审批人可用性。数据库查询用内存桩替代，
 * 真实目录/授权与 API 行为由 {@code WorkflowValidationApiTest} 覆盖。
 */
class WorkflowValidatorTest {

    /** 测试租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 测试操作者。 */
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** 只读查询桩。 */
    private final FakeSupport support = new FakeSupport();

    /** 被测校验器。 */
    private final WorkflowValidator validator = new WorkflowValidator(support);

    /** 合法工具图：保存与发布均通过（发布前排除发布者后仍有审批人）。 */
    @Test
    @DisplayName("合法工具图：SAVE/PUBLISH 通过")
    void validToolGraph_passesSaveAndPublish() {
        UUID toolId = UUID.randomUUID();
        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));

        assertThat(validate(parse(toolGraph(toolId)), WorkflowValidationScope.SAVE)
                        .issues())
                .isEmpty();
        assertThat(validate(parse(toolGraph(toolId)), WorkflowValidationScope.PUBLISH)
                        .valid())
                .isTrue();
    }

    /** 合法条件图：条件源、算子、类型与 TRUE/FALSE 分支均通过。 */
    @Test
    @DisplayName("合法条件图：条件源/算子/分支通过")
    void validConditionGraph_passes() {
        assertThat(validate(parse(conditionGraph(validCondition())), WorkflowValidationScope.SAVE)
                        .issues())
                .isEmpty();
    }

    /** 缺少/多个 START 与缺少 END 返回稳定问题码。 */
    @Test
    @DisplayName("START/END：缺失与重复")
    void startAndEndProblems() {
        WorkflowValidationResult missingStart = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                        ],"edges":[{"id":"e1","source":"model","target":"end"}]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(missingStart.hasIssue(WorkflowValidationCode.MISSING_START)).isTrue();

        WorkflowValidationResult multipleStart = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"s1","type":"START","name":"开始1","config":{}},
                          {"id":"s2","type":"START","name":"开始2","config":{}},
                          {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                        ],"edges":[
                          {"id":"e1","source":"s1","target":"model"},
                          {"id":"e2","source":"model","target":"end"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(multipleStart.hasIssue(WorkflowValidationCode.MULTIPLE_START))
                .isTrue();

        WorkflowValidationResult missingEnd = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}}
                        ],"edges":[{"id":"e1","source":"start","target":"model"}]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(missingEnd.hasIssue(WorkflowValidationCode.MISSING_END)).isTrue();
    }

    /** 环、孤立节点、入度超限与非 END 出度异常。 */
    @Test
    @DisplayName("结构：环/孤立/入度/出度")
    void graphStructureProblems() {
        WorkflowValidationResult cycle = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"a","type":"MODEL","name":"A","config":{"promptTemplate":"a","outputKind":"TEXT"}},
                          {"id":"b","type":"MODEL","name":"B","config":{"promptTemplate":"b","outputKind":"TEXT"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"a"},
                          {"id":"e2","source":"a","target":"b"},
                          {"id":"e3","source":"b","target":"a"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(cycle.hasIssue(WorkflowValidationCode.GRAPH_CYCLE)).isTrue();

        WorkflowValidationResult orphan = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}},
                          {"id":"lonely","type":"MODEL","name":"孤立","config":{"promptTemplate":"y","outputKind":"TEXT"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"model"},
                          {"id":"e2","source":"model","target":"end"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(orphan.issues())
                .anySatisfy(issue -> assertThat(issue.code()).isEqualTo(WorkflowValidationCode.ORPHAN_NODE));
        assertThat(orphan.issues())
                .anySatisfy(issue -> assertThat(issue.nodeId()).isEqualTo("lonely"));

        WorkflowValidationResult indegree = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"a","type":"MODEL","name":"A","config":{"promptTemplate":"a","outputKind":"TEXT"}},
                          {"id":"b","type":"MODEL","name":"B","config":{"promptTemplate":"b","outputKind":"TEXT"}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"a"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"a"},
                          {"id":"e2","source":"start","target":"b"},
                          {"id":"e3","source":"a","target":"end"},
                          {"id":"e4","source":"b","target":"end"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(indegree.hasIssue(WorkflowValidationCode.INDEGREE_EXCEEDED)).isTrue();
        assertThat(indegree.hasIssue(WorkflowValidationCode.EDGE_INVALID)).isTrue();

        WorkflowValidationResult deadEnd = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}}
                        ],"edges":[{"id":"e1","source":"start","target":"model"}]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(deadEnd.hasIssue(WorkflowValidationCode.EDGE_INVALID)).isTrue();
    }

    /** 条件节点出边数量与分支标记。 */
    @Test
    @DisplayName("条件分支：出边数量/分支标记/非条件边")
    void conditionBranchProblems() {
        String oneBranch = conditionGraph(validCondition())
                .replace("{\"id\":\"e5\",\"source\":\"condition\",\"target\":\"modelFalse\",\"branch\":\"FALSE\"},", "")
                .replace(
                        ",{\"id\":\"e5\",\"source\":\"condition\",\"target\":\"modelFalse\",\"branch\":\"FALSE\"}", "");
        assertThat(validate(parse(oneBranch), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String duplicateBranches =
                conditionGraph(validCondition()).replace("\"branch\":\"FALSE\"", "\"branch\":\"TRUE\"");
        assertThat(validate(parse(duplicateBranches), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String branchOnModel = conditionGraph(validCondition())
                .replace(
                        "\"id\":\"e2\",\"source\":\"retrieval\",\"target\":\"condition\"",
                        "\"id\":\"e2\",\"source\":\"retrieval\",\"target\":\"condition\",\"branch\":\"TRUE\"");
        assertThat(validate(parse(branchOnModel), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.EDGE_INVALID))
                .isTrue();
    }

    /** targetNodeKey 契约 pattern（F-002）。 */
    @Test
    @DisplayName("targetNodeKey pattern：拒绝 bad key")
    void targetNodeKeyPattern_isEnforced() {
        UUID toolId = UUID.randomUUID();
        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));
        String badKey = toolGraph(toolId).replace("\"targetNodeKey\":\"tool\"", "\"targetNodeKey\":\"bad key\"");

        WorkflowValidationResult result = validate(parse(badKey), WorkflowValidationScope.SAVE);

        assertThat(result.hasIssue(WorkflowValidationCode.APPROVAL_CONFIG)).isTrue();
        assertThat(result.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.targetNodeKey"));
    }

    /** 各节点类型必填参数。 */
    @Test
    @DisplayName("必填参数：检索/模型/分类/条件/结果")
    void requiredConfigProblems() {
        WorkflowValidationResult retrieval = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"retrieval"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"retrieval"},
                          {"id":"e2","source":"retrieval","target":"end"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(retrieval.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.queryTemplate"));
        assertThat(retrieval.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.topK"));

        WorkflowValidationResult model = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"model","type":"MODEL","name":"模型","config":{"outputKind":"CLASSIFICATION"}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"model"},
                          {"id":"e2","source":"model","target":"end"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(model.issues()).anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.promptTemplate"));
        assertThat(model.issues()).anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.labels"));

        WorkflowValidationResult condition = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"condition","type":"CONDITION","name":"条件","config":{}},
                          {"id":"end","type":"END","name":"结束","config":{"resultFrom":"condition"}}
                        ],"edges":[
                          {"id":"e1","source":"start","target":"condition"},
                          {"id":"e2","source":"condition","target":"end","branch":"TRUE"},
                          {"id":"e3","source":"condition","target":"end","branch":"FALSE"}
                        ]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(condition.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.condition"));

        WorkflowValidationResult end = validate(parse("""
                        {"schemaVersion":1,"nodes":[
                          {"id":"start","type":"START","name":"开始","config":{}},
                          {"id":"end","type":"END","name":"结束","config":{}}
                        ],"edges":[{"id":"e1","source":"start","target":"end"}]}
                        """), WorkflowValidationScope.SAVE);
        assertThat(end.issues()).anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.resultFrom"));
    }

    /** 模型必须在 REPORT 链且 ACTIVE。 */
    @Test
    @DisplayName("模型：不在 REPORT 链返回 MODEL_UNAVAILABLE")
    void modelAvailability_isChecked() {
        UUID toolId = UUID.randomUUID();
        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));
        UUID modelId = UUID.randomUUID();
        String graph = toolGraph(toolId)
                .replace(
                        "{\"promptTemplate\":\"生成\",\"outputKind\":\"TEXT\"}",
                        "{\"promptTemplate\":\"生成\",\"outputKind\":\"TEXT\",\"modelId\":\"" + modelId + "\"}");

        WorkflowValidationResult unavailable = validate(parse(graph), WorkflowValidationScope.SAVE);
        assertThat(unavailable.hasIssue(WorkflowValidationCode.MODEL_UNAVAILABLE))
                .isTrue();

        support.availableModels.add(modelId);
        assertThat(validate(parse(graph), WorkflowValidationScope.SAVE).issues())
                .isEmpty();
    }

    /** 工具授权、停用与审批前驱绑定。 */
    @Test
    @DisplayName("工具：未授权/停用/审批前驱")
    void toolAuthorizationAndApprovalPredecessor() {
        UUID toolId = UUID.randomUUID();
        String graph = toolGraph(toolId);

        support.tools.put(toolId, WorkflowToolAvailability.notAuthorized());
        assertThat(validate(parse(graph), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.TOOL_NOT_AUTHORIZED))
                .isTrue();

        support.tools.put(toolId, WorkflowToolAvailability.toolDisabled());
        assertThat(validate(parse(graph), WorkflowValidationScope.SAVE).hasIssue(WorkflowValidationCode.TOOL_DISABLED))
                .isTrue();

        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));
        String withoutApproval = """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"tool","type":"MCP_TOOL","name":"工具","config":{"toolId":"%s"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"tool"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"tool"},
                  {"id":"e2","source":"tool","target":"end"}
                ]}
                """.formatted(toolId);
        assertThat(validate(parse(withoutApproval), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.APPROVAL_CONFIG))
                .isTrue();

        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, false));
        assertThat(validate(parse(withoutApproval), WorkflowValidationScope.SAVE)
                        .issues())
                .isEmpty();
    }

    /** 审批节点配置、动作绑定与发布级可用审批人。 */
    @Test
    @DisplayName("审批：assignee/actionKind/targetNodeKey/NO_APPROVER")
    void approvalConfigurationAndAvailability() {
        UUID toolId = UUID.randomUUID();
        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));

        String wrongTarget = toolGraph(toolId).replace("\"targetNodeKey\":\"tool\"", "\"targetNodeKey\":\"report\"");
        assertThat(validate(parse(wrongTarget), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.APPROVAL_CONFIG))
                .isTrue();

        String userApproval = """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"approval","type":"APPROVAL","name":"审批","config":{
                    "assigneeType":"USER","assigneeUserIds":["%s"],"actionKind":"CONTINUE"}},
                  {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"approval"},
                  {"id":"e2","source":"approval","target":"model"},
                  {"id":"e3","source":"model","target":"end"}
                ]}
                """.formatted(ACTOR);
        assertThat(validate(parse(userApproval), WorkflowValidationScope.SAVE).issues())
                .isEmpty();
        support.approvers = 0;
        WorkflowValidationResult publish = validate(parse(userApproval), WorkflowValidationScope.PUBLISH);
        assertThat(publish.hasIssue(WorkflowValidationCode.NO_APPROVER)).isTrue();
        assertThat(publish.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("config.assigneeUserIds"));

        String conflictingAssignee = userApproval.replace(
                "\"assigneeType\":\"USER\"", "\"assigneeType\":\"USER\",\"assigneeRole\":\"MEMBER\"");
        assertThat(validate(parse(conflictingAssignee), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.APPROVAL_CONFIG))
                .isTrue();
    }

    /** 条件算子、值类型、受控字段与上游引用。 */
    @Test
    @DisplayName("条件：算子/值类型/受控字段/上游")
    void conditionValueAndSourceProblems() {
        String numericString = conditionGraph("""
                {"left":{"source":"RUN_INPUT","path":"topic","valueType":"STRING"},"operator":"GT",
                 "right":{"valueType":"STRING","value":"x"}}
                """);
        assertThat(validate(parse(numericString), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String mismatch = conditionGraph("""
                {"left":{"source":"RUN_INPUT","path":"topic","valueType":"NUMBER"},"operator":"EQ",
                 "right":{"valueType":"STRING","value":"x"}}
                """);
        assertThat(validate(parse(mismatch), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String inWithoutValues = conditionGraph("""
                {"left":{"source":"RUN_INPUT","path":"topic","valueType":"STRING"},"operator":"IN",
                 "right":{"valueType":"STRING"}}
                """);
        assertThat(validate(parse(inWithoutValues), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String wrongValueType = conditionGraph("""
                {"left":{"source":"RUN_INPUT","path":"topic","valueType":"NUMBER"},"operator":"EQ",
                 "right":{"valueType":"NUMBER","value":"x"}}
                """);
        assertThat(validate(parse(wrongValueType), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String wrongSourceType = conditionGraph("""
                {"left":{"source":"NODE_OUTPUT","nodeId":"modelTrue","path":"hitCount","valueType":"NUMBER"},
                 "operator":"GTE","right":{"valueType":"NUMBER","value":1}}
                """);
        assertThat(validate(parse(wrongSourceType), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String downstreamSource = conditionGraph("""
                {"left":{"source":"NODE_OUTPUT","nodeId":"modelTrue","path":"classificationLabel","valueType":"STRING"},
                 "operator":"EQ","right":{"valueType":"STRING","value":"HIGH"}}
                """);
        assertThat(validate(parse(downstreamSource), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();

        String runInputWithNodeId = conditionGraph("""
                {"left":{"source":"RUN_INPUT","nodeId":"retrieval","path":"topic","valueType":"STRING"},
                 "operator":"EQ","right":{"valueType":"STRING","value":"x"}}
                """);
        assertThat(validate(parse(runInputWithNodeId), WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.CONDITION_BRANCH_INVALID))
                .isTrue();
    }

    /** END 结果来源必须存在、非 START 且为上游。 */
    @Test
    @DisplayName("结果：resultFrom 不存在/START/下游")
    void resultFromProblems() {
        UUID toolId = UUID.randomUUID();
        support.tools.put(toolId, WorkflowToolAvailability.available(McpToolRisk.READ, true));
        String graph = toolGraph(toolId);

        assertThat(validate(
                                parse(graph.replace("\"resultFrom\":\"report\"", "\"resultFrom\":\"missing\"")),
                                WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.RESULT_INVALID))
                .isTrue();
        assertThat(validate(
                                parse(graph.replace("\"resultFrom\":\"report\"", "\"resultFrom\":\"start\"")),
                                WorkflowValidationScope.SAVE)
                        .hasIssue(WorkflowValidationCode.RESULT_INVALID))
                .isTrue();
        assertThat(validate(
                                parse(graph.replace("\"resultFrom\":\"report\"", "\"resultFrom\":\"tool\"")),
                                WorkflowValidationScope.SAVE)
                        .issues())
                .isEmpty();
    }

    /** 节点/边上限在 JSON 解析阶段拒绝（IT-WF-111 的形状部分）。 */
    @Test
    @DisplayName("上限：51 节点/101 边在解析阶段拒绝")
    void limitsAreRejectedDuringParsing() {
        String nodes = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> "{\"id\":\"n" + index + "\",\"type\":\"START\",\"name\":\"n\",\"config\":{}}")
                .collect(Collectors.joining(","));
        String tooManyNodes = "{\"schemaVersion\":1,\"nodes\":[" + nodes + "],\"edges\":[]}";
        assertThatThrownBy(() -> WorkflowGraphJson.parse(tooManyNodes))
                .isInstanceOf(WorkflowGraphFormatException.class);

        String edges = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "{\"id\":\"e" + index + "\",\"source\":\"s\",\"target\":\"e\"}")
                .collect(Collectors.joining(","));
        String tooManyEdges = "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"s\",\"type\":\"START\",\"name\":\"s\",\"config\":{}},"
                + "{\"id\":\"e\",\"type\":\"END\",\"name\":\"e\",\"config\":{}}"
                + "],\"edges\":[" + edges + "]}";
        assertThatThrownBy(() -> WorkflowGraphJson.parse(tooManyEdges))
                .isInstanceOf(WorkflowGraphFormatException.class);
    }

    /**
     * 解析图 JSON。
     *
     * @param json JSON 文本
     * @return 图
     */
    private static WorkflowGraph parse(String json) {
        return WorkflowGraphJson.parse(json);
    }

    /**
     * 按阶段校验。
     *
     * @param graph 图
     * @param scope 阶段
     * @return 校验结果
     */
    private WorkflowValidationResult validate(WorkflowGraph graph, WorkflowValidationScope scope) {
        return validator.validate(graph, new WorkflowValidationRequest(TENANT, ACTOR, scope));
    }

    /**
     * 构造含工具审批链的合法图。
     *
     * @param toolId 工具 ID
     * @return JSON
     */
    private static String toolGraph(UUID toolId) {
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{"queryTemplate":"{{input.topic}}","topK":5}},
                  {"id":"draft","type":"MODEL","name":"草稿","config":{"promptTemplate":"整理","outputKind":"TEXT"}},
                  {"id":"approval","type":"APPROVAL","name":"审批","config":{
                    "assigneeType":"ROLE","assigneeRole":"TENANT_ADMIN","actionKind":"MCP_TOOL","targetNodeKey":"tool"}},
                  {"id":"tool","type":"MCP_TOOL","name":"取数","config":{"toolId":"%s","argsTemplate":{"query":"{{input.topic}}"}}},
                  {"id":"report","type":"MODEL","name":"报告","config":{"promptTemplate":"生成","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"report"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"retrieval"},
                  {"id":"e2","source":"retrieval","target":"draft"},
                  {"id":"e3","source":"draft","target":"approval"},
                  {"id":"e4","source":"approval","target":"tool"},
                  {"id":"e5","source":"tool","target":"report"},
                  {"id":"e6","source":"report","target":"end"}
                ]}
                """.formatted(toolId);
    }

    /**
     * 构造含条件分支的合法图。
     *
     * @param condition 条件配置 JSON
     * @return JSON
     */
    private static String conditionGraph(String condition) {
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{"queryTemplate":"{{input.topic}}","topK":5}},
                  {"id":"condition","type":"CONDITION","name":"条件","config":{"condition":%s}},
                  {"id":"modelTrue","type":"MODEL","name":"真","config":{"promptTemplate":"真","outputKind":"TEXT"}},
                  {"id":"modelFalse","type":"MODEL","name":"假","config":{"promptTemplate":"假","outputKind":"TEXT"}},
                  {"id":"endTrue","type":"END","name":"结束真","config":{"resultFrom":"modelTrue"}},
                  {"id":"endFalse","type":"END","name":"结束假","config":{"resultFrom":"modelFalse"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"retrieval"},
                  {"id":"e2","source":"retrieval","target":"condition"},
                  {"id":"e3","source":"condition","target":"modelTrue","branch":"TRUE"},
                  {"id":"e4","source":"modelTrue","target":"endTrue"},
                  {"id":"e5","source":"condition","target":"modelFalse","branch":"FALSE"},
                  {"id":"e6","source":"modelFalse","target":"endFalse"}
                ]}
                """.formatted(condition);
    }

    /**
     * 返回合法条件配置。
     *
     * @return 条件 JSON
     */
    private static String validCondition() {
        return """
                {"left":{"source":"NODE_OUTPUT","nodeId":"retrieval","path":"hitCount","valueType":"NUMBER"},
                 "operator":"GTE","right":{"valueType":"NUMBER","value":1}}
                """;
    }

    /**
     * 内存查询桩。
     *
     * <p>默认所有引用的模型都不可用、工具未授权、审批人 1 个；测试按需覆盖，避免隐式依赖真实目录。
     */
    private static final class FakeSupport implements WorkflowValidationSupport {

        /** 可用的 REPORT 模型。 */
        private final Set<UUID> availableModels = new HashSet<>();

        /** 工具状态。 */
        private final Map<UUID, WorkflowToolAvailability> tools = new HashMap<>();

        /** 可用审批人数。 */
        private int approvers = 1;

        @Override
        public Set<UUID> findAvailableReportModels(Set<UUID> modelIds) {
            return modelIds.stream().filter(availableModels::contains).collect(Collectors.toSet());
        }

        @Override
        public Map<UUID, WorkflowToolAvailability> toolAvailabilities(TenantId tenantId, Set<UUID> toolIds) {
            Map<UUID, WorkflowToolAvailability> states = new LinkedHashMap<>();
            for (UUID toolId : toolIds) {
                states.put(toolId, tools.getOrDefault(toolId, WorkflowToolAvailability.notAuthorized()));
            }
            return states;
        }

        @Override
        public int countActiveApprovers(TenantId tenantId, WorkflowApproverQuery query) {
            return approvers;
        }
    }
}
