package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 文档级 ACL：可见范围、创建人、403/404 矩阵、校验与审计。
 *
 * <p>显式开启 {@code riccon.kb.acl.enforcement-enabled=true}；列表、详情与 ACL 读取在同一租户内验证，
 * 跨租户/跨部门统一 404。每个用例使用唯一租户隔离数据。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeAclApiTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 创建默认 TENANT 文档并返回保留/解析元数据。 */
    @Test
    @DisplayName("创建默认 TENANT：字段、保留 180 天与新增审计")
    void createDocument_defaultsToTenantWithRetentionAndAudit() {
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "默认文档", null, null, null);
        String documentId = document.path("id").asText();
        assertThat(document.path("visibility").asText()).isEqualTo("TENANT");
        assertThat(document.path("departmentId").isNull()).isTrue();
        assertThat(document.path("aclUserCount").asLong()).isZero();
        assertThat(document.path("parseEngine").asText()).isEqualTo("NONE");
        assertThat(document.path("warnings").isArray()).isTrue();
        assertThat(document.path("parseSummary").isNull()).isTrue();
        assertThat(document.path("retentionDays").asInt()).isEqualTo(180);
        assertThat(document.path("retentionPolicy").asText()).isEqualTo("DEPLOYMENT_DEFAULT");
        assertThat(document.path("retentionExpiresAt").asText()).isNotBlank();
        assertThat(document.path("sourceFileAccessLimited").asBoolean()).isTrue();

        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("visibility")).isEqualTo("TENANT");
        assertThat(row.get("acl_version")).isEqualTo(1);
        assertThat(row.get("created_by")).isEqualTo(actorId(fixture.admin()));

        List<AuditRow> added = documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_ADDED, fixture.tenant(), documentId);
        assertThat(added).hasSize(1);
        JsonNode details = JSON.readTree(added.get(0).details());
        assertThat(details.path("sourceType").asText()).isEqualTo("UPLOAD");
        assertThat(details.path("visibility").asText()).isEqualTo("TENANT");
        assertThat(details.path("fileId").asText())
                .isEqualTo(document.path("fileId").asText());
    }

    /** USERS ACL 决定可见性，变更在下一请求生效并写审计/ACL_ONLY 事件。 */
    @Test
    @DisplayName("USERS ACL：授权、撤销、创建人始终可见与审计")
    void usersAcl_controlsVisibilityAndNextRequest() throws Exception {
        TenantFixture fixture = tenantFixture();
        LoginSession member2 = seedLogin(fixture.platform(), fixture.tenant(), "m2", List.of("MEMBER"));
        UUID memberId = actorId(fixture.member());
        JsonNode document = createDocument(fixture, "用户文档", "USERS", null, List.of(memberId));
        String documentId = document.path("id").asText();
        assertThat(document.path("aclUserCount").asLong()).isEqualTo(1);

        assertThat(documentIds(listDocuments(fixture.member()))).contains(documentId);
        assertThat(documentIds(listDocuments(member2))).doesNotContain(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(getDocument(member2, documentId).status()).isEqualTo(404);
        assertThat(getDocument(member2, documentId).body()).contains("RICCON-TENANT-404");
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(200);

        JsonNode acl = client.json(getAcl(fixture.admin(), documentId));
        assertThat(acl.path("visibility").asText()).isEqualTo("USERS");
        assertThat(acl.path("creatorUserId").asText())
                .isEqualTo(actorId(fixture.admin()).toString());
        assertThat(acl.path("users")).hasSize(2);
        assertThat(acl.path("users").toString())
                .contains(actorId(fixture.admin()).toString());
        assertThat(acl.path("users").toString()).contains(memberId.toString());

        // 可见但非创建人/非 ACL 管理员读取 ACL -> 403；不可见 -> 404。
        assertThat(getAcl(fixture.member(), documentId).status()).isEqualTo(403);
        assertThat(getAcl(member2, documentId).status()).isEqualTo(404);

        UUID member2Id = actorId(member2);
        ApiTestClient.ApiResponse replaced = putAcl(
                fixture.admin(), documentId, Map.of("visibility", "USERS", "userIds", List.of(member2Id.toString())));
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);

        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(404);
        assertThat(getDocument(member2, documentId).status()).isEqualTo(200);
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(200);
        assertThat(aclRowCount(fixture.tenant(), documentId)).isEqualTo(1);

        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("acl_version")).isEqualTo(2);
        List<AuditRow> changed = documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), documentId);
        assertThat(changed).hasSize(1);
        JsonNode details = JSON.readTree(changed.get(0).details());
        assertThat(details.path("previousVisibility").asText()).isEqualTo("USERS");
        assertThat(details.path("visibility").asText()).isEqualTo("USERS");
        assertThat(details.path("addedUserCount").asInt()).isEqualTo(1);
        assertThat(details.path("removedUserCount").asInt()).isEqualTo(1);
        assertThat(details.path("aclUserCount").asLong()).isEqualTo(1);

        assertThat(documentEvents(fixture.tenant(), documentId).stream()
                        .filter(event -> ("doc:" + documentId + ":acl:2").equals(event.idempotencyKey()))
                        .map(event -> JSON.readTree(event.payload()))
                        .anyMatch(payload ->
                                "ACL_ONLY".equals(payload.path("mode").asText())
                                        && payload.path("aclVersion").asInt() == 2))
                .isTrue();
    }

    /** DEPT ACL：本部门可见、他部门 404、部门停用后下一请求 404。 */
    @Test
    @DisplayName("DEPT ACL：部门边界与停用即时生效")
    void deptAcl_departmentBoundaryAndDisable() {
        TenantFixture fixture = tenantFixture();
        UUID deptA = createDepartment(fixture.platform(), fixture.tenant(), "DEPT-A");
        UUID deptB = createDepartment(fixture.platform(), fixture.tenant(), "DEPT-B");
        addDepartmentMember(fixture.platform(), fixture.tenant(), deptA, actorId(fixture.member()));
        LoginSession other = seedLogin(fixture.platform(), fixture.tenant(), "other", List.of("MEMBER"));
        addDepartmentMember(fixture.platform(), fixture.tenant(), deptB, actorId(other));

        JsonNode document = createDocument(fixture, "部门文档", "DEPT", deptA, null);
        String documentId = document.path("id").asText();
        assertThat(document.path("departmentId").asText()).isEqualTo(deptA.toString());

        assertThat(documentIds(listDocuments(fixture.member()))).contains(documentId);
        assertThat(documentIds(listDocuments(other))).doesNotContain(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(getDocument(other, documentId).status()).isEqualTo(404);

        disableDepartment(fixture.platform(), fixture.tenant(), deptA);
        assertThat(documentIds(listDocuments(fixture.member()))).doesNotContain(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(404);
        // 管理员仍可通过管理性读取看到部门停用的文档。
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(200);
    }

    /** 可见范围组合、上限与跨租户/跨部门 404/403 矩阵。 */
    @Test
    @DisplayName("ACL 校验矩阵与 403/404")
    void aclValidationAndMatrix() {
        TenantFixture fixture = tenantFixture();
        TenantFixture foreign = tenantFixture();
        UUID deptA = createDepartment(fixture.platform(), fixture.tenant(), "DEPT-A");
        UUID foreignDept = createDepartment(foreign.platform(), foreign.tenant(), "FOREIGN-DEPT");
        UUID memberId = actorId(fixture.member());
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "out", List.of("MEMBER"));

        JsonNode tenantDoc = createDocument(fixture, "租户文档", "TENANT", null, null);
        String tenantDocId = tenantDoc.path("id").asText();
        JsonNode usersDoc = createDocument(fixture, "用户文档", "USERS", null, List.of(memberId));
        String usersDocId = usersDoc.path("id").asText();

        // 可见但非创建人/非管理员 -> 403；不可见 -> 404；跨租户 -> 404。
        assertThat(getAcl(fixture.member(), tenantDocId).status()).isEqualTo(403);
        assertThat(getAcl(outsider, usersDocId).status()).isEqualTo(404);
        TenantFixture otherTenant = tenantFixture();
        assertThat(getDocument(otherTenant.member(), tenantDocId).status()).isEqualTo(404);
        assertThat(getAcl(otherTenant.member(), tenantDocId).status()).isEqualTo(404);
        // 平台管理员持有租户文档 ID：按跨租户统一 404，不返回 403 造成存在性探测。
        assertThat(getDocument(fixture.platform(), tenantDocId).status()).isEqualTo(404);
        assertThat(getAcl(fixture.platform(), tenantDocId).status()).isEqualTo(404);

        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        assertBadRequest(
                putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "TENANT", "departmentId", deptA.toString())));
        assertBadRequest(putAcl(fixture.admin(), tenantDocId, Map.of()));
        assertBadRequest(putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "DEPT")));
        assertBadRequest(putAcl(
                fixture.admin(), tenantDocId, Map.of("visibility", "DEPT", "userIds", List.of(memberId.toString()))));
        assertBadRequest(
                putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "USERS", "departmentId", deptA.toString())));
        assertBadRequest(putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "USERS", "userIds", tooMany)));
        assertBadRequest(putAcl(
                fixture.admin(),
                tenantDocId,
                Map.of("visibility", "USERS", "userIds", List.of(foreignDept.toString()))));
        assertBadRequest(putAcl(
                fixture.admin(),
                tenantDocId,
                Map.of("visibility", "USERS", "userIds", List.of(memberId.toString(), memberId.toString()))));
        assertBadRequest(putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "PUBLIC")));

        // 停用部门不能作为 DEPT 目标。
        disableDepartment(fixture.platform(), fixture.tenant(), deptA);
        assertBadRequest(
                putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "DEPT", "departmentId", deptA.toString())));

        // 跨租户文档在租户 A 的写/删/重索引路径统一 404。
        ApiTestClient.ApiResponse crossTenantPut = putAcl(fixture.admin(), tenantDocId, Map.of("visibility", "TENANT"));
        assertThat(crossTenantPut.status()).isEqualTo(200);
        assertThat(client.delete(
                                "/api/v1/knowledge/documents/" + tenantDocId,
                                otherTenant.member().sessionCookie(),
                                otherTenant.member().csrfToken())
                        .status())
                .isEqualTo(404);
        assertThat(client.post(
                                "/api/v1/knowledge/documents/" + tenantDocId + "/reindex",
                                client.jsonBody(),
                                otherTenant.admin().sessionCookie(),
                                otherTenant.admin().csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(404);
    }

    /** 创建人总是可见；创建人不出现在显式名单中；非创建人不能移除创建人。 */
    @Test
    @DisplayName("创建人隐式可见且不可移除")
    void creatorIsAlwaysVisibleAndNotRemovable() {
        TenantFixture fixture = tenantFixture();
        UUID adminId = actorId(fixture.admin());
        UUID memberId = actorId(fixture.member());
        JsonNode document = createDocument(fixture, "创建人文档", "USERS", null, List.of(adminId, memberId));
        String documentId = document.path("id").asText();
        assertThat(document.path("aclUserCount").asLong()).isEqualTo(1);

        JsonNode acl = client.json(getAcl(fixture.admin(), documentId));
        assertThat(acl.path("users")).hasSize(2);
        assertThat(acl.path("users").toString()).contains("\"creator\":true");

        ApiTestClient.ApiResponse replaced = putAcl(
                fixture.admin(), documentId, Map.of("visibility", "USERS", "userIds", List.of(memberId.toString())));
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(200);
        JsonNode after = client.json(getAcl(fixture.admin(), documentId));
        assertThat(after.path("users").toString()).contains(adminId.toString());
        assertThat(after.path("users").toString()).contains("\"creator\":true");
    }

    /**
     * 抽取列表中的文档 ID。
     *
     * @param response 列表响应
     * @return 文档 ID 列表
     */
    private List<String> documentIds(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode item : client.json(response).path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }

    /**
     * 断言 400 且错误码为 {@code RICCON-KB-400}。
     *
     * @param response 响应
     */
    private void assertBadRequest(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-KB-400");
    }
}
