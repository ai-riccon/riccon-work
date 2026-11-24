package com.riccon.api.workflow.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 版本/停用启用/移交 API 集成测试（{@code IT-WF-102}、{@code IT-WF-112}，AC-03/15/16）。
 *
 * <p>覆盖：版本列表倒序与分页、可见性 404、停用/启用与重复状态 409、审计前后状态、旧版本保留；停用
 * 403/404 矩阵；移交成功与 from/to 审计、原所有者失去编辑、目标状态/权限/不存在校验；平台租户 404。
 *
 * <p>真实 MySQL + Flyway V15 + 真实会话/CSRF/权限链；不 mock 持久化。
 */
class WorkflowVersionLifecycleApiTest extends WorkflowVersionTestSupport {

    /** 版本列表按版本号倒序、分页、可见性 404。 */
    @Test
    @DisplayName("IT-WF-102 版本列表倒序/分页与可见性")
    void listVersions_orderedPagedAndScoped() {
        Fixture fixture = fixture("wfv");
        String workflowId = createDraft(fixture.owner(), "版本列表").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-1001");

        JsonNode ownerPage = listVersions(fixture.owner(), workflowId, null);
        assertThat(ownerPage.path("total").asLong()).isEqualTo(1);
        assertThat(ownerPage.path("items")).hasSize(1);
        JsonNode first = ownerPage.path("items").get(0);
        assertThat(first.path("versionNo").asInt()).isEqualTo(1);
        assertThat(first.path("runScope").asText()).isEqualTo("ONLY_SELF");
        assertThat(first.path("publishedBy").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(first.has("graphJson")).isFalse();
        assertThat(ownerPage.toString()).doesNotContain("SECRET-PROMPT-MARKER");

        // ONLY_SELF 版本对普通员工不可见，统一 404。
        ApiTestClient.ApiResponse hiddenResponse = client.get(
                "/api/v1/workflows/" + workflowId + "/versions",
                fixture.member().sessionCookie(),
                Map.of());
        assertProblem(hiddenResponse, 404, TENANT_404);

        // 范围变更发布 v2=TENANT 后，员工可见全部版本元数据，按版本号倒序。
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-key-1002");
        JsonNode memberPage = listVersions(fixture.member(), workflowId, null);
        assertThat(memberPage.path("total").asLong()).isEqualTo(2);
        assertThat(memberPage.path("items").get(0).path("versionNo").asInt()).isEqualTo(2);
        assertThat(memberPage.path("items").get(1).path("versionNo").asInt()).isEqualTo(1);

        JsonNode secondPage = listVersions(fixture.member(), workflowId, "page=2&size=1");
        assertThat(secondPage.path("page").asInt()).isEqualTo(2);
        assertThat(secondPage.path("size").asInt()).isEqualTo(1);
        assertThat(secondPage.path("total").asLong()).isEqualTo(2);
        assertThat(secondPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(secondPage.path("items")).hasSize(1);
        assertThat(secondPage.path("items").get(0).path("versionNo").asInt()).isEqualTo(1);

        String otherTenant = uniqueTenantCode("wfx");
        createTenant(fixture.platform(), otherTenant);
        LoginSession crossTenantEditor = seedAndLoginLocalUser(
                fixture.platform(),
                otherTenant,
                "cross" + UUID.randomUUID(),
                "cross-password-12",
                List.of("WORKFLOW_EDITOR"));
        ApiTestClient.ApiResponse crossResponse = client.get(
                "/api/v1/workflows/" + workflowId + "/versions", crossTenantEditor.sessionCookie(), Map.of());
        assertProblem(crossResponse, 404, TENANT_404);

        LoginSession platformAdmin = loginPlatformAdmin();
        ApiTestClient.ApiResponse platformResponse =
                client.get("/api/v1/workflows/" + workflowId + "/versions", platformAdmin.sessionCookie(), Map.of());
        assertProblem(platformResponse, 404, TENANT_404);
    }

    /** 停用/启用：状态、canRun、审计前后状态、重复状态 409、版本仍可读。 */
    @Test
    @DisplayName("IT-WF-112 停用/启用写审计并可重复恢复")
    void disableEnable_writesAuditAndKeepsVersions() {
        Fixture fixture = fixture("wfv");
        String workflowId = createDraft(fixture.owner(), "停用流程").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-1101");

        ApiTestClient.ApiResponse disabled = disable(fixture.owner(), workflowId);
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        JsonNode disabledDetail = client.json(disabled);
        assertThat(disabledDetail.path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabledDetail.path("canRun").asBoolean()).isFalse();
        assertThat(disabledDetail.path("canPublish").asBoolean()).isTrue();
        assertThat(definitionStatus(fixture.tenant(), workflowId)).isEqualTo("DISABLED");
        assertThat(publishedVersionNo(fixture.tenant(), workflowId)).isEqualTo(1);

        List<AuditRow> disabledAudits = auditRows("workflow.disabled").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .toList();
        assertThat(disabledAudits).hasSize(1);
        JsonNode disabledDetails = JSON.readTree(disabledAudits.get(0).details());
        assertThat(disabledAudits.get(0).actorId()).isEqualTo(fixture.ownerId().toString());
        assertThat(disabledDetails.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(disabledDetails.path("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(disabledDetails.path("status").asText()).isEqualTo("DISABLED");

        JsonNode versionsWhileDisabled = listVersions(fixture.owner(), workflowId, null);
        assertThat(versionsWhileDisabled.path("total").asLong()).isEqualTo(1);
        assertThat(versionsWhileDisabled.path("items").get(0).path("versionNo").asInt())
                .isEqualTo(1);

        assertProblem(disable(fixture.owner(), workflowId), 409, WF_409);
        assertThat(auditRows("workflow.disabled").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);

        ApiTestClient.ApiResponse enabled = enable(fixture.owner(), workflowId);
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        JsonNode enabledDetail = client.json(enabled);
        assertThat(enabledDetail.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(enabledDetail.path("canRun").asBoolean()).isTrue();
        assertThat(definitionStatus(fixture.tenant(), workflowId)).isEqualTo("ACTIVE");

        List<AuditRow> enabledAudits = auditRows("workflow.enabled").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .toList();
        assertThat(enabledAudits).hasSize(1);
        JsonNode enabledDetails = JSON.readTree(enabledAudits.get(0).details());
        assertThat(enabledDetails.path("previousStatus").asText()).isEqualTo("DISABLED");
        assertThat(enabledDetails.path("status").asText()).isEqualTo("ACTIVE");

        assertProblem(enable(fixture.owner(), workflowId), 409, WF_409);
        assertThat(auditRows("workflow.enabled").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);

        // 租户管理员也可停用；所有者可再次启用，双方共用同一状态机与审计。
        ApiTestClient.ApiResponse adminDisabled = disable(fixture.tenantAdmin(), workflowId);
        assertThat(adminDisabled.status()).as(adminDisabled.body()).isEqualTo(200);
        assertThat(client.json(adminDisabled).path("status").asText()).isEqualTo("DISABLED");
        assertThat(auditRows("workflow.disabled").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(2);
        assertThat(enable(fixture.owner(), workflowId).status()).isEqualTo(200);
        assertThat(definitionStatus(fixture.tenant(), workflowId)).isEqualTo("ACTIVE");
        assertThat(auditRows("workflow.enabled").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(2);
    }

    /** 停用权限：私有草稿 404、同租户可见但非所有者 403、跨租户/平台管理员 404、普通员工无 URL 权限 403。 */
    @Test
    @DisplayName("IT-WF-112 停用权限 403/404 矩阵")
    void disable_permissionMatrix() {
        Fixture fixture = fixture("wfv");
        String privateFlow = createDraft(fixture.owner(), "私有停用").path("id").asText();
        saveDraft(fixture.owner(), privateFlow, 1, defaultGraph(fixture));
        assertProblem(disable(fixture.otherEditor(), privateFlow), 404, TENANT_404);

        String tenantFlow = createDraft(fixture.owner(), "租户停用").path("id").asText();
        saveDraft(fixture.owner(), tenantFlow, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), tenantFlow, "TENANT", null, "publish-key-1201");

        assertProblem(disable(fixture.otherEditor(), tenantFlow), 403, AUTH_403);
        assertProblem(disable(fixture.member(), tenantFlow), 403, AUTH_403);

        String otherTenant = uniqueTenantCode("wfx");
        createTenant(fixture.platform(), otherTenant);
        LoginSession crossTenantEditor = seedAndLoginLocalUser(
                fixture.platform(),
                otherTenant,
                "cross" + UUID.randomUUID(),
                "cross-password-12",
                List.of("WORKFLOW_EDITOR"));
        assertProblem(disable(crossTenantEditor, tenantFlow), 404, TENANT_404);

        LoginSession platformAdmin = loginPlatformAdmin();
        assertProblem(disable(platformAdmin, tenantFlow), 404, TENANT_404);

        assertThat(definitionStatus(fixture.tenant(), tenantFlow)).isEqualTo("ACTIVE");
        assertThat(auditRows("workflow.disabled").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant()))
                        .count())
                .isZero();
    }

    /** 移交成功：目标成为所有者、from/to 审计、原所有者失去编辑、新所有者可保存并发布。 */
    @Test
    @DisplayName("IT-WF-112 移交成功并写 from/to 审计")
    void transfer_success_updatesOwnerAndAudit() {
        Fixture fixture = fixture("wfv");
        String workflowId = createDraft(fixture.owner(), "移交流程").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(transfer(fixture.owner(), workflowId, fixture.otherEditorId()), 403, AUTH_403);
        assertProblem(transfer(fixture.otherEditor(), workflowId, fixture.otherEditorId()), 403, AUTH_403);

        ApiTestClient.ApiResponse transferred = transfer(fixture.tenantAdmin(), workflowId, fixture.otherEditorId());
        assertThat(transferred.status()).as(transferred.body()).isEqualTo(200);
        JsonNode transferredDetail = client.json(transferred);
        assertThat(transferredDetail.path("ownerUserId").asText())
                .isEqualTo(fixture.otherEditorId().toString());
        assertThat(ownerUserIdInDatabase(fixture.tenant(), workflowId)).isEqualTo(fixture.otherEditorId());

        List<AuditRow> audits = auditRows("workflow.owner.transferred").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .toList();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actorId()).isEqualTo(fixture.tenantAdminId().toString());
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(details.path("fromUserId").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(details.path("toUserId").asText())
                .isEqualTo(fixture.otherEditorId().toString());

        // 原所有者失去编辑权（私有草稿 404）；新所有者可继续保存与发布。
        assertThat(getDetail(fixture.owner(), workflowId, 404).path("code").asText())
                .isEqualTo(TENANT_404);
        JsonNode newOwnerDetail = getDetail(fixture.otherEditor(), workflowId, 200);
        assertThat(newOwnerDetail.path("canEdit").asBoolean()).isTrue();
        assertThat(newOwnerDetail.path("canPublish").asBoolean()).isTrue();
        JsonNode saved = saveDraft(fixture.otherEditor(), workflowId, 2, defaultGraph(fixture));
        assertThat(saved.path("draftVersion").asInt()).isEqualTo(3);
        JsonNode published = publishOk(fixture.otherEditor(), workflowId, "ONLY_SELF", null, "publish-key-1301");
        assertThat(published.path("publishedBy").asText())
                .isEqualTo(fixture.otherEditorId().toString());
        assertThat(getDetail(fixture.owner(), workflowId, 404).path("code").asText())
                .isEqualTo(TENANT_404);
    }

    /** 移交校验：目标不存在 404、目标停用/无编辑权 400、重复移交 409、请求形状 400、审计不产生。 */
    @Test
    @DisplayName("IT-WF-112 移交目标与请求校验")
    void transfer_validationAndConflicts() {
        Fixture fixture = fixture("wfv");
        String workflowId = createDraft(fixture.owner(), "移交校验").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(
                transfer(fixture.tenantAdmin(), UUID.randomUUID().toString(), fixture.otherEditorId()),
                404,
                TENANT_404);
        assertProblem(transfer(fixture.tenantAdmin(), workflowId, UUID.randomUUID()), 404, TENANT_404);
        assertProblem(transfer(fixture.tenantAdmin(), workflowId, fixture.memberId()), 400, WF_400);

        LoginSession disabledEditor = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "disabled" + UUID.randomUUID(),
                "disabled-password-12",
                List.of("WORKFLOW_EDITOR"));
        UUID disabledEditorId = actorId(disabledEditor);
        disableUser(fixture, disabledEditorId);
        assertProblem(transfer(fixture.tenantAdmin(), workflowId, disabledEditorId), 400, WF_400);

        assertProblem(transfer(fixture.tenantAdmin(), workflowId, fixture.ownerId()), 409, WF_409);

        ApiTestClient.ApiResponse missingBody = client.post(
                "/api/v1/workflows/" + workflowId + "/transfer",
                "{}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertProblem(missingBody, 400, WF_400);
        ApiTestClient.ApiResponse unknownField = client.post(
                "/api/v1/workflows/" + workflowId + "/transfer",
                "{\"ownerUserId\":\"" + fixture.otherEditorId() + "\",\"unknown\":true}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertProblem(unknownField, 400, WF_400);

        assertThat(ownerUserIdInDatabase(fixture.tenant(), workflowId)).isEqualTo(fixture.ownerId());
        assertThat(auditRows("workflow.owner.transferred").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant()))
                        .count())
                .isZero();
    }
}
