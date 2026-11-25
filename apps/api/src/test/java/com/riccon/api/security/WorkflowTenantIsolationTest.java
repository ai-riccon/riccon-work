package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M06 流程/运行/审批跨租户与平台边界独立套件（TEN-M06-01、SEC-WF-002/003/006）。
 *
 * <p>从攻击者视角验证：租户 B 与平台管理员持有对象 ID 也不能读取/修改/发布/运行/审批/取消；
 * 私有草稿与他人运行统一 404 且列表不泄露名称或数量；同租户能力不足返回 403，不可见对象返回 404；
 * 匿名请求返回 401。所有用例使用真实 MySQL/Flyway V15、真实会话/CSRF/权限链与 HTTP，零 skip。</p>
 */
@Tag("security")
@Tag("tenant-isolation")
class WorkflowTenantIsolationTest extends WorkflowRunTestSupport {

    /** 生成本类唯一后缀；基类的 shortId 为私有实现。 */
    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 以会话身份 GET。 */
    private ApiTestClient.ApiResponse get(LoginSession session, String path) {
        return client.get(path, session.sessionCookie(), Map.of());
    }

    /** 创建租户 B 的租户管理员会话。 */
    private LoginSession crossTenantAdmin(LoginSession platform, String tenant) {
        return seedAndLoginLocalUser(
                platform, tenant, "xten" + suffix(), "xtenant-password-12", List.of("TENANT_ADMIN"));
    }

    /** 创建、保存并发布一个流程。 */
    private String publishWorkflow(Fixture fixture, String name, String runScope, UUID departmentId) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, runScope, departmentId, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    /** TEN-M06-01：跨租户流程/版本/运行/审批统一 404。 */
    @Test
    @DisplayName("TEN-M06-01 跨租户流程/运行/审批统一 404，平台管理员 404")
    void tenM0601_crossTenantAndPlatformAreInvisible() {
        Fixture fixture = fixture("tenm0601");
        String workflowId = publishWorkflow(fixture, "跨租户流程", "ONLY_SELF", null);
        JsonNode run = createRunOk(fixture.owner(), workflowId, null, "run-" + UUID.randomUUID());
        UUID runId = UUID.fromString(run.path("id").asText());
        UUID approvalId = insertPendingApproval(fixture.tenant(), runId, workflowId, "approval", fixture.ownerId());

        LoginSession platform = fixture.platform();
        String tenantB = uniqueTenantCode("xtm0601");
        createTenant(platform, tenantB);
        LoginSession betaAdmin = crossTenantAdmin(platform, tenantB);

        // 跨租户：持有流程/版本/运行/审批 ID 也不能读取或写入。
        assertProblem(get(betaAdmin, "/api/v1/workflows/" + workflowId), 404, TENANT_404);
        assertProblem(get(betaAdmin, "/api/v1/workflows/" + workflowId + "/versions"), 404, TENANT_404);
        assertProblem(
                client.put(
                        "/api/v1/workflows/" + workflowId + "/draft",
                        JSON.writeValueAsString(Map.of("version", 1, "graph", JSON.readTree(defaultGraph(fixture)))),
                        betaAdmin.sessionCookie(),
                        betaAdmin.csrfToken()),
                404,
                TENANT_404);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/publish",
                        client.jsonBody("runScope", "TENANT"),
                        betaAdmin.sessionCookie(),
                        betaAdmin.csrfToken(),
                        Map.of()),
                404,
                TENANT_404);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/runs",
                        JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "cross"))),
                        betaAdmin.sessionCookie(),
                        betaAdmin.csrfToken(),
                        Map.of("Idempotency-Key", "xten-run-" + UUID.randomUUID())),
                404,
                TENANT_404);
        assertProblem(get(betaAdmin, "/api/v1/workflow-runs/" + runId), 404, TENANT_404);
        assertProblem(get(betaAdmin, "/api/v1/workflow-approvals/" + approvalId), 404, TENANT_404);
        assertProblem(
                client.post(
                        "/api/v1/workflow-runs/" + runId + "/cancel",
                        "",
                        betaAdmin.sessionCookie(),
                        betaAdmin.csrfToken(),
                        Map.of()),
                404,
                TENANT_404);

        JsonNode betaRuns = client.json(get(betaAdmin, "/api/v1/workflow-runs?page=1&size=20"));
        assertThat(betaRuns.path("total").asLong()).isZero();
        assertThat(betaRuns.path("items").toString()).doesNotContain(workflowId);
        JsonNode betaApprovals = client.json(get(betaAdmin, "/api/v1/workflow-approvals?page=1&size=20"));
        assertThat(betaApprovals.path("total").asLong()).isZero();

        // 平台管理员：列表与对象统一 404，不泄露租户内容。
        assertProblem(get(platform, "/api/v1/workflows"), 404, TENANT_404);
        assertProblem(get(platform, "/api/v1/workflows/" + workflowId), 404, TENANT_404);
        assertProblem(get(platform, "/api/v1/workflow-runs/" + runId), 404, TENANT_404);
        assertProblem(get(platform, "/api/v1/workflow-approvals/" + approvalId), 404, TENANT_404);
    }

    /** SEC-WF-002：私有草稿对同租户其他编辑者/员工统一 404，列表不含名称。 */
    @Test
    @DisplayName("SEC-WF-002 私有草稿对他人 404，列表不泄露名称/数量")
    void secWf002_privateDraftInvisible() {
        Fixture fixture = fixture("secwf002");
        String name = "私有草稿-" + suffix();
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(get(fixture.otherEditor(), "/api/v1/workflows/" + workflowId), 404, TENANT_404);
        assertProblem(get(fixture.member(), "/api/v1/workflows/" + workflowId), 404, TENANT_404);

        JsonNode otherMine = client.json(get(fixture.otherEditor(), "/api/v1/workflows?view=MINE&page=1&size=20"));
        assertThat(otherMine.path("items").toString()).doesNotContain(workflowId);
        assertThat(otherMine.path("items").toString()).doesNotContain(name);
        JsonNode memberRunnable = client.json(get(fixture.member(), "/api/v1/workflows?view=RUNNABLE&page=1&size=20"));
        assertThat(memberRunnable.path("items").toString()).doesNotContain(workflowId);
        assertThat(memberRunnable.path("items").toString()).doesNotContain(name);

        // 所有者与租户管理员仍可见（管���员按 ALL 视图）。
        assertThat(getDetail(fixture.owner(), workflowId, 200).path("id").asText())
                .isEqualTo(workflowId);
        assertThat(client.json(get(fixture.tenantAdmin(), "/api/v1/workflows?view=ALL&page=1&size=20"))
                        .path("items")
                        .toString())
                .contains(workflowId);
    }

    /** SEC-WF-003：他人运行详情 404，历史列表不显示名称/数量；管理员可查并写管理性读取审计。 */
    @Test
    @DisplayName("SEC-WF-003 他人运行详情 404，历史列表不泄露，管理员可查")
    void secWf003_runVisibility() {
        Fixture fixture = fixture("secwf003");
        String name = "运行可见性-" + suffix();
        String workflowId = publishWorkflow(fixture, name, "ONLY_SELF", null);
        JsonNode run = createRunOk(fixture.owner(), workflowId, null, "run-" + UUID.randomUUID());
        String runId = run.path("id").asText();

        assertProblem(get(fixture.otherEditor(), "/api/v1/workflow-runs/" + runId), 404, TENANT_404);
        assertProblem(get(fixture.member(), "/api/v1/workflow-runs/" + runId), 404, TENANT_404);

        JsonNode otherRuns = client.json(get(fixture.otherEditor(), "/api/v1/workflow-runs?page=1&size=20"));
        assertThat(otherRuns.path("items").toString()).doesNotContain(workflowId);
        assertThat(otherRuns.path("items").toString()).doesNotContain(name);
        assertThat(otherRuns.path("total").asLong()).isZero();
        JsonNode memberRuns = client.json(get(fixture.member(), "/api/v1/workflow-runs?page=1&size=20"));
        assertThat(memberRuns.path("items").toString()).doesNotContain(workflowId);
        assertThat(memberRuns.path("total").asLong()).isZero();

        // 发起人 200；租户管理员 200 且写管理性读取审计。
        assertThat(get(fixture.owner(), "/api/v1/workflow-runs/" + runId).status())
                .isEqualTo(200);
        assertThat(get(fixture.tenantAdmin(), "/api/v1/workflow-runs/" + runId).status())
                .isEqualTo(200);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.admin_read"))
                .anySatisfy(row -> assertThat(row.details()).contains(runId));
    }

    /** SEC-WF-006：同租户能力不足 403；不可见对象 404；取消/停用/启用/移交矩阵。 */
    @Test
    @DisplayName("SEC-WF-006 能力不足 403、对象不可见 404，取消/停用/移交按发起人与角色判定")
    void secWf006_authorizationMatrix() {
        Fixture fixture = fixture("secwf006");
        String name = "权限矩阵-" + suffix();

        // 普通员工无编排能力：403。
        assertProblem(
                client.post(
                        "/api/v1/workflows",
                        client.jsonBody("name", "员工流程"),
                        fixture.member().sessionCookie(),
                        fixture.member().csrfToken(),
                        Map.of()),
                403,
                AUTH_403);

        // 使用 TENANT 范围流程：同租户员工/编辑者可查看，从而区分“可见但无能力 403”与“不可见 404”。
        String workflowId = publishWorkflow(fixture, name, "TENANT", null);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/publish",
                        client.jsonBody("runScope", "TENANT"),
                        fixture.member().sessionCookie(),
                        fixture.member().csrfToken(),
                        Map.of()),
                403,
                AUTH_403);
        assertProblem(disable(fixture.member(), workflowId), 403, AUTH_403);
        // 非所有者编辑者不能停用/启用他人流程。
        assertProblem(disable(fixture.otherEditor(), workflowId), 403, AUTH_403);
        assertProblem(transfer(fixture.otherEditor(), workflowId, fixture.memberId()), 403, AUTH_403);
        assertProblem(transfer(fixture.owner(), workflowId, fixture.memberId()), 403, AUTH_403);

        // 所有者可停用/启用；租户管理员也可。
        assertThat(disable(fixture.owner(), workflowId).status()).isEqualTo(200);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/runs",
                        JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "disabled"))),
                        fixture.owner().sessionCookie(),
                        fixture.owner().csrfToken(),
                        Map.of("Idempotency-Key", "disabled-" + UUID.randomUUID())),
                400,
                WF_400);
        assertThat(enable(fixture.owner(), workflowId).status()).isEqualTo(200);
        assertThat(disable(fixture.tenantAdmin(), workflowId).status()).isEqualTo(200);
        assertThat(enable(fixture.tenantAdmin(), workflowId).status()).isEqualTo(200);

        // 移交：租户管理员可移交；目标必须是可编辑成员。
        assertThat(transfer(fixture.tenantAdmin(), workflowId, fixture.otherEditorId())
                        .status())
                .isEqualTo(200);
        assertThat(ownerUserIdInDatabase(fixture.tenant(), workflowId)).isEqualTo(fixture.otherEditorId());

        // 取消矩阵：发起人与租户管理员可取消；其他人对不可见运行统一 404。
        JsonNode run = createRunOk(fixture.otherEditor(), workflowId, null, "run-" + UUID.randomUUID());
        String runId = run.path("id").asText();
        // TENANT 范围流程对同租户可见，但取消只允许发起人与租户管理员 → 403。
        assertProblem(
                client.post(
                        "/api/v1/workflow-runs/" + runId + "/cancel",
                        "",
                        fixture.owner().sessionCookie(),
                        fixture.owner().csrfToken(),
                        Map.of()),
                403,
                AUTH_403);
        assertProblem(
                client.post(
                        "/api/v1/workflow-runs/" + runId + "/cancel",
                        "",
                        fixture.member().sessionCookie(),
                        fixture.member().csrfToken(),
                        Map.of()),
                403,
                AUTH_403);
        assertThat(client.post(
                                "/api/v1/workflow-runs/" + runId + "/cancel",
                                "",
                                fixture.tenantAdmin().sessionCookie(),
                                fixture.tenantAdmin().csrfToken(),
                                Map.of())
                        .status())
                .isEqualTo(202);

        JsonNode second = createRunOk(fixture.otherEditor(), workflowId, null, "run-" + UUID.randomUUID());
        assertThat(cancelRun(fixture.otherEditor(), second.path("id").asText()).status())
                .isEqualTo(202);
    }

    /** 匿名访问必须 401，不能 404/403 泄露对象是否存在。 */
    @Test
    @DisplayName("SEC-WF-006 匿名工作流端点 401（GET；带合法 CSRF 的 POST）")
    void anonymousRequestsReturn401() {
        ApiTestClient.ApiResponse list = client.get("/api/v1/workflows");
        assertThat(list.status()).isEqualTo(401);
        assertThat(client.json(list).path("code").asText()).isEqualTo("RICCON-AUTH-401");

        // 匿名写请求先过 CSRF：无 CSRF 为 403（安全链既有顺序）；带合法匿名 CSRF 后由认证返回 401。
        ApiTestClient.ApiResponse noCsrf =
                client.post("/api/v1/workflows", client.jsonBody("name", "匿名流程"), null, null, Map.of());
        assertThat(noCsrf.status()).isEqualTo(403);
        CsrfSession csrf = csrfSession();
        ApiTestClient.ApiResponse create = client.post(
                "/api/v1/workflows", client.jsonBody("name", "匿名流程"), csrf.cookie(), csrf.token(), Map.of());
        assertThat(create.status()).isEqualTo(401);
        assertThat(client.json(create).path("code").asText()).isEqualTo("RICCON-AUTH-401");

        ApiTestClient.ApiResponse detail = client.get("/api/v1/workflow-runs/" + UUID.randomUUID());
        assertThat(detail.status()).isEqualTo(401);
    }
}
