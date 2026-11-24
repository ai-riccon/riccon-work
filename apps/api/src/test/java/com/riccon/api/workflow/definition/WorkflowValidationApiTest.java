package com.riccon.api.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 图校验 API 集成测试（IT-WF-111、SEC-WF-005，AC-02/06/09/18/24）。
 *
 * <p>覆盖：合法草稿保存与校验通过；非法图保存 400 {@code RICCON-WF-400} 且不写库/不写审计；
 * targetNodeKey pattern；脚本/HTTP/并行/循环/定时等负向节点类型；模型/工具/审批/条件/结果/上限；校验端点
 * 权限与“不改变草稿版本”。真实 MySQL + Flyway V15 + 真实会话/CSRF/权限链；不写 E2E（T16a）。
 */
class WorkflowValidationApiTest extends ApiIntegrationTestSupport {

    /** 测试 JSON 编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 保存非法图统一错误码。 */
    private static final String WF_400 = "RICCON-WF-400";

    /** V15 种子的内置市场调研模板 ID。 */
    private static final UUID TEMPLATE_ID = UUID.fromString("01990000-0000-7000-8000-000000000601");

    /** 本测试创建的模型 ID，用于清理全局目录。 */
    private final List<UUID> createdModelIds = new ArrayList<>();

    /** 本测试创建的 provider ID，用于清理全局目录。 */
    private final List<UUID> createdProviderIds = new ArrayList<>();

    /**
     * 清理测试写入的全局模型目录，避免污染同 JVM 的其他模型目录用例。
     *
     * <p>只删除本测试创建的 provider/model/routing 行；不触碰租户数据与 MCP 目录。
     */
    @AfterEach
    void cleanGlobalModelCatalog() {
        if (createdModelIds.isEmpty()) {
            return;
        }
        List<byte[]> modelIds =
                createdModelIds.stream().map(BinaryUuids::toBytes).toList();
        jdbcClient
                .sql("DELETE FROM model_routing_entry WHERE model_id IN (:ids)")
                .param("ids", modelIds)
                .update();
        jdbcClient
                .sql("DELETE FROM model WHERE id IN (:ids)")
                .param("ids", modelIds)
                .update();
        List<byte[]> providerIds =
                createdProviderIds.stream().map(BinaryUuids::toBytes).toList();
        jdbcClient
                .sql("DELETE FROM model_provider WHERE id IN (:ids)")
                .param("ids", providerIds)
                .update();
        createdModelIds.clear();
        createdProviderIds.clear();
    }

    /** 合法模板等价图：开始→检索→草稿模型→审批→工具（工具留空）→报告模型→结束。 */
    private static final String TEMPLATE_GRAPH = """
            {"schemaVersion":1,"nodes":[
              {"id":"start","type":"START","name":"开始","config":{}},
              {"id":"retrieval","type":"RETRIEVAL","name":"检索知识库","config":{"queryTemplate":"{{input.topic}}","topK":5}},
              {"id":"draft","type":"MODEL","name":"生成草稿","config":{"promptTemplate":"整理要点","outputKind":"TEXT"}},
              {"id":"approval","type":"APPROVAL","name":"审批取数","config":{
                "assigneeType":"ROLE","assigneeRole":"TENANT_ADMIN","actionKind":"MCP_TOOL","targetNodeKey":"tool"}},
              {"id":"tool","type":"MCP_TOOL","name":"取数","config":{"toolId":null,"argsTemplate":{"query":"{{input.topic}}"}}},
              {"id":"report","type":"MODEL","name":"生成报告","config":{"promptTemplate":"整合","outputKind":"TEXT"}},
              {"id":"end","type":"END","name":"结束","config":{"resultFrom":"report","resultTitle":"报告"}}
            ],"edges":[
              {"id":"e1","source":"start","target":"retrieval"},
              {"id":"e2","source":"retrieval","target":"draft"},
              {"id":"e3","source":"draft","target":"approval"},
              {"id":"e4","source":"approval","target":"tool"},
              {"id":"e5","source":"tool","target":"report"},
              {"id":"e6","source":"report","target":"end"}
            ]}
            """;

    /** 合法条件图：检索命中数 GTE 1，TRUE/FALSE 分支各到模型与结束。 */
    private static final String CONDITION_GRAPH = """
            {"schemaVersion":1,"nodes":[
              {"id":"start","type":"START","name":"开始","config":{}},
              {"id":"retrieval","type":"RETRIEVAL","name":"检索","config":{"queryTemplate":"{{input.topic}}","topK":5}},
              {"id":"condition","type":"CONDITION","name":"判断","config":{"condition":{
                "left":{"source":"NODE_OUTPUT","nodeId":"retrieval","path":"hitCount","valueType":"NUMBER"},
                "operator":"GTE","right":{"valueType":"NUMBER","value":1}}}},
              {"id":"modelTrue","type":"MODEL","name":"命中","config":{"promptTemplate":"命中","outputKind":"TEXT"}},
              {"id":"modelFalse","type":"MODEL","name":"未命中","config":{"promptTemplate":"未命中","outputKind":"TEXT"}},
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
            """;

    /** 合法草稿保存与校验：问题列表一致、校验不改变版本且不新增保存审计。 */
    @Test
    @DisplayName("IT-WF-111 合法草稿保存/校验通过且校验不改版本")
    void validDraft_saveAndValidate() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "合法流程");
        UUID toolId = createTool(fixture.tenant(), "ACTIVE", true, true);

        JsonNode saved = saveOk(fixture.owner(), workflowId, 1, templateGraph(toolId));
        assertThat(saved.path("draftVersion").asInt()).isEqualTo(2);

        JsonNode validation = validateOk(fixture.owner(), workflowId);
        assertThat(validation.path("valid").asBoolean()).isTrue();
        assertThat(validation.path("issues")).isEmpty();
        assertThat(validation.propertyNames()).containsExactlyInAnyOrder("valid", "issues");

        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(2);
        assertThat(auditRows("workflow.draft.saved").stream()
                        .filter(row -> row.tenantId().equals(fixture.tenant()))
                        .count())
                .isEqualTo(1);
        assertThat(getOk(fixture.owner(), workflowId).path("draftVersion").asInt())
                .isEqualTo(2);
    }

    /** 非法图保存 400：逐项问题、版本不推进、无保存审计、草稿图不变。 */
    @Test
    @DisplayName("IT-WF-111 非法图保存 400 且不写库/不写审计")
    void invalidGraph_saveReturns400WithoutWrites() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "非法流程");
        UUID toolId = createTool(fixture.tenant(), "ACTIVE", true, true);
        String validWithTool = templateGraph(toolId);

        JsonNode error = save400(fixture.owner(), workflowId, 1, """
                {"schemaVersion":1,"nodes":[
                  {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[{"id":"e1","source":"model","target":"end"}]}
                """);
        assertThat(error.path("code").asText()).isEqualTo(WF_400);
        assertThat(error.path("detail").asText()).contains("MISSING_START");

        JsonNode cycle = save400(fixture.owner(), workflowId, 1, """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"a","type":"MODEL","name":"A","config":{"promptTemplate":"a","outputKind":"TEXT"}},
                  {"id":"b","type":"MODEL","name":"B","config":{"promptTemplate":"b","outputKind":"TEXT"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"a"},
                  {"id":"e2","source":"a","target":"b"},
                  {"id":"e3","source":"b","target":"a"}
                ]}
                """);
        assertThat(cycle.path("detail").asText()).contains("GRAPH_CYCLE");

        JsonNode badTargetKey = save400(
                fixture.owner(),
                workflowId,
                1,
                validWithTool.replace("\"targetNodeKey\":\"tool\"", "\"targetNodeKey\":\"bad key\""));
        assertThat(badTargetKey.path("detail").asText()).contains("APPROVAL_CONFIG");

        JsonNode badModel = save400(
                fixture.owner(),
                workflowId,
                1,
                validWithTool.replace(
                        "{\"promptTemplate\":\"整合\",\"outputKind\":\"TEXT\"}",
                        "{\"promptTemplate\":\"整合\",\"outputKind\":\"TEXT\",\"modelId\":\"" + UUID.randomUUID()
                                + "\"}"));
        assertThat(badModel.path("detail").asText()).contains("MODEL_UNAVAILABLE");

        JsonNode badTool = save400(
                fixture.owner(),
                workflowId,
                1,
                validWithTool.replace("\"toolId\":\"" + toolId + "\"", "\"toolId\":\"" + UUID.randomUUID() + "\""));
        assertThat(badTool.path("detail").asText()).contains("TOOL_NOT_AUTHORIZED");

        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.draft.saved").stream()
                        .filter(row -> row.tenantId().equals(fixture.tenant()))
                        .count())
                .isZero();
        assertThat(getOk(fixture.owner(), workflowId).path("draftGraph").path("nodes"))
                .isEmpty();
    }

    /** F-001：MCP_TOOL.toolId=null 必须按缺失必填阻断保存与 VALIDATE/PUBLISH 口径校验。 */
    @Test
    @DisplayName("IT-WF-111 F-001 toolId=null：保存 400、模板复制 VALIDATE 阻断")
    void nullToolId_isRequiredAndBlocksSaveValidate() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "缺工具流程");

        JsonNode error = save400(fixture.owner(), workflowId, 1, TEMPLATE_GRAPH);
        assertThat(error.path("detail").asText()).contains("REQUIRED_CONFIG", "node=tool", "field=config.toolId");
        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.draft.saved").stream()
                        .filter(row -> row.tenantId().equals(fixture.tenant()))
                        .count())
                .isZero();
        assertThat(getOk(fixture.owner(), workflowId).path("draftGraph").path("nodes"))
                .isEmpty();

        // 内置模板复制后保留 toolId=null：VALIDATE（发布口径）必须逐项阻断，保存也必须再次阻断。
        JsonNode copied = createDraftFromTemplate(fixture.owner(), TEMPLATE_ID);
        String copiedId = copied.path("id").asText();
        JsonNode validation = validateOk(fixture.owner(), copiedId);
        assertThat(validation.path("valid").asBoolean()).isFalse();
        assertThat(validation.path("issues")).anySatisfy(issue -> {
            assertThat(issue.path("code").asText()).isEqualTo("REQUIRED_CONFIG");
            assertThat(issue.path("nodeId").asText()).isEqualTo("tool");
            assertThat(issue.path("field").asText()).isEqualTo("config.toolId");
        });
        assertThat(save400(fixture.owner(), copiedId, 1, TEMPLATE_GRAPH)
                        .path("detail")
                        .asText())
                .contains("REQUIRED_CONFIG");
        assertThat(draftVersion(fixture.tenant(), copiedId)).isEqualTo(1);
    }

    /** 负向节点类型与执行配置全部被接口拒绝，不落库（AC-24、IT-WF-111）。 */
    @Test
    @DisplayName("IT-WF-111 负向节点类型：SCRIPT/CODE/HTTP/PARALLEL/LOOP/SUBFLOW/CRON")
    void negativeNodeTypes_areRejected() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "负向节点");

        for (String type : List.of("SCRIPT", "CODE", "HTTP", "PARALLEL", "LOOP", "SUBFLOW", "CRON", "EXEC")) {
            String graph = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"n\",\"type\":\"" + type
                    + "\",\"name\":\"x\",\"config\":{}}],\"edges\":[]}";
            JsonNode error = save400(fixture.owner(), workflowId, 1, graph);
            assertThat(error.path("code").asText()).as(type).isEqualTo(WF_400);
        }

        for (String field : List.of("script", "code", "http", "cron", "parallel", "loop", "subflow")) {
            String graph = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"n\",\"type\":\"START\",\"name\":\"x\","
                    + "\"config\":{\"" + field + "\":\"blocked\"}}],\"edges\":[]}";
            JsonNode error = save400(fixture.owner(), workflowId, 1, graph);
            assertThat(error.path("code").asText()).as(field).isEqualTo(WF_400);
        }

        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(1);
    }

    /** 校验端点权限：所有者 200；其他编辑者/跨租户/平台管理员 404；普通员工 403。 */
    @Test
    @DisplayName("IT-WF-111 校验端点权限与平台租户 404")
    void validateEndpoint_permissions() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "权限流程");

        JsonNode ownerValidation = validateOk(fixture.owner(), workflowId);
        assertThat(ownerValidation.path("valid").asBoolean()).isFalse();
        assertThat(ownerValidation.path("issues"))
                .anySatisfy(issue -> assertThat(issue.path("code").asText()).isEqualTo("MISSING_START"));
        assertThat(ownerValidation.path("issues"))
                .anySatisfy(issue -> assertThat(issue.path("code").asText()).isEqualTo("MISSING_END"));
        JsonNode firstIssue = ownerValidation.path("issues").get(0);
        assertThat(firstIssue.propertyNames()).contains("code", "message");
        assertThat(firstIssue.path("message").asText()).isNotBlank();
        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(1);

        assertThat(validate(fixture.otherEditor(), workflowId).status()).isEqualTo(404);
        assertThat(client.json(validate(fixture.otherEditor(), workflowId))
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-TENANT-404");
        assertThat(validate(fixture.member(), workflowId).status()).isEqualTo(403);
        assertThat(client.json(validate(fixture.member(), workflowId))
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-AUTH-403");

        String otherTenant = uniqueTenantCode("wfx");
        createTenant(loginPlatformAdmin(), otherTenant);
        LoginSession crossTenantEditor = seedAndLoginLocalUser(
                loginPlatformAdmin(),
                otherTenant,
                "cross" + shortId(),
                "cross-password-12",
                List.of("WORKFLOW_EDITOR"));
        ApiTestClient.ApiResponse crossTenant = validate(crossTenantEditor, workflowId);
        assertThat(crossTenant.status()).as(crossTenant.body()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse platform = validate(loginPlatformAdmin(), workflowId);
        assertThat(platform.status()).as(platform.body()).isEqualTo(404);
        assertThat(client.json(platform).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 审批人可用性只在发布口径阻断：保存允许，校验返回 NO_APPROVER；补齐非发布者后通过。 */
    @Test
    @DisplayName("IT-WF-111 审批 NO_APPROVER：保存允许、校验阻断、补齐后通过")
    void approvalNoApprover_isPublishLevel() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "审批可用性");
        String ownerOnly = approvalGraph(fixture.ownerId().toString());

        saveOk(fixture.owner(), workflowId, 1, ownerOnly);
        JsonNode noApprover = validateOk(fixture.owner(), workflowId);
        assertThat(noApprover.path("valid").asBoolean()).isFalse();
        assertThat(noApprover.path("issues"))
                .anySatisfy(issue -> assertThat(issue.path("code").asText()).isEqualTo("NO_APPROVER"));

        String withOther = approvalGraph(
                fixture.ownerId().toString(), fixture.otherEditorId().toString());
        saveOk(fixture.owner(), workflowId, 2, withOther);
        JsonNode passed = validateOk(fixture.owner(), workflowId);
        assertThat(passed.path("valid").asBoolean()).isTrue();
        assertThat(passed.path("issues")).isEmpty();
    }

    /** 工具授权/停用/审批前驱：未授权与停用阻断，要求审批的工具必须有绑定审批前驱。 */
    @Test
    @DisplayName("IT-WF-111 工具授权/停用/审批前驱")
    void toolAuthorizationAndApprovalPredecessor() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "工具校验");

        UUID unauthorized = createTool(fixture.tenant(), "ACTIVE", true, false);
        JsonNode unauthorizedError = save400(fixture.owner(), workflowId, 1, toolOnlyGraph(unauthorized));
        assertThat(unauthorizedError.path("detail").asText()).contains("TOOL_NOT_AUTHORIZED");

        UUID disabled = createTool(fixture.tenant(), "DISABLED", true, true);
        JsonNode disabledError = save400(fixture.owner(), workflowId, 1, toolGraph(disabled));
        assertThat(disabledError.path("detail").asText()).contains("TOOL_DISABLED");

        UUID active = createTool(fixture.tenant(), "ACTIVE", true, true);
        JsonNode missingApproval = save400(fixture.owner(), workflowId, 1, toolOnlyGraph(active));
        assertThat(missingApproval.path("detail").asText()).contains("APPROVAL_CONFIG");

        saveOk(fixture.owner(), workflowId, 1, toolGraph(active));
        assertThat(validateOk(fixture.owner(), workflowId).path("valid").asBoolean())
                .isTrue();
    }

    /** 模型目录可用性：ACTIVE 且在 REPORT 链通过；停用或不在链中拒绝。 */
    @Test
    @DisplayName("IT-WF-111 模型：REPORT 链 ACTIVE 通过，停用/不在链拒绝")
    void modelAvailability_realDirectory() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "模型校验");
        UUID modelId = createModel("ACTIVE", true);
        String graph = modelGraph(modelId);

        saveOk(fixture.owner(), workflowId, 1, graph);
        assertThat(validateOk(fixture.owner(), workflowId).path("valid").asBoolean())
                .isTrue();

        jdbcClient
                .sql("UPDATE model SET status = 'DISABLED' WHERE id = :id")
                .param("id", BinaryUuids.toBytes(modelId))
                .update();
        assertThat(save400(fixture.owner(), workflowId, 2, graph).path("detail").asText())
                .contains("MODEL_UNAVAILABLE");

        UUID notInChain = createModel("ACTIVE", false);
        assertThat(save400(fixture.owner(), workflowId, 2, modelGraph(notInChain))
                        .path("detail")
                        .asText())
                .contains("MODEL_UNAVAILABLE");
    }

    /** 条件与结果负向：算子/值类型非法与 resultFrom 非法均被拒绝；合法条件图通过。 */
    @Test
    @DisplayName("IT-WF-111 条件/结果负向与合法条件图")
    void conditionAndResultValidation() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "条件校验");

        String numericString = CONDITION_GRAPH
                .replace("\"operator\":\"GTE\"", "\"operator\":\"GT\"")
                .replace("\"valueType\":\"NUMBER\"", "\"valueType\":\"STRING\"")
                .replace("\"value\":1", "\"value\":\"x\"");
        assertThat(save400(fixture.owner(), workflowId, 1, numericString)
                        .path("detail")
                        .asText())
                .contains("CONDITION_BRANCH_INVALID");

        assertThat(save400(
                                fixture.owner(),
                                workflowId,
                                1,
                                CONDITION_GRAPH.replace("\"resultFrom\":\"modelTrue\"", "\"resultFrom\":\"missing\""))
                        .path("detail")
                        .asText())
                .contains("RESULT_INVALID");

        saveOk(fixture.owner(), workflowId, 1, CONDITION_GRAPH);
        assertThat(validateOk(fixture.owner(), workflowId).path("valid").asBoolean())
                .isTrue();
    }

    /** 节点上限在保存阶段拒绝。 */
    @Test
    @DisplayName("IT-WF-111 51 节点保存 400")
    void nodeLimit_isRejected() {
        Fixture fixture = fixture();
        String workflowId = createDraft(fixture.owner(), "节点上限");
        StringBuilder nodes = new StringBuilder();
        for (int index = 0; index < 51; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{\"id\":\"n").append(index).append("\",\"type\":\"START\",\"name\":\"n\",\"config\":{}}");
        }
        String graph = "{\"schemaVersion\":1,\"nodes\":[" + nodes + "],\"edges\":[]}";
        assertThat(save400(fixture.owner(), workflowId, 1, graph).path("code").asText())
                .isEqualTo(WF_400);
        assertThat(draftVersion(fixture.tenant(), workflowId)).isEqualTo(1);
    }

    /**
     * 构造审批图：开始→审批→模型→结束，审批人仅指定用户。
     *
     * @param assigneeUserIds 审批人 ID
     * @return JSON
     */
    private static String approvalGraph(String... assigneeUserIds) {
        String ids = java.util.Arrays.stream(assigneeUserIds)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"approval","type":"APPROVAL","name":"审批","config":{
                    "assigneeType":"USER","assigneeUserIds":[%s],"actionKind":"CONTINUE"}},
                  {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"approval"},
                  {"id":"e2","source":"approval","target":"model"},
                  {"id":"e3","source":"model","target":"end"}
                ]}
                """.formatted(ids);
    }

    /**
     * 构造带审批前驱的工具图。
     *
     * @param toolId 工具 ID
     * @return JSON
     */
    private static String toolGraph(UUID toolId) {
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"approval","type":"APPROVAL","name":"审批","config":{
                    "assigneeType":"ROLE","assigneeRole":"TENANT_ADMIN","actionKind":"MCP_TOOL","targetNodeKey":"tool"}},
                  {"id":"tool","type":"MCP_TOOL","name":"工具","config":{"toolId":"%s"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"tool"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"approval"},
                  {"id":"e2","source":"approval","target":"tool"},
                  {"id":"e3","source":"tool","target":"end"}
                ]}
                """.formatted(toolId);
    }

    /**
     * 构造工具节点图。
     *
     * @param toolId 工具 ID
     * @return JSON
     */
    private static String toolOnlyGraph(UUID toolId) {
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"tool","type":"MCP_TOOL","name":"工具","config":{"toolId":"%s"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"tool"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"tool"},
                  {"id":"e2","source":"tool","target":"end"}
                ]}
                """.formatted(toolId);
    }

    /**
     * 给模板等价图填入已授权工具 ID。
     *
     * @param toolId 工具 ID
     * @return JSON
     */
    private static String templateGraph(UUID toolId) {
        return TEMPLATE_GRAPH.replace("\"toolId\":null", "\"toolId\":\"" + toolId + "\"");
    }

    /**
     * 构造指定模型的图。
     *
     * @param modelId 模型 ID
     * @return JSON
     */
    private static String modelGraph(UUID modelId) {
        return """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"model","type":"MODEL","name":"模型","config":{
                    "promptTemplate":"x","outputKind":"TEXT","modelId":"%s"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"model"},
                  {"id":"e2","source":"model","target":"end"}
                ]}
                """.formatted(modelId);
    }

    /**
     * 创建测试模型目录行与可选 REPORT 链条目。
     *
     * @param status 模型状态
     * @param inReportChain 是否加入 REPORT 链
     * @return 模型 ID
     */
    private UUID createModel(String status, boolean inReportChain) {
        UUID providerId = UuidV7.generate();
        UUID modelId = UuidV7.generate();
        createdProviderIds.add(providerId);
        createdModelIds.add(modelId);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (
                            id, code, name, protocol, base_url, status, created_at, updated_at
                        ) VALUES (
                            :id, :code, 'Mock Provider', 'OPENAI_COMPATIBLE', 'https://model.invalid/v1',
                            'ACTIVE', NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", "wf-model-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            capabilities, status, created_at, updated_at
                        ) VALUES (
                            :id, :providerId, :modelKey, 'Mock Model', 8192, 1024, NULL, :status, NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", "model-" + suffix)
                .param("status", status)
                .update();
        if (inReportChain) {
            jdbcClient.sql("""
                            INSERT INTO model_routing_entry (purpose, position, model_id)
                            SELECT 'REPORT', COALESCE(MAX(position), 0) + 1, :modelId
                              FROM model_routing_entry
                             WHERE purpose = 'REPORT'
                            """).param("modelId", BinaryUuids.toBytes(modelId)).update();
        }
        return modelId;
    }

    /**
     * 创建测试 MCP server、工具与可选租户授权。
     *
     * @param tenant 租户
     * @param status 工具状态
     * @param requiresApproval 是否要求审批
     * @param granted 是否授权给租户
     * @return 工具 ID
     */
    private UUID createTool(String tenant, String status, boolean requiresApproval, boolean granted) {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, endpoint, status, created_at, updated_at)
                        VALUES (:id, :code, 'Mock MCP', 'HTTP', 'https://mcp.invalid/rpc', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(serverId))
                .param("code", "wf-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool (
                            id, server_id, name, description, risk_level, requires_approval, status,
                            created_at, updated_at
                        ) VALUES (
                            :id, :serverId, :name, 'mock read-only tool', 'READ', :requiresApproval, :status,
                            NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(toolId))
                .param("serverId", BinaryUuids.toBytes(serverId))
                .param("name", "search-" + suffix)
                .param("requiresApproval", requiresApproval)
                .param("status", status)
                .update();
        if (granted) {
            jdbcClient
                    .sql("""
                            INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled, granted_at, updated_at)
                            VALUES (:tenantId, :toolId, TRUE, NOW(6), NOW(6))
                            """)
                    .param("tenantId", tenant)
                    .param("toolId", BinaryUuids.toBytes(toolId))
                    .update();
        }
        return toolId;
    }

    /**
     * 创建空白草稿。
     *
     * @param session 会话
     * @param name 名称
     * @return 流程 ID
     */
    private String createDraft(LoginSession session, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows",
                client.jsonBody("name", name),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 从内置模板创建草稿并断言 201。
     *
     * @param session 会话
     * @param templateId 模板 ID
     * @return 草稿详情
     */
    private JsonNode createDraftFromTemplate(LoginSession session, UUID templateId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows",
                client.jsonBody("templateId", templateId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 保存草稿并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 版本
     * @param graph 图
     * @return 响应
     */
    private JsonNode saveOk(LoginSession session, String workflowId, int version, String graph) {
        ApiTestClient.ApiResponse response = save(session, workflowId, version, graph);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 保存草稿并断言 400 {@code RICCON-WF-400}。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 版本
     * @param graph 图
     * @return 错误响应
     */
    private JsonNode save400(LoginSession session, String workflowId, int version, String graph) {
        ApiTestClient.ApiResponse response = save(session, workflowId, version, graph);
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode body = client.json(response);
        assertThat(body.path("code").asText()).isEqualTo(WF_400);
        return body;
    }

    /**
     * 发起保存请求。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 版本
     * @param graph 图
     * @return 响应
     */
    private ApiTestClient.ApiResponse save(LoginSession session, String workflowId, int version, String graph) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("graph", JSON.readTree(graph));
        return client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                JSON.writeValueAsString(body),
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 调用校验端点并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @return 校验结果
     */
    private JsonNode validateOk(LoginSession session, String workflowId) {
        ApiTestClient.ApiResponse response = validate(session, workflowId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 调用校验端点。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse validate(LoginSession session, String workflowId) {
        return client.post(
                "/api/v1/workflows/" + workflowId + "/validate",
                null,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 读取草稿详情并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @return 响应
     */
    private JsonNode getOk(LoginSession session, String workflowId) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows/" + workflowId, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 直接读取草稿版本。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 草稿版本
     */
    private int draftVersion(String tenant, String workflowId) {
        return jdbcClient
                .sql("SELECT draft_version FROM workflow_definition WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query(Integer.class)
                .single();
    }

    /**
     * 创建本测试专用租户与账号。
     *
     * @return 测试夹具
     */
    private Fixture fixture() {
        LoginSession platformAdmin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("wfv");
        createTenant(platformAdmin, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenant);
        LoginSession owner = seedAndLoginLocalUser(
                platformAdmin, tenant, "owner" + shortId(), "owner-password-12", List.of("WORKFLOW_EDITOR"));
        LoginSession otherEditor = seedAndLoginLocalUser(
                platformAdmin, tenant, "editor" + shortId(), "editor-password-12", List.of("WORKFLOW_EDITOR"));
        LoginSession member = seedAndLoginLocalUser(
                platformAdmin, tenant, "member" + shortId(), "member-password-12", List.of("MEMBER"));
        return new Fixture(tenant, tenantAdmin, owner, otherEditor, member);
    }

    /**
     * 生成短随机后缀，避免账号重复。
     *
     * @return 8 位后缀
     */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 测试夹具。
     *
     * @param tenant 租户代码
     * @param tenantAdmin 租户管理员会话
     * @param owner 流程所有者（WORKFLOW_EDITOR）会话
     * @param otherEditor 同租户其他编辑者会话
     * @param member 普通员工会话
     */
    private record Fixture(
            String tenant,
            LoginSession tenantAdmin,
            LoginSession owner,
            LoginSession otherEditor,
            LoginSession member) {

        /**
         * 所有者账号 ID。
         *
         * @return 账号 ID
         */
        UUID ownerId() {
            return UUID.fromString(owner.body().path("user").path("id").asText());
        }

        /**
         * 其他编辑者账号 ID。
         *
         * @return 账号 ID
         */
        UUID otherEditorId() {
            return UUID.fromString(otherEditor.body().path("user").path("id").asText());
        }
    }
}
