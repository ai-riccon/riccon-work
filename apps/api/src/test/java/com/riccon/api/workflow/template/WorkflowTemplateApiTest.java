package com.riccon.api.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 内置模板 API 集成测试（M06-AC-10）。
 *
 * <p>覆盖契约 {@code GET /api/v1/workflow-templates} 与 {@code POST /workflow-templates/{id}/copy}：
 * 列表只返回 {@code BUILT_IN} 模板并带分页/过滤；复制生成当前用户的个人草稿，来源模板版本写入草稿，
 * 源模板行不被修改；未知模板 404、无编辑权限 403、请求体/过滤长度非法 400；平台租户统一 404。
 *
 * <p>真实 MySQL + Flyway V15 种子 + 真实会话/CSRF/权限链；不直写草稿绕过 API。E2E-M06-07 由 T16a
 * 在浏览器覆盖，不在本测试内启动前端。
 */
class WorkflowTemplateApiTest extends ApiIntegrationTestSupport {

    /** 测试 JSON 编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** V15 种子的内置市场调研模板 ID。 */
    private static final UUID TEMPLATE_ID = UUID.fromString("01990000-0000-7000-8000-000000000601");

    /** 内置模板展示名，与 V15 种子一致。 */
    private static final String TEMPLATE_NAME = "市场调研报告";

    /** 列表返回内置模板契约字段且不泄露图/节点配置；员工、租户管理员都可读。 */
    @Test
    @DisplayName("IT-WF-101 模板列表返回 BUILT_IN 字段与分页")
    void list_returnsBuiltInTemplatesWithContractFields() {
        Fixture fixture = fixture();

        JsonNode page = list(fixture.member(), "");
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode template = itemById(page, TEMPLATE_ID.toString());
        assertThat(template).as("V15 内置模板必须出现在列表").isNotNull();
        assertThat(template.path("name").asText()).isEqualTo(TEMPLATE_NAME);
        assertThat(template.path("scenario").asText()).isEqualTo("市场调研");
        assertThat(template.path("description").asText()).isNotBlank();
        assertThat(template.path("source").asText()).isEqualTo("BUILT_IN");
        assertThat(template.path("visibility").asText()).isEqualTo("BUILT_IN");
        assertThat(template.path("builtInVersion").asInt()).isEqualTo(1);
        assertThat(template.path("upgradeAvailable").asBoolean()).isFalse();
        assertThat(template.path("updatedAt").asText()).endsWith("+08:00");
        // 内置模板无所有者、无升级检测；列表不返回图或节点配置，避免泄露模板内部实现。
        assertThat(template.has("ownerUserId")).isFalse();
        assertThat(template.has("latestBuiltInVersion")).isFalse();
        assertThat(template.has("graph")).isFalse();
        assertThat(template.has("nodeCount")).isFalse();

        // 租户管理员与普通员工看到同一内置目录（模板共享/私有归 M06b）。
        assertThat(list(fixture.tenantAdmin(), "").path("total").asLong())
                .isEqualTo(page.path("total").asLong());
    }

    /** 名称/场景包含过滤、通配符转义与强制分页；过滤参数超长 400。 */
    @Test
    @DisplayName("IT-WF-101 模板列表过滤、通配符转义与分页")
    void list_filtersAndPaginates() {
        Fixture fixture = fixture();

        assertThat(list(fixture.member(), "?scenario=" + encode("市场"))
                        .path("total")
                        .asLong())
                .isEqualTo(1);
        assertThat(list(fixture.member(), "?name=" + encode("调研")).path("total").asLong())
                .isEqualTo(1);
        assertThat(list(fixture.member(), "?name=" + encode("不存在的模板"))
                        .path("total")
                        .asLong())
                .isZero();
        // `%` 必须按字面量匹配：若未转义会变成“匹配全部”，暴露 LIKE 注入式的过滤绕过。
        assertThat(list(fixture.member(), "?scenario=%25").path("total").asLong())
                .isZero();
        // `_` 同理只匹配一个字面下划线，内置模板场景不含下划线。
        assertThat(list(fixture.member(), "?name=_").path("total").asLong()).isZero();

        JsonNode secondPage = list(fixture.member(), "?page=2&size=1");
        assertThat(secondPage.path("page").asInt()).isEqualTo(2);
        assertThat(secondPage.path("size").asInt()).isEqualTo(1);
        assertThat(secondPage.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(secondPage.path("items").size()).isZero();

        assertRequest400("/api/v1/workflow-templates?page=0", fixture.member());
        assertRequest400("/api/v1/workflow-templates?size=101", fixture.member());
        assertRequest400("/api/v1/workflow-templates?name=" + "a".repeat(201), fixture.member());
        assertRequest400("/api/v1/workflow-templates?scenario=" + "a".repeat(201), fixture.member());
    }

    /** 未认证 401、无 workflow.read 403、平台管理员 404。 */
    @Test
    @DisplayName("IT-WF-101 模板列表认证、权限与平台租户守卫")
    void list_enforcesAuthAndTenantGuards() {
        Fixture fixture = fixture();

        ApiTestClient.ApiResponse anonymous = client.get("/api/v1/workflow-templates");
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(401);

        ApiTestClient.ApiResponse forbidden =
                client.get("/api/v1/workflow-templates", fixture.noRole().sessionCookie(), Map.of());
        assertThat(forbidden.status()).as(forbidden.body()).isEqualTo(403);
        assertThat(client.json(forbidden).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        LoginSession platformAdmin = loginPlatformAdmin();
        ApiTestClient.ApiResponse platform =
                client.get("/api/v1/workflow-templates", platformAdmin.sessionCookie(), Map.of());
        assertThat(platform.status()).as(platform.body()).isEqualTo(404);
        assertThat(client.json(platform).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 复制生成当前用户的草稿，记录来源模板；源模板行、版本与图在复制前后完全一致。 */
    @Test
    @DisplayName("IT-WF-101 从模板复制为个人草稿且不修改源模板")
    void copy_createsPersonalDraftWithoutMutatingTemplate() {
        Fixture fixture = fixture();
        TemplateState before = templateState();

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                "{}",
                fixture.editor().sessionCookie(),
                fixture.editor().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode draft = client.json(response);
        String workflowId = draft.path("id").asText();

        assertThat(draft.path("name").asText()).isEqualTo(TEMPLATE_NAME);
        assertThat(draft.path("ownerUserId").asText())
                .isEqualTo(fixture.editorId().toString());
        assertThat(draft.path("sourceTemplateId").asText()).isEqualTo(TEMPLATE_ID.toString());
        assertThat(draft.path("sourceTemplateType").asText()).isEqualTo("BUILT_IN");
        assertThat(draft.path("sourceTemplateVersion").asInt()).isEqualTo(1);
        assertThat(draft.path("draftVersion").asInt()).isEqualTo(1);
        assertThat(draft.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(draft.path("canEdit").asBoolean()).isTrue();
        // 模板是 J2 等价链路：节点/连线完整复制；MCP 工具未选择，复制后由用户在授权范围内补选（AC-10）。
        assertThat(draft.path("draftGraph").path("nodes")).hasSize(7);
        assertThat(draft.path("draftGraph").path("edges")).hasSize(6);
        JsonNode toolNode = nodeById(draft.path("draftGraph"), "tool");
        assertThat(toolNode).as("模板必须包含工具节点").isNotNull();
        assertThat(toolNode.path("type").asText()).isEqualTo("MCP_TOOL");
        assertThat(toolNode.path("config").path("toolId").asText("")).isEmpty();

        // 源模板不被修改：名称、版本、图与更新时间全部保持。
        assertThat(templateState()).isEqualTo(before);

        DraftRow row = draftRow(fixture.tenant(), workflowId);
        assertThat(row.ownerUserId()).isEqualTo(fixture.editorId());
        assertThat(row.sourceTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(row.sourceTemplateType()).isEqualTo("BUILT_IN");
        assertThat(row.sourceTemplateVersion()).isEqualTo(1);
        assertThat(row.draftVersion()).isEqualTo(1);

        // 复制写 workflow.created 审计，来源模板进入审计白名单；不写图或提示词。
        List<AuditRow> createdRows = auditRows("workflow.created").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .toList();
        assertThat(createdRows).hasSize(1);
        JsonNode details = JSON.readTree(createdRows.get(0).details());
        assertThat(details.path("sourceTemplateId").asText()).isEqualTo(TEMPLATE_ID.toString());
        assertThat(details.path("sourceTemplateType").asText()).isEqualTo("BUILT_IN");
        assertThat(createdRows.get(0).details()).doesNotContain("argsTemplate");

        // 自定义名称只作用于草稿，不改模板；再次复制产生新的草稿 ID。
        ApiTestClient.ApiResponse named = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                client.jsonBody("name", " 我的市场调研 "),
                fixture.editor().sessionCookie(),
                fixture.editor().csrfToken(),
                Map.of());
        assertThat(named.status()).as(named.body()).isEqualTo(201);
        JsonNode namedDraft = client.json(named);
        assertThat(namedDraft.path("name").asText()).isEqualTo("我的市场调研");
        assertThat(namedDraft.path("id").asText()).isNotEqualTo(workflowId);
        assertThat(templateState()).isEqualTo(before);
    }

    /** 复制入口的权限、404、请求体与幂等键边界。 */
    @Test
    @DisplayName("IT-WF-101 模板复制权限与请求边界")
    void copy_enforcesGuards() {
        Fixture fixture = fixture();

        // 普通员工只有 read/run/approve，不能复制模板。
        ApiTestClient.ApiResponse member = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                "{}",
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(member.status()).as(member.body()).isEqualTo(403);
        assertThat(client.json(member).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        // 未知模板按契约 404（统一租户 404，不泄露存在性）。
        ApiTestClient.ApiResponse unknown = client.post(
                "/api/v1/workflow-templates/" + UUID.randomUUID() + "/copy",
                "{}",
                fixture.editor().sessionCookie(),
                fixture.editor().csrfToken(),
                Map.of());
        assertThat(unknown.status()).as(unknown.body()).isEqualTo(404);
        assertThat(client.json(unknown).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        // 请求体形状：未知字段、非字符串名称、超长名称统一 400 RICCON-WF-400。
        assertCopy400(fixture.editor(), client.jsonBody("unknown", true));
        assertCopy400(fixture.editor(), client.jsonBody("name", 42));
        assertCopy400(fixture.editor(), client.jsonBody("name", "a".repeat(201)));

        // Idempotency-Key 可选：缺失成功；提供但过短在进入服务前 400。
        ApiTestClient.ApiResponse shortKey = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                "{}",
                fixture.editor().sessionCookie(),
                fixture.editor().csrfToken(),
                Map.of("Idempotency-Key", "short"));
        assertThat(shortKey.status()).as(shortKey.body()).isEqualTo(400);

        // 平台管理员 404；无编辑权限且无读取权限的账号同样 403。
        LoginSession platformAdmin = loginPlatformAdmin();
        ApiTestClient.ApiResponse platform = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                "{}",
                platformAdmin.sessionCookie(),
                platformAdmin.csrfToken(),
                Map.of());
        assertThat(platform.status()).as(platform.body()).isEqualTo(404);
        assertThat(client.json(platform).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse noRole = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                "{}",
                fixture.noRole().sessionCookie(),
                fixture.noRole().csrfToken(),
                Map.of());
        assertThat(noRole.status()).as(noRole.body()).isEqualTo(403);
    }

    /**
     * 读取模板分页。
     *
     * @param session 会话
     * @param query 查询串；以 {@code ?} 开头
     * @return 分页响应
     */
    private JsonNode list(LoginSession session, String query) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflow-templates" + query, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 断言请求返回 400 {@code RICCON-REQUEST-400}。
     *
     * @param path 请求路径（含查询串）
     * @param session 会话
     */
    private void assertRequest400(String path, LoginSession session) {
        ApiTestClient.ApiResponse response = client.get(path, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-REQUEST-400");
    }

    /**
     * 断言复制请求体非法返回 400 {@code RICCON-WF-400}。
     *
     * @param session 会话
     * @param body 请求体
     */
    private void assertCopy400(LoginSession session, String body) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflow-templates/" + TEMPLATE_ID + "/copy",
                body,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-WF-400");
    }

    /**
     * 按 ID 查找列表项。
     *
     * @param page 分页响应
     * @param id 模板 ID
     * @return 模板项；未找到时返回 {@code null}
     */
    private static JsonNode itemById(JsonNode page, String id) {
        for (JsonNode item : page.path("items")) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 按节点 key 查找图节点。
     *
     * @param graph 图
     * @param nodeId 节点 key
     * @return 节点；未找到时返回 {@code null}
     */
    private static JsonNode nodeById(JsonNode graph, String nodeId) {
        for (JsonNode node : graph.path("nodes")) {
            if (nodeId.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 读取全局模板行的可比较状态。
     *
     * @return 名称、版本、图 JSON 与更新时间文本
     */
    private TemplateState templateState() {
        return jdbcClient
                .sql("""
                        SELECT name, version, graph_json, CAST(updated_at AS CHAR) AS updated_text
                          FROM workflow_template
                         WHERE id = :id
                        """)
                .param("id", BinaryUuids.toBytes(TEMPLATE_ID))
                .query((rs, rowNum) -> new TemplateState(
                        rs.getString("name"),
                        rs.getInt("version"),
                        rs.getString("graph_json"),
                        rs.getString("updated_text")))
                .single();
    }

    /**
     * 读取草稿行的来源模板与所有者。
     *
     * @param tenant 租户
     * @param workflowId 流程 ID
     * @return 草稿行
     */
    private DraftRow draftRow(String tenant, String workflowId) {
        return jdbcClient
                .sql("""
                        SELECT owner_user_id, source_template_id, source_template_type,
                               source_template_version, draft_version
                          FROM workflow_definition
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenant)
                .param("id", BinaryUuids.toBytes(UUID.fromString(workflowId)))
                .query((rs, rowNum) -> new DraftRow(
                        BinaryUuids.fromBytes(rs.getBytes("owner_user_id")),
                        BinaryUuids.fromBytes(rs.getBytes("source_template_id")),
                        rs.getString("source_template_type"),
                        rs.getObject("source_template_version", Integer.class),
                        rs.getInt("draft_version")))
                .single();
    }

    /**
     * 创建本测试专用租户与账号。
     *
     * @return 测试夹具
     */
    private Fixture fixture() {
        LoginSession platformAdmin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("wft");
        createTenant(platformAdmin, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platformAdmin, tenant);
        LoginSession editor = seedAndLoginLocalUser(
                platformAdmin, tenant, "editor" + shortId(), "editor-password-12", List.of("WORKFLOW_EDITOR"));
        LoginSession member = seedAndLoginLocalUser(
                platformAdmin, tenant, "member" + shortId(), "member-password-12", List.of("MEMBER"));
        LoginSession noRole =
                seedAndLoginLocalUser(platformAdmin, tenant, "guest" + shortId(), "guest-password-12", List.of());
        return new Fixture(tenant, tenantAdmin, editor, member, noRole);
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
     * URL 编码查询参数值。
     *
     * @param value 原始值
     * @return 编码后的值
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 租户与账号夹具。
     *
     * @param tenant 租户代码
     * @param tenantAdmin 租户管理员
     * @param editor 流程编辑者
     * @param member 普通员工
     * @param noRole 无角色账号
     */
    private record Fixture(
            String tenant, LoginSession tenantAdmin, LoginSession editor, LoginSession member, LoginSession noRole) {

        /**
         * 编辑者账号 ID。
         *
         * @return 账号 ID
         */
        UUID editorId() {
            return UUID.fromString(editor.body().path("user").path("id").asText());
        }
    }

    /**
     * 模板行可比较状态。
     *
     * @param name 名称
     * @param version 版本
     * @param graphJson 图 JSON
     * @param updatedText 更新时间文本
     */
    private record TemplateState(String name, int version, String graphJson, String updatedText) {}

    /**
     * 草稿来源模板行。
     *
     * @param ownerUserId 所有者
     * @param sourceTemplateId 来源模板 ID
     * @param sourceTemplateType 来源模板类型
     * @param sourceTemplateVersion 来源模板版本
     * @param draftVersion 草稿版本
     */
    private record DraftRow(
            UUID ownerUserId,
            UUID sourceTemplateId,
            String sourceTemplateType,
            Integer sourceTemplateVersion,
            int draftVersion) {}
}
