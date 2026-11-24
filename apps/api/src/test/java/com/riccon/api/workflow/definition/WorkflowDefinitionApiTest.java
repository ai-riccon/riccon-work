package com.riccon.api.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 流程草稿 API 集成测试（{@code IT-WF-101}，AC-01）。
 *
 * <p>覆盖：创建/重开/参数一致；未发布草稿不出现在可运行列表；草稿仅所有者与租户管理员可见；
 * 所有者与租户管理员并发保存时后保存者 409 且不静默覆盖；冲突后另存副本；模板复制生成个人草稿；
 * 保存请求的形状错误返回 400 {@code RICCON-WF-400}。
 *
 * <p>真实 MySQL + Flyway V15 + 真实会话/CSRF/权限链；不使用 repository 直写绕过 API。
 */
class WorkflowDefinitionApiTest extends ApiIntegrationTestSupport {

    /** 测试 JSON 编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** V15 种子的内置市场调研模板 ID。 */
    private static final UUID TEMPLATE_ID = UUID.fromString("01990000-0000-7000-8000-000000000601");

    /** 七节点图：开始→检索→草稿模型→审批→工具→报告模型→结束。 */
    private static final String GRAPH = """
            {
              "schemaVersion": 1,
              "nodes": [
                {"id":"start","type":"START","name":"开始","config":{},"position":{"x":10,"y":20}},
                {"id":"retrieval","type":"RETRIEVAL","name":"检索知识库","config":{"queryTemplate":"{{input.topic}}","topK":5}},
                {"id":"draft","type":"MODEL","name":"生成草稿","config":{"promptTemplate":"SECRET-PROMPT-MARKER","outputKind":"TEXT"}},
                {"id":"approval","type":"APPROVAL","name":"审批工具","config":{"assigneeType":"ROLE","assigneeRole":"TENANT_ADMIN","actionKind":"MCP_TOOL","targetNodeKey":"tool"}},
                {"id":"tool","type":"MCP_TOOL","name":"取数","config":{"toolId":null,"argsTemplate":{"query":"{{input.topic}}"}}},
                {"id":"report","type":"MODEL","name":"生成报告","config":{"promptTemplate":"整合材料","outputKind":"TEXT"}},
                {"id":"end","type":"END","name":"结束","config":{"resultFrom":"report","resultTitle":"市场调研报告"}}
              ],
              "edges": [
                {"id":"e1","source":"start","target":"retrieval"},
                {"id":"e2","source":"retrieval","target":"draft"},
                {"id":"e3","source":"draft","target":"approval"},
                {"id":"e4","source":"approval","target":"tool"},
                {"id":"e5","source":"tool","target":"report"},
                {"id":"e6","source":"report","target":"end"}
              ]
            }
            """;

    /** 创建、保存、重开与审计：节点/连线/参数与保存前一致，未发布不可运行。 */
    @Test
    @DisplayName("IT-WF-101 创建/保存/重开/未发布不可运行/审计")
    void createSaveReopen_andUnpublishedNotRunnable() {
        Fixture fixture = fixture();
        JsonNode created = createDraft(fixture.owner(), "销售调研", "销售部市场调研流程");
        String workflowId = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.path("draftVersion").asInt()).isEqualTo(1);
        assertThat(created.path("hasUnpublishedChanges").asBoolean()).isTrue();
        assertThat(created.path("canEdit").asBoolean()).isTrue();
        assertThat(created.path("canPublish").asBoolean()).isTrue();
        assertThat(created.path("canRun").asBoolean()).isFalse();
        assertThat(created.path("draftGraph").path("nodes")).isEmpty();
        assertThat(created.path("sourceTemplateId").isMissingNode()).isTrue();

        JsonNode saved = saveDraft(fixture.owner(), workflowId, 1, "销售调研 v2", GRAPH);
        assertThat(saved.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(saved.path("name").asText()).isEqualTo("销售调研 v2");
        assertThat(saved.path("draftGraph").path("nodes")).hasSize(7);
        assertThat(saved.path("draftGraph").path("edges")).hasSize(6);
        assertThat(saved.path("draftGraph")
                        .path("nodes")
                        .get(2)
                        .path("config")
                        .path("promptTemplate")
                        .asText())
                .isEqualTo("SECRET-PROMPT-MARKER");
        assertThat(saved.path("draftGraph")
                        .path("nodes")
                        .get(0)
                        .path("position")
                        .path("x")
                        .asDouble())
                .isEqualTo(10.0);

        JsonNode reopened = get(fixture.owner(), workflowId, 200);
        assertThat(reopened.path("draftVersion").asInt()).isEqualTo(2);
        assertThat(reopened.path("draftGraph")).isEqualTo(saved.path("draftGraph"));
        assertThat(reopened.path("hasUnpublishedChanges").asBoolean()).isTrue();

        JsonNode mine = list(fixture.owner(), "view=MINE&size=50");
        assertThat(ids(mine)).contains(workflowId);
        JsonNode runnable = list(fixture.owner(), "view=RUNNABLE&size=50");
        assertThat(ids(runnable)).doesNotContain(workflowId);
        assertThat(runnable.path("total").asLong()).isZero();

        assertThat(draftVersionInDatabase(fixture.tenant(), workflowId)).isEqualTo(2);

        List<AuditRow> createdRows = auditRows("workflow.created").stream()
                .filter(row -> row.tenantId().equals(fixture.tenant()))
                .toList();
        assertThat(createdRows).hasSize(1);
        assertThat(createdRows.get(0).targetId()).isEqualTo(workflowId);
        JsonNode createdDetails = JSON.readTree(createdRows.get(0).details());
        assertThat(createdDetails.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(createdRows.get(0).details()).doesNotContain("SECRET-PROMPT-MARKER");

        List<AuditRow> savedRows = auditRows("workflow.draft.saved").stream()
                .filter(row -> row.tenantId().equals(fixture.tenant()))
                .toList();
        assertThat(savedRows).hasSize(1);
        JsonNode savedDetails = JSON.readTree(savedRows.get(0).details());
        assertThat(savedDetails.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(savedDetails.path("version").asInt()).isEqualTo(2);
        assertThat(savedDetails.path("nodeCount").asInt()).isEqualTo(7);
        assertThat(savedRows.get(0).details()).doesNotContain("SECRET-PROMPT-MARKER");
    }

    /** 草稿仅所有者与租户管理员可见；其他编辑者/员工 404；ALL 仅管理员；平台管理员 404。 */
    @Test
    @DisplayName("IT-WF-101 草稿可见性：所有者/租户管理员，其他编辑者与员工 404")
    void draftVisibility_isOwnerAndTenantAdminOnly() {
        Fixture fixture = fixture();
        String workflowId =
                createDraft(fixture.owner(), "私有草稿", null).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, null, GRAPH);

        JsonNode notVisible = get(fixture.otherEditor(), workflowId, 404);
        assertThat(notVisible.path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(get(fixture.member(), workflowId, 404).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(ids(list(fixture.otherEditor(), "view=MINE&size=50"))).doesNotContain(workflowId);
        assertThat(ids(list(fixture.member(), "view=MINE&size=50"))).doesNotContain(workflowId);

        ApiTestClient.ApiResponse forbiddenResponse = client.get(
                "/api/v1/workflows?view=ALL&size=50", fixture.otherEditor().sessionCookie(), Map.of());
        assertThat(forbiddenResponse.status()).as(forbiddenResponse.body()).isEqualTo(403);
        assertThat(client.json(forbiddenResponse).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        JsonNode adminDetail = get(fixture.tenantAdmin(), workflowId, 200);
        assertThat(adminDetail.path("draftGraph").path("nodes")).hasSize(7);
        assertThat(adminDetail.path("canEdit").asBoolean()).isTrue();
        assertThat(ids(list(fixture.tenantAdmin(), "view=MINE&size=50"))).contains(workflowId);
        assertThat(ids(list(fixture.tenantAdmin(), "view=ALL&size=50"))).contains(workflowId);

        String otherTenant = uniqueTenantCode("wf2");
        createTenant(loginPlatformAdmin(), otherTenant);
        LoginSession otherTenantEditor = seedAndLoginLocalUser(
                loginPlatformAdmin(),
                otherTenant,
                "cross" + shortId(),
                "cross-tenant-password-12",
                List.of("WORKFLOW_EDITOR"));
        assertThat(get(otherTenantEditor, workflowId, 404).path("code").asText())
                .isEqualTo("RICCON-TENANT-404");
        assertThat(ids(list(otherTenantEditor, "view=MINE&size=50"))).doesNotContain(workflowId);

        LoginSession platformAdmin = loginPlatformAdmin();
        ApiTestClient.ApiResponse platformResponse =
                client.get("/api/v1/workflows", platformAdmin.sessionCookie(), Map.of());
        assertThat(platformResponse.status()).as(platformResponse.body()).isEqualTo(404);
        assertThat(client.json(platformResponse).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 所有者与租户管理员并发保存：后保存者 409，不静默覆盖；随后另存副本保留双方修改。 */
    @Test
    @DisplayName("IT-WF-101 并发保存 409 与另存副本")
    void concurrentSave_conflictThenDuplicate() {
        Fixture fixture = fixture();
        String workflowId =
                createDraft(fixture.owner(), "并发草稿", null).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, "所有者修改", GRAPH);

        // 租户管理员基于同一版本 2 保存，版本推进到 3。
        JsonNode adminSaved = saveDraft(fixture.tenantAdmin(), workflowId, 2, "管理员修改", GRAPH);
        assertThat(adminSaved.path("draftVersion").asInt()).isEqualTo(3);
        assertThat(adminSaved.path("name").asText()).isEqualTo("管理员修改");

        // 所有者仍持版本 2，保存必须 409 且不覆盖管理员修改。
        ApiTestClient.ApiResponse stale = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                draftBody(2, "过期修改", GRAPH),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken());
        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        assertThat(client.json(stale).path("code").asText()).isEqualTo("RICCON-WF-409");

        JsonNode after = get(fixture.owner(), workflowId, 200);
        assertThat(after.path("name").asText()).isEqualTo("管理员修改");
        assertThat(after.path("draftVersion").asInt()).isEqualTo(3);

        // 冲突后另存副本：新 ID、当前用户所有、版本 1、图与源一致；源流程不变。
        JsonNode copy = duplicate(fixture.owner(), workflowId, "所有者副本");
        String copyId = copy.path("id").asText();
        assertThat(copyId).isNotEqualTo(workflowId);
        assertThat(copy.path("ownerUserId").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(copy.path("draftVersion").asInt()).isEqualTo(1);
        assertThat(copy.path("name").asText()).isEqualTo("所有者副本");
        assertThat(copy.path("publishedVersionId").isMissingNode()).isTrue();
        assertThat(copy.path("draftGraph")).isEqualTo(after.path("draftGraph"));
        assertThat(ids(list(fixture.owner(), "view=MINE&size=50"))).contains(workflowId, copyId);

        JsonNode defaultNamed = duplicate(fixture.owner(), workflowId, null);
        assertThat(defaultNamed.path("name").asText()).isEqualTo("管理员修改 副本");
        assertThat(auditRows("workflow.created").stream()
                        .filter(row -> row.tenantId().equals(fixture.tenant())
                                && row.targetId().equals(copyId))
                        .count())
                .isEqualTo(1);
    }

    /** 从内置模板创建个人草稿；未知模板 400；未发布仍不可运行。 */
    @Test
    @DisplayName("IT-WF-101 模板复制为个人草稿")
    void createFromTemplate_copiesGraphAndSource() {
        Fixture fixture = fixture();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows",
                client.jsonBody("templateId", TEMPLATE_ID.toString()),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode body = client.json(response);
        assertThat(body.path("name").asText()).isEqualTo("市场调研报告");
        assertThat(body.path("sourceTemplateId").asText()).isEqualTo(TEMPLATE_ID.toString());
        assertThat(body.path("sourceTemplateType").asText()).isEqualTo("BUILT_IN");
        assertThat(body.path("sourceTemplateVersion").asInt()).isEqualTo(1);
        assertThat(body.path("draftGraph").path("nodes")).hasSize(7);
        assertThat(ids(list(fixture.owner(), "view=RUNNABLE&size=50")))
                .doesNotContain(body.path("id").asText());

        ApiTestClient.ApiResponse unknown = client.post(
                "/api/v1/workflows",
                client.jsonBody("templateId", UUID.randomUUID().toString()),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of());
        assertThat(unknown.status()).as(unknown.body()).isEqualTo(400);
        assertThat(client.json(unknown).path("code").asText()).isEqualTo("RICCON-WF-400");
    }

    /**
     * 已发布版本的范围可见性（T02 无发布写入路径，直接插入只读夹具）。
     *
     * <p>覆盖 T02 列表/详情读取逻辑：TENANT 范围对员工可运行且 MINE 可见；详情不返回草稿图；
     * 草稿哈希与版本一致时 {@code hasUnpublishedChanges=false}，草稿修改后为 true；ONLY_SELF 仅所有者
     * 可运行。发布写入本身由 T04 实现。
     */
    @Test
    @DisplayName("IT-WF-101 已发布版本的范围可见性（只读夹具）")
    void publishedVersion_scopeVisibility() {
        Fixture fixture = fixture();
        String tenantFlow =
                createDraft(fixture.owner(), "租户流程", null).path("id").asText();
        saveDraft(fixture.owner(), tenantFlow, 1, null, GRAPH);
        publishFixture(
                fixture, tenantFlow, "TENANT", UUID.fromString(fixture.ownerId().toString()));

        JsonNode memberDetail = get(fixture.member(), tenantFlow, 200);
        assertThat(memberDetail.path("draftGraph").isMissingNode()).isTrue();
        assertThat(memberDetail.path("latestVersion").path("versionNo").asInt()).isEqualTo(1);
        assertThat(memberDetail.path("latestVersion").path("runScope").asText()).isEqualTo("TENANT");
        assertThat(memberDetail.path("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(memberDetail.path("canRun").asBoolean()).isTrue();
        assertThat(memberDetail.path("canEdit").asBoolean()).isFalse();
        assertThat(ids(list(fixture.member(), "view=RUNNABLE&size=50"))).contains(tenantFlow);
        assertThat(ids(list(fixture.member(), "view=MINE&size=50"))).contains(tenantFlow);

        String selfFlow = createDraft(fixture.owner(), "仅自己流程", null).path("id").asText();
        saveDraft(fixture.owner(), selfFlow, 1, null, GRAPH);
        publishFixture(
                fixture,
                selfFlow,
                "ONLY_SELF",
                UUID.fromString(fixture.ownerId().toString()));
        assertThat(ids(list(fixture.member(), "view=RUNNABLE&size=50"))).doesNotContain(selfFlow);
        assertThat(ids(list(fixture.owner(), "view=RUNNABLE&size=50"))).contains(selfFlow);

        // 草稿改名后再保存同图，哈希不变；改图后 hasUnpublishedChanges=true。
        saveDraft(fixture.owner(), tenantFlow, 2, "租户流程改名", GRAPH);
        assertThat(get(fixture.owner(), tenantFlow, 200)
                        .path("hasUnpublishedChanges")
                        .asBoolean())
                .isFalse();
        saveDraft(fixture.owner(), tenantFlow, 3, null, GRAPH.replace("生成报告", "生成报告 v2"));
        assertThat(get(fixture.owner(), tenantFlow, 200)
                        .path("hasUnpublishedChanges")
                        .asBoolean())
                .isTrue();
    }

    /**
     * 直接插入发布版本与定义发布指针，作为 T02 只读可见性夹具。
     *
     * <p>不经过生产发布路径（T04）；版本行插入合法，更新/删除由 V15 触发器拒绝。
     *
     * @param fixture 测试夹具
     * @param workflowId 流程 ID
     * @param runScope 运行范围
     * @param publishedBy 发布人
     */
    private void publishFixture(Fixture fixture, String workflowId, String runScope, UUID publishedBy) {
        WorkflowGraph graph = WorkflowGraphJson.parse(GRAPH);
        UUID versionId = UUID.randomUUID();
        jdbcClient
                .sql("""
                        INSERT INTO workflow_version (
                            tenant_id, id, workflow_id, version_no, graph_json, graph_sha256, node_count,
                            run_scope, department_id, published_by, published_at, created_at
                        ) VALUES (
                            :tenant, :id, :workflowId, 1, :graphJson, :sha, 7,
                            :runScope, NULL, :publishedBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", fixture.tenant())
                .param("id", BinaryUuids.toBytes(versionId))
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .param("graphJson", WorkflowGraphJson.toJson(graph))
                .param("sha", WorkflowGraphJson.sha256(graph))
                .param("runScope", runScope)
                .param("publishedBy", BinaryUuids.toBytes(publishedBy))
                .update();
        jdbcClient
                .sql("""
                        UPDATE workflow_definition
                           SET published_version_id = :versionId, published_version_no = 1, updated_at = NOW(6)
                         WHERE tenant_id = :tenant AND id = :workflowId
                        """)
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("tenant", fixture.tenant())
                .param("workflowId", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .update();
    }

    /** 请求形状错误返回 400 RICCON-WF-400。 */
    @Test
    @DisplayName("IT-WF-101 保存请求形状错误 400")
    void invalidRequests_returnWorkflow400() {
        Fixture fixture = fixture();
        String workflowId =
                createDraft(fixture.owner(), "校验草稿", null).path("id").asText();

        assertThat(client.post(
                                "/api/v1/workflows",
                                client.jsonBody("name", "x", "unknown", 1),
                                fixture.owner().sessionCookie(),
                                fixture.owner().csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(400);
        assertThat(client.json(client.post(
                                "/api/v1/workflows",
                                client.jsonBody("name", ""),
                                fixture.owner().sessionCookie(),
                                fixture.owner().csrfToken(),
                                Map.of()))
                        .path("code")
                        .asText())
                .isEqualTo("RICCON-WF-400");

        assertWorkflow400(fixture.owner(), workflowId, 0, GRAPH);
        assertWorkflow400(fixture.owner(), workflowId, 1, GRAPH.replace("START", "SCRIPT"));
        assertWorkflow400(
                fixture.owner(),
                workflowId,
                1,
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\","
                        + "\"config\":{}}],\"edges\":[{\"id\":\"e1\",\"source\":\"s\",\"target\":\"s\","
                        + "\"branch\":true}]}");
        assertWorkflow400(
                fixture.owner(),
                workflowId,
                1,
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"s\",\"type\":\"START\",\"name\":\"x\","
                        + "\"config\":{\"script\":\"rm -rf /\"}}],\"edges\":[]}");

        // 失败请求不得推进版本或写入保存审计。
        assertThat(draftVersionInDatabase(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.draft.saved").stream()
                        .filter(row -> row.tenantId().equals(fixture.tenant()))
                        .count())
                .isZero();
    }

    /** 请求解析与控制器分支：view/幂等键/未知字段/缺图/说明清空。 */
    @Test
    @DisplayName("IT-WF-101 请求分支：view/幂等键/未知字段/缺图/说明清空")
    void requestBranches_areValidated() {
        Fixture fixture = fixture();
        JsonNode created = createDraft(fixture.owner(), "分支草稿", "初始说明");
        String workflowId = created.path("id").asText();
        assertThat(created.path("description").asText()).isEqualTo("初始说明");

        ApiTestClient.ApiResponse badView =
                client.get("/api/v1/workflows?view=UNKNOWN", fixture.owner().sessionCookie(), Map.of());
        assertThat(badView.status()).as(badView.body()).isEqualTo(400);
        assertThat(client.json(badView).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse shortKey = client.post(
                "/api/v1/workflows",
                client.jsonBody("name", "幂等"),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of("Idempotency-Key", "short"));
        assertThat(shortKey.status()).as(shortKey.body()).isEqualTo(400);

        ApiTestClient.ApiResponse longName = client.post(
                "/api/v1/workflows",
                client.jsonBody("name", "a".repeat(201)),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of());
        assertThat(longName.status()).as(longName.body()).isEqualTo(400);
        assertThat(client.json(longName).path("code").asText()).isEqualTo("RICCON-WF-400");

        ApiTestClient.ApiResponse badTemplate = client.post(
                "/api/v1/workflows",
                client.jsonBody("templateId", "not-a-uuid"),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of());
        assertThat(badTemplate.status()).as(badTemplate.body()).isEqualTo(400);
        assertThat(client.json(badTemplate).path("code").asText()).isEqualTo("RICCON-WF-400");

        ApiTestClient.ApiResponse missingGraph = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                "{\"version\":1}",
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken());
        assertThat(missingGraph.status()).as(missingGraph.body()).isEqualTo(400);
        assertThat(client.json(missingGraph).path("code").asText()).isEqualTo("RICCON-WF-400");

        ApiTestClient.ApiResponse stringVersion = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                "{\"version\":\"1\",\"graph\":{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}}",
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken());
        assertThat(stringVersion.status()).as(stringVersion.body()).isEqualTo(400);
        assertThat(client.json(stringVersion).path("code").asText()).isEqualTo("RICCON-WF-400");

        ApiTestClient.ApiResponse duplicateUnknown = client.post(
                "/api/v1/workflows/" + workflowId + "/duplicate",
                client.jsonBody("name", "x", "unknown", true),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of());
        assertThat(duplicateUnknown.status()).as(duplicateUnknown.body()).isEqualTo(400);
        assertThat(client.json(duplicateUnknown).path("code").asText()).isEqualTo("RICCON-WF-400");

        // 显式 description=null 清空说明；name 省略保留原名。
        JsonNode cleared = saveDraftWithBody(
                fixture.owner(),
                workflowId,
                "{\"version\":1,\"description\":null,\"graph\":" + JSON.writeValueAsString(JSON.readTree(GRAPH)) + "}");
        assertThat(cleared.path("description").isMissingNode()).isTrue();
        assertThat(cleared.path("name").asText()).isEqualTo("分支草稿");
        JsonNode described = saveDraftWithBody(
                fixture.owner(),
                workflowId,
                "{\"version\":2,\"description\":\"新说明\",\"graph\":" + JSON.writeValueAsString(JSON.readTree(GRAPH))
                        + "}");
        assertThat(described.path("description").asText()).isEqualTo("新说明");
    }

    /**
     * 使用原始请求体保存草稿并断言 200。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param body 请求体
     * @return 响应
     */
    private JsonNode saveDraftWithBody(LoginSession session, String workflowId, String body) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/workflows/" + workflowId + "/draft", body, session.sessionCookie(), session.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 断言保存草稿返回 400 {@code RICCON-WF-400}。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 版本
     * @param graph 图
     */
    private void assertWorkflow400(LoginSession session, String workflowId, int version, String graph) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                draftBody(version, null, graph),
                session.sessionCookie(),
                session.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-WF-400");
    }

    /**
     * 创建空白草稿。
     *
     * @param session 会话
     * @param name 名称
     * @param description 说明
     * @return 响应
     */
    private JsonNode createDraft(LoginSession session, String name, String description) {
        String body = description == null
                ? client.jsonBody("name", name)
                : client.jsonBody("name", name, "description", description);
        ApiTestClient.ApiResponse response =
                client.post("/api/v1/workflows", body, session.sessionCookie(), session.csrfToken(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 保存草稿并断言成功。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param version 版本
     * @param name 名称；可空表示不修改
     * @param graph 图
     * @return 响应
     */
    private JsonNode saveDraft(LoginSession session, String workflowId, int version, String name, String graph) {
        ApiTestClient.ApiResponse response = client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                draftBody(version, name, graph),
                session.sessionCookie(),
                session.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 构造保存草稿请求体。
     *
     * @param version 版本
     * @param name 名称；为空时不提交
     * @param graph 图
     * @return JSON 请求体
     */
    private static String draftBody(int version, String name, String graph) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        if (name != null) {
            body.put("name", name);
        }
        body.put("graph", JSON.readTree(graph));
        return JSON.writeValueAsString(body);
    }

    /**
     * 读取流程详情并断言状态。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param status 期望状态
     * @return 响应
     */
    private JsonNode get(LoginSession session, String workflowId, int status) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows/" + workflowId, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        return client.json(response);
    }

    /**
     * 列出流程。
     *
     * @param session 会话
     * @param query 查询串
     * @return 响应
     */
    private JsonNode list(LoginSession session, String query) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows?" + query, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 另存副本。
     *
     * @param session 会话
     * @param workflowId 源流程 ID
     * @param name 副本名称；为空时省略
     * @return 响应
     */
    private JsonNode duplicate(LoginSession session, String workflowId, String name) {
        String body = name == null ? "{}" : client.jsonBody("name", name);
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows/" + workflowId + "/duplicate",
                body,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 提取列表 ID。
     *
     * @param page 列表响应
     * @return ID 列表
     */
    private static List<String> ids(JsonNode page) {
        assertThat(page.path("items").isArray()).isTrue();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }

    /**
     * 直接读取草稿版本，验证失败请求未推进版本。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 草稿版本
     */
    private int draftVersionInDatabase(String tenant, String workflowId) {
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
        String tenant = uniqueTenantCode("wfd");
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
    }
}
