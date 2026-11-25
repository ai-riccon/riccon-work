package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M06 部门维度越权独立套件（DEPT-M06-01）。
 *
 * <p>覆盖 DEPARTMENT 发布范围下的跨部门运行、移出部门、部门停用与账号停用：下一次请求按新范围判定，
 * 不可见时统一 404；已发起运行保留且租户管理员仍可查；部门负责人/租户管理员的发布范围边界由服务端
 * 拒绝。真实 MySQL/Flyway V15、真实会话/CSRF/权限链与 HTTP，零 skip。</p>
 */
@Tag("security")
@Tag("dept-isolation")
class WorkflowDepartmentIsolationTest extends WorkflowRunTestSupport {

    /** 唯一后缀。 */
    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 以会话身份 GET。 */
    private ApiTestClient.ApiResponse get(LoginSession session, String path) {
        return client.get(path, session.sessionCookie(), Map.of());
    }

    /** 平台管理员把账号移出部门。 */
    private void removeDepartmentMember(LoginSession platform, String tenant, UUID departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.delete(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members/" + userId,
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(204);
    }

    /** 创建并发布 DEPARTMENT 范围流程。 */
    private String publishDepartmentWorkflow(Fixture fixture, String name, UUID departmentId) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "DEPARTMENT", departmentId, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    /** 发起人发起一次 DEPARTMENT 运行。 */
    private QueryRun runAs(LoginSession session, String workflowId) {
        JsonNode run = createRunOk(session, workflowId, null, "run-" + UUID.randomUUID());
        String runId = run.path("id").asText();
        return new QueryRun(runId, run.path("status").asText());
    }

    /** DEPT-M06-01：跨部门 404、移出部门/部门停用/账号停用后 404，管理员与发起人可见。 */
    @Test
    @DisplayName("DEPT-M06-01 跨部门与移出/停用后运行不可见，管理员仍可查")
    void deptM0601_departmentScopeChanges() {
        Fixture fixture = fixture("deptm0601");
        UUID departmentA = createManagedDepartment(fixture, "编排一部-" + suffix(), fixture.ownerId());
        UUID departmentB = createManagedDepartment(fixture, "编排二部-" + suffix(), fixture.otherEditorId());
        addDepartmentMember(fixture.platform(), fixture.tenant(), departmentA, fixture.memberId());
        String workflowId = publishDepartmentWorkflow(fixture, "部门运行-" + suffix(), departmentA);

        // 部门成员可运行；他部门编辑者不可运行、不可查看运行详情。
        QueryRun memberRun = runAs(fixture.member(), workflowId);
        assertThat(get(fixture.member(), "/api/v1/workflow-runs/" + memberRun.runId())
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.owner(), "/api/v1/workflow-runs/" + memberRun.runId())
                        .status())
                .isEqualTo(404);
        assertThat(get(fixture.otherEditor(), "/api/v1/workflow-runs/" + memberRun.runId())
                        .status())
                .isEqualTo(404);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/runs",
                        JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "跨部门"))),
                        fixture.otherEditor().sessionCookie(),
                        fixture.otherEditor().csrfToken(),
                        Map.of("Idempotency-Key", "cross-dept-" + UUID.randomUUID())),
                404,
                TENANT_404);

        // 移出部门：已发起运行保留且按原可见规则可查（发起人 200、管理员 200），
        // 但下一次运行准入按新范围判定，DEPARTMENT 流程不再可见（404）。
        removeDepartmentMember(fixture.platform(), fixture.tenant(), departmentA, fixture.memberId());
        assertThat(get(fixture.member(), "/api/v1/workflow-runs/" + memberRun.runId())
                        .status())
                .isEqualTo(200);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/runs",
                        JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "移出部门"))),
                        fixture.member().sessionCookie(),
                        fixture.member().csrfToken(),
                        Map.of("Idempotency-Key", "removed-dept-" + UUID.randomUUID())),
                404,
                TENANT_404);
        assertThat(get(fixture.tenantAdmin(), "/api/v1/workflow-runs/" + memberRun.runId())
                        .status())
                .isEqualTo(200);

        // 部门停用：重新加入后发起的新运行保留可查；新运行被拒绝。
        addDepartmentMember(fixture.platform(), fixture.tenant(), departmentA, fixture.memberId());
        QueryRun secondRun = runAs(fixture.member(), workflowId);
        disableDepartment(fixture.tenant(), departmentA);
        assertThat(get(fixture.member(), "/api/v1/workflow-runs/" + secondRun.runId())
                        .status())
                .isEqualTo(200);
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + workflowId + "/runs",
                        JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "部门停用"))),
                        fixture.member().sessionCookie(),
                        fixture.member().csrfToken(),
                        Map.of("Idempotency-Key", "disabled-dept-" + UUID.randomUUID())),
                404,
                TENANT_404);
        assertThat(get(fixture.tenantAdmin(), "/api/v1/workflow-runs/" + secondRun.runId())
                        .status())
                .isEqualTo(200);

        // 账号停用：不能发起新运行；已发起运行仅租户管理员可查。
        disableUser(fixture, fixture.memberId());
        int disabledStatus = get(fixture.member(), "/api/v1/workflow-runs/" + secondRun.runId())
                .status();
        assertThat(disabledStatus).isIn(401, 403);
        ApiTestClient.ApiResponse disabledCreate = client.post(
                "/api/v1/workflows/" + workflowId + "/runs",
                JSON.writeValueAsString(Map.of("inputs", Map.of("topic", "账号停用"))),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Idempotency-Key", "disabled-user-" + UUID.randomUUID()));
        assertThat(disabledCreate.status()).isIn(401, 403);
        assertThat(get(fixture.tenantAdmin(), "/api/v1/workflow-runs/" + secondRun.runId())
                        .status())
                .isEqualTo(200);
    }

    /** 发布范围边界：他部门编辑者与租户管理员不能发布 DEPARTMENT。 */
    @Test
    @DisplayName("DEPT-M06-01 非部门负责人与租户管理员不能发布 DEPARTMENT 范围")
    void deptM0601_publishScopeBoundary() {
        Fixture fixture = fixture("deptscope");
        UUID departmentA = createManagedDepartment(fixture, "发布部-" + suffix(), fixture.ownerId());
        String otherWorkflow = createDraft(fixture.otherEditor(), "他部门发布-" + suffix())
                .path("id")
                .asText();
        saveDraft(fixture.otherEditor(), otherWorkflow, 1, defaultGraph(fixture));
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + otherWorkflow + "/publish",
                        JSON.writeValueAsString(
                                Map.of("runScope", "DEPARTMENT", "departmentId", departmentA.toString())),
                        fixture.otherEditor().sessionCookie(),
                        fixture.otherEditor().csrfToken(),
                        Map.of("Idempotency-Key", "scope-" + UUID.randomUUID())),
                400,
                WF_400);

        String adminWorkflow = createDraft(fixture.tenantAdmin(), "管理员发布-" + suffix())
                .path("id")
                .asText();
        saveDraft(fixture.tenantAdmin(), adminWorkflow, 1, defaultGraph(fixture));
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + adminWorkflow + "/publish",
                        JSON.writeValueAsString(
                                Map.of("runScope", "DEPARTMENT", "departmentId", departmentA.toString())),
                        fixture.tenantAdmin().sessionCookie(),
                        fixture.tenantAdmin().csrfToken(),
                        Map.of("Idempotency-Key", "scope-admin-" + UUID.randomUUID())),
                400,
                WF_400);

        // 仅自己/本租户的角色边界：编辑者不能发布 TENANT，租户管理员可以。
        String ownerWorkflow =
                createDraft(fixture.owner(), "编辑者范围-" + suffix()).path("id").asText();
        saveDraft(fixture.owner(), ownerWorkflow, 1, defaultGraph(fixture));
        assertProblem(
                client.post(
                        "/api/v1/workflows/" + ownerWorkflow + "/publish",
                        JSON.writeValueAsString(Map.of("runScope", "TENANT")),
                        fixture.owner().sessionCookie(),
                        fixture.owner().csrfToken(),
                        Map.of("Idempotency-Key", "scope-tenant-" + UUID.randomUUID())),
                400,
                WF_400);
        assertThat(publish(fixture.tenantAdmin(), ownerWorkflow, "TENANT", null, "scope-tenant-ok-" + UUID.randomUUID())
                        .status())
                .isEqualTo(201);
    }

    /** 运行标识与状态快照；仅用于本套件内部传递。 */
    private record QueryRun(String runId, String status) {}
}
