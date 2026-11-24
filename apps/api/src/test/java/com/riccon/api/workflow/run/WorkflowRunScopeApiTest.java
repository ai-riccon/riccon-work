package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 运行范围与部门可见性集成测试（T04 run_scope 约束、F-004/F-005）。
 *
 * <p>核心约束：运行准入必须读取“用户所选版本”的 {@code run_scope + department_id}，不能只看最近发布
 * 版本或流程定义。覆盖 v1=ONLY_SELF/v2=TENANT 与反向组合、DEPARTMENT 本部门/他部门/无部门/部门停用、
 * 所有者移出部门后 RUNNABLE 列表与运行准入一致返回 404。
 */
class WorkflowRunScopeApiTest extends WorkflowRunTestSupport {

    /** 创建空白草稿并返回 ID。 */
    private String draft(Fixture fixture, String name) {
        return createDraft(fixture.owner(), name).path("id").asText();
    }

    /** 读取 RUNNABLE 列表。 */
    private JsonNode runnableList(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workflows?view=RUNNABLE&size=100", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /** 判断列表中是否包含流程。 */
    private static boolean contains(JsonNode page, String workflowId) {
        for (JsonNode item : page.path("items")) {
            if (workflowId.equals(item.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("IT-WF-109 v1=ONLY_SELF/v2=TENANT 时普通员工显式选 v1 返回 404、默认 v2 可运行")
    void createRun_onlySelfThenTenant_usesSelectedVersion() {
        Fixture fixture = fixture("wfs");
        String workflowId = draft(fixture, "版本范围收窄后放宽");
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-scope-001");
        UUID v1 = publishedVersionId(fixture.tenant(), workflowId);

        saveDraft(fixture.owner(), workflowId, 2, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-scope-002");

        assertProblem(
                createRun(fixture.member(), workflowId, v1, DEFAULT_INPUTS, "run-scope-001"),
                404,
                TENANT_404);
        JsonNode run = createRunOk(fixture.member(), workflowId, null, "run-scope-002");
        assertThat(run.path("versionNo").asInt()).isEqualTo(2);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-WF-109 v1=TENANT/v2=ONLY_SELF 时普通员工仍可显式运行 v1，默认 v2 返回 404")
    void createRun_tenantThenOnlySelf_usesSelectedVersion() {
        Fixture fixture = fixture("wfs");
        String workflowId = draft(fixture, "版本范围放宽后收窄");
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-scope-003");
        UUID v1 = publishedVersionId(fixture.tenant(), workflowId);

        saveDraft(fixture.owner(), workflowId, 2, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-scope-004");

        JsonNode run = createRunOk(fixture.member(), workflowId, v1, "run-scope-003");
        assertThat(run.path("versionNo").asInt()).isEqualTo(1);
        assertProblem(
                createRun(fixture.member(), workflowId, null, DEFAULT_INPUTS, "run-scope-004"),
                404,
                TENANT_404);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-WF-109 F-004 本部门成员可运行/可见，他部门与无部门 404，部门停用后下一次请求 404")
    void createRun_departmentScope_visibilityMatrix() {
        Fixture fixture = fixture("wfs");
        UUID departmentA = createManagedDepartment(fixture, "部门A", fixture.ownerId());
        UUID departmentB = createDepartment(fixture.platform(), fixture.tenant(), "部门B");
        addDepartmentMember(fixture.platform(), fixture.tenant(), departmentA, fixture.memberId());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LoginSession outsider = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "outsider" + suffix,
                "outsider-password-12",
                List.of("MEMBER"));
        addDepartmentMember(
                fixture.platform(), fixture.tenant(), departmentB, actorId(outsider));
        LoginSession noDepartment = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "nodept" + suffix,
                "nodept-password-12",
                List.of("MEMBER"));

        String workflowId = draft(fixture, "部门范围");
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "DEPARTMENT", departmentA, "publish-scope-005");

        JsonNode run = createRunOk(fixture.member(), workflowId, null, "run-dept-001");
        assertThat(run.path("status").asText()).isEqualTo("PENDING");
        assertProblem(
                createRun(outsider, workflowId, null, DEFAULT_INPUTS, "run-dept-002"),
                404,
                TENANT_404);
        assertProblem(
                createRun(noDepartment, workflowId, null, DEFAULT_INPUTS, "run-dept-003"),
                404,
                TENANT_404);

        // 详情与 RUNNABLE 列表使用同一范围谓词：本部门可见，他部门/无部门 404。
        assertThat(getDetail(fixture.member(), workflowId, 200).path("id").asText())
                .isEqualTo(workflowId);
        getDetail(outsider, workflowId, 404);
        getDetail(noDepartment, workflowId, 404);
        assertThat(contains(runnableList(fixture.member()), workflowId)).isTrue();
        assertThat(contains(runnableList(outsider), workflowId)).isFalse();
        assertThat(contains(runnableList(noDepartment), workflowId)).isFalse();

        // 部门停用：下一次请求按新范围判定；租户管理员仍保留管理能力。
        disableDepartment(fixture.tenant(), departmentA);
        assertProblem(
                createRun(fixture.member(), workflowId, null, DEFAULT_INPUTS, "run-dept-004"),
                404,
                TENANT_404);
        assertProblem(
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-dept-005"),
                404,
                TENANT_404);
        JsonNode adminRun = createRunOk(fixture.tenantAdmin(), workflowId, null, "run-dept-006");
        assertThat(adminRun.path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("IT-WF-109 F-005 所有者移出部门后 RUNNABLE 列表与运行准入一致返回 404")
    void createRun_ownerMovedOutOfDepartment_runAndRunnableAgree() {
        Fixture fixture = fixture("wfs");
        UUID departmentA = createManagedDepartment(fixture, "原部门", fixture.ownerId());
        addDepartmentMember(fixture.platform(), fixture.tenant(), departmentA, fixture.memberId());
        String workflowId = draft(fixture, "移出部门");
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "DEPARTMENT", departmentA, "publish-scope-006");

        // 所有者移出部门：下一次请求按新范围判定，列表与运行准入都不再包含该流程。
        moveUserDepartment(fixture.tenant(), fixture.ownerId(), null);
        assertThat(contains(runnableList(fixture.owner()), workflowId)).isFalse();
        assertProblem(
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-dept-007"),
                404,
                TENANT_404);

        // 仍在部门内的成员保持可运行；租户管理员按管理能力可运行任意版本。
        createRunOk(fixture.member(), workflowId, null, "run-dept-008");
        createRunOk(fixture.tenantAdmin(), workflowId, null, "run-dept-009");
        assertThat(runCount(fixture.tenant())).isEqualTo(2);
    }
}
