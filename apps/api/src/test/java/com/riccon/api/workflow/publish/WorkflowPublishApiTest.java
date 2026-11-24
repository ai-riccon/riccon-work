package com.riccon.api.workflow.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.domain.WorkflowGraphJson;
import com.riccon.api.workflow.version.WorkflowVersionTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * 发布 API 集成测试（{@code IT-WF-102}，AC-03/16）。
 *
 * <p>覆盖：发布成功写入不可变版本、规范化哈希、runScope、发布指针与同事务审计；发布失败（NO_APPROVER/
 * 范围非法）返回 400 且不产生版本、不改当前发布指针；同一草稿重复发布幂等、不同草稿发布新版本；旧运行
 * 保持原版本绑定；V15 触发器拒绝更新/删除版本；发布权限与范围矩阵。
 *
 * <p>真实 MySQL + Flyway V15 + 真实会话/CSRF/权限链；不使用 repository 直写绕过发布 API。
 */
class WorkflowPublishApiTest extends WorkflowVersionTestSupport {

    /** 发布成功：版本快照、指针、审计与详情能力标记一致。 */
    @Test
    @DisplayName("IT-WF-102 发布成功写入不可变版本与审计")
    void publish_success_writesVersionAndAudit() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "发布流程").path("id").asText();
        String graph = defaultGraph(fixture);
        saveDraft(fixture.owner(), workflowId, 1, graph);

        JsonNode published = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0001");
        assertThat(published.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(published.path("versionNo").asInt()).isEqualTo(1);
        assertThat(published.path("runScope").asText()).isEqualTo("ONLY_SELF");
        assertThat(published.path("nodeCount").asInt()).isEqualTo(7);
        assertThat(published.path("publishedBy").asText())
                .isEqualTo(fixture.ownerId().toString());
        assertThat(published.path("graphSha256").asText()).matches("^[a-f0-9]{64}$");
        assertThat(published.path("departmentId").isMissingNode()).isTrue();
        assertThat(published.path("publishedAt").asText()).isNotBlank();

        List<VersionRow> rows = versionRows(fixture.tenant(), workflowId);
        assertThat(rows).hasSize(1);
        VersionRow row = rows.get(0);
        assertThat(row.versionNo()).isEqualTo(1);
        assertThat(row.runScope()).isEqualTo("ONLY_SELF");
        assertThat(row.nodeCount()).isEqualTo(7);
        assertThat(row.publishedBy()).isEqualTo(fixture.ownerId());
        assertThat(row.graphSha256()).isEqualTo(published.path("graphSha256").asText());
        // MySQL JSON 列会重排键与空白；用规范化哈希确认快照与草稿图语义一致。
        assertThat(row.computedGraphSha256()).isEqualTo(row.graphSha256());
        assertThat(row.computedGraphSha256()).isEqualTo(WorkflowGraphJson.sha256(WorkflowGraphJson.parse(graph)));
        assertThat(publishedVersionId(fixture.tenant(), workflowId)).isEqualTo(row.id());
        assertThat(publishedVersionNo(fixture.tenant(), workflowId)).isEqualTo(1);

        List<AuditRow> audits = auditRows("workflow.published").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .toList();
        assertThat(audits).hasSize(1);
        AuditRow audit = audits.get(0);
        assertThat(audit.actorId()).isEqualTo(fixture.ownerId().toString());
        assertThat(audit.targetType()).isEqualTo("WORKFLOW");
        assertThat(audit.outcome()).isEqualTo("SUCCESS");
        JsonNode details = JSON.readTree(audit.details());
        assertThat(details.path("workflowId").asText()).isEqualTo(workflowId);
        assertThat(details.path("workflowVersionId").asText())
                .isEqualTo(row.id().toString());
        assertThat(details.path("versionNo").asInt()).isEqualTo(1);
        assertThat(details.path("runScope").asText()).isEqualTo("ONLY_SELF");
        assertThat(details.path("nodeCount").asInt()).isEqualTo(7);
        assertThat(details.path("departmentId").isNull()).isTrue();
        assertThat(audit.details()).doesNotContain("SECRET-PROMPT-MARKER");

        JsonNode detail = getDetail(fixture.owner(), workflowId, 200);
        assertThat(detail.path("latestVersion").path("versionNo").asInt()).isEqualTo(1);
        assertThat(detail.path("latestVersion").path("runScope").asText()).isEqualTo("ONLY_SELF");
        assertThat(detail.path("publishedVersionNo").asInt()).isEqualTo(1);
        assertThat(detail.path("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(detail.path("canRun").asBoolean()).isTrue();
        assertThat(detail.path("canEdit").asBoolean()).isTrue();

        // ONLY_SELF 对其他同租户员工不可见，统一 404 且不泄露名称。
        assertThat(getDetail(fixture.member(), workflowId, 404).path("code").asText())
                .isEqualTo(TENANT_404);
    }

    /** 发布失败：NO_APPROVER 返回 400，不产生版本、不改当前发布指针、不写发布审计。 */
    @Test
    @DisplayName("IT-WF-102 发布校验失败不产生版本且不改发布指针")
    void publish_validationFailure_keepsPointerAndWritesNoVersion() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "失败发布").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        JsonNode first = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0101");
        UUID firstVersionId = UUID.fromString(first.path("id").asText());

        // 保存一个新草稿：审批人只配置发布者本人，SAVE 允许但 PUBLISH 必须 NO_APPROVER 阻断。
        saveDraft(fixture.owner(), workflowId, 2, graph(fixture.toolId(), userApprover(fixture.ownerId())));
        ApiTestClient.ApiResponse failed = publish(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0102");
        assertProblem(failed, 400, WF_400);
        assertThat(client.json(failed).path("detail").asText()).contains("NO_APPROVER");

        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(publishedVersionId(fixture.tenant(), workflowId)).isEqualTo(firstVersionId);
        assertThat(publishedVersionNo(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.published").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);
        assertThat(getDetail(fixture.owner(), workflowId, 200)
                        .path("latestVersion")
                        .path("versionNo")
                        .asInt())
                .isEqualTo(1);
    }

    /** 发布范围：编辑者只能 ONLY_SELF/DEPARTMENT，租户管理员只能 ONLY_SELF/TENANT；范围变更发布新版本并审计。 */
    @Test
    @DisplayName("IT-WF-102 发布范围角色限制与范围变更")
    void publish_scopeRules_andScopeChange() {
        Fixture fixture = fixture("wfp");
        UUID ownerDepartment = createManagedDepartment(fixture, "销售部", fixture.ownerId());
        UUID otherDepartment = createManagedDepartment(fixture, "市场部", fixture.otherEditorId());
        String workflowId = createDraft(fixture.owner(), "范围流程").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(publish(fixture.owner(), workflowId, "TENANT", null, "publish-key-0201"), 400, WF_400);
        assertProblem(
                publish(fixture.owner(), workflowId, "DEPARTMENT", otherDepartment, "publish-key-0202"), 400, WF_400);
        assertThat(versionCount(fixture.tenant(), workflowId)).isZero();

        JsonNode departmentVersion =
                publishOk(fixture.owner(), workflowId, "DEPARTMENT", ownerDepartment, "publish-key-0203");
        assertThat(departmentVersion.path("versionNo").asInt()).isEqualTo(1);
        assertThat(departmentVersion.path("runScope").asText()).isEqualTo("DEPARTMENT");
        assertThat(departmentVersion.path("departmentId").asText()).isEqualTo(ownerDepartment.toString());

        // 同一草稿改范围必须发布新版本；旧版本保留原范围（AC-03）。
        assertProblem(publish(fixture.owner(), workflowId, "TENANT", null, "publish-key-0204"), 400, WF_400);
        JsonNode tenantVersion = publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-key-0205");
        assertThat(tenantVersion.path("versionNo").asInt()).isEqualTo(2);
        assertThat(tenantVersion.path("runScope").asText()).isEqualTo("TENANT");
        assertThat(tenantVersion.path("departmentId").isMissingNode()).isTrue();

        // 租户管理员不能发布 DEPARTMENT 范围。
        assertProblem(
                publish(fixture.tenantAdmin(), workflowId, "DEPARTMENT", ownerDepartment, "publish-key-0206"),
                400,
                WF_400);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(2);

        List<VersionRow> rows = versionRows(fixture.tenant(), workflowId);
        assertThat(rows.get(0).runScope()).isEqualTo("DEPARTMENT");
        assertThat(rows.get(0).departmentId()).isEqualTo(ownerDepartment);
        assertThat(rows.get(1).runScope()).isEqualTo("TENANT");
        assertThat(rows.get(1).departmentId()).isNull();

        List<JsonNode> publishedDetails = auditRows("workflow.published").stream()
                .filter(audit -> audit.tenantId().equals(fixture.tenant())
                        && audit.targetId().equals(workflowId))
                .map(audit -> JSON.readTree(audit.details()))
                .toList();
        assertThat(publishedDetails).hasSize(2);
        // 审计行排序可能受同毫秒 UUIDv7 影响，按 versionNo/runScope 组合断言而不依赖数组顺序。
        assertThat(publishedDetails.stream()
                        .map(details -> details.path("runScope").asText())
                        .toList())
                .containsExactlyInAnyOrder("DEPARTMENT", "TENANT");
        JsonNode departmentAudit = publishedDetails.stream()
                .filter(details -> details.path("versionNo").asInt() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(departmentAudit.path("runScope").asText()).isEqualTo("DEPARTMENT");
        assertThat(departmentAudit.path("departmentId").asText()).isEqualTo(ownerDepartment.toString());
        JsonNode tenantAudit = publishedDetails.stream()
                .filter(details -> details.path("versionNo").asInt() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(tenantAudit.path("runScope").asText()).isEqualTo("TENANT");
        assertThat(tenantAudit.path("departmentId").isNull()).isTrue();
    }

    /** 同一草稿重复发布幂等；草稿变化发布新版本；旧运行绑定与旧版本快照不变；触发器拒绝改删。 */
    @Test
    @DisplayName("IT-WF-102 重复发布幂等、旧版本绑定与不可变触发器")
    void publish_idempotent_andOldVersionStaysBound() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "幂等发布").path("id").asText();
        String firstGraph = defaultGraph(fixture);
        saveDraft(fixture.owner(), workflowId, 1, firstGraph);
        JsonNode first = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0301");
        UUID firstVersionId = UUID.fromString(first.path("id").asText());
        VersionRow firstRow = versionRows(fixture.tenant(), workflowId).get(0);

        JsonNode retry = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0302");
        assertThat(retry.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(retry.path("versionNo").asInt()).isEqualTo(1);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.published").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);

        // 旧运行绑定 v1；发布 v2 后仍指向 v1，且 v1 快照不变。
        UUID runId = insertRunFixture(fixture.tenant(), workflowId, firstVersionId, 1, fixture.memberId());
        String secondGraph = firstGraph.replace("生成报告", "生成报告 v2");
        saveDraft(fixture.owner(), workflowId, 2, secondGraph);
        JsonNode second = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0303");
        assertThat(second.path("versionNo").asInt()).isEqualTo(2);
        assertThat(runVersionId(fixture.tenant(), runId)).isEqualTo(firstVersionId);
        assertThat(publishedVersionId(fixture.tenant(), workflowId))
                .isEqualTo(UUID.fromString(second.path("id").asText()));
        assertThat(publishedVersionNo(fixture.tenant(), workflowId)).isEqualTo(2);

        List<VersionRow> rows = versionRows(fixture.tenant(), workflowId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).id()).isEqualTo(firstRow.id());
        assertThat(rows.get(0).graphJson()).isEqualTo(firstRow.graphJson());
        assertThat(rows.get(0).graphSha256()).isEqualTo(firstRow.graphSha256());
        assertThat(rows.get(0).runScope()).isEqualTo("ONLY_SELF");
        assertThat(rows.get(1).id()).isNotEqualTo(firstRow.id());

        // V15 触发器拒绝修改/删除版本（版本不可变的数据库层保证）。
        assertThatThrownBy(() -> jdbcClient
                        .sql("UPDATE workflow_version SET node_count = 99 WHERE tenant_id = :tenant AND id = :id")
                        .param("tenant", fixture.tenant())
                        .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(firstVersionId))
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(versionRows(fixture.tenant(), workflowId).get(0).nodeCount()).isEqualTo(7);
        assertThatThrownBy(() -> jdbcClient
                        .sql("DELETE FROM workflow_version WHERE tenant_id = :tenant AND id = :id")
                        .param("tenant", fixture.tenant())
                        .param("id", com.riccon.api.persistence.BinaryUuids.toBytes(firstVersionId))
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(2);
    }

    /** 发布请求形状/可选幂等键校验：缺失键接受，过短/过长、未知字段、非法范围均 400 且不产生版本。 */
    @Test
    @DisplayName("IT-WF-102 发布请求与可选幂等键校验")
    void publish_requestValidation() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "发布校验").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(publish(fixture.owner(), workflowId, "ONLY_SELF", null, "short"), 400, WF_400);
        assertProblem(publish(fixture.owner(), workflowId, "ONLY_SELF", null, "a".repeat(129)), 400, WF_400);
        assertProblem(publish(fixture.owner(), workflowId, null, null, "publish-key-0401"), 400, WF_400);
        assertProblem(publish(fixture.owner(), workflowId, "UNKNOWN", null, "publish-key-0402"), 400, WF_400);
        assertProblem(publish(fixture.owner(), workflowId, "DEPARTMENT", null, "publish-key-0403"), 400, WF_400);
        assertProblem(
                publish(fixture.owner(), workflowId, "ONLY_SELF", UUID.randomUUID(), "publish-key-0404"), 400, WF_400);
        assertProblem(publish(fixture.owner(), workflowId, "TENANT", null, "publish-key-0406"), 400, WF_400);

        ApiTestClient.ApiResponse unknownField = client.post(
                "/api/v1/workflows/" + workflowId + "/publish",
                "{\"runScope\":\"ONLY_SELF\",\"unknown\":true}",
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of("Idempotency-Key", "publish-key-0405"));
        assertProblem(unknownField, 400, WF_400);

        assertThat(versionCount(fixture.tenant(), workflowId)).isZero();
        assertThat(publishedVersionId(fixture.tenant(), workflowId)).isNull();
    }

    /** 发布不要求 Idempotency-Key：缺失键可发布，重复发布仍按资源级幂等返回同一版本。 */
    @Test
    @DisplayName("IT-WF-102 发布缺失 Idempotency-Key 仍成功且资源级幂等")
    void publish_withoutIdempotencyKey_isAcceptedAndIdempotent() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "无键发布").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        JsonNode first = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, null);
        JsonNode second = publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, null);

        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("versionNo").asInt()).isEqualTo(1);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(1);
        assertThat(auditRows("workflow.published").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);
    }

    /** VALIDATE 与 PUBLISH 同口径：范围组合/角色/部门结果一致，且校验只读。 */
    @Test
    @DisplayName("IT-WF-102 VALIDATE 范围与 PUBLISH 同口径且只读")
    void validate_scopeMatchesPublish_andStaysReadOnly() {
        Fixture fixture = fixture("wfp");
        UUID ownerDepartment = createManagedDepartment(fixture, "销售部", fixture.ownerId());
        UUID otherDepartment = createManagedDepartment(fixture, "市场部", fixture.otherEditorId());
        String workflowId = createDraft(fixture.owner(), "范围校验").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        int draftVersion =
                getDetail(fixture.owner(), workflowId, 200).path("draftVersion").asInt();

        List<ScopeCase> cases = List.of(
                new ScopeCase(fixture.owner(), "ONLY_SELF", null, true),
                new ScopeCase(fixture.owner(), "DEPARTMENT", ownerDepartment, true),
                new ScopeCase(fixture.owner(), "TENANT", null, false),
                new ScopeCase(fixture.owner(), "DEPARTMENT", otherDepartment, false),
                new ScopeCase(fixture.owner(), "DEPARTMENT", UUID.randomUUID(), false),
                new ScopeCase(fixture.owner(), "ONLY_SELF", ownerDepartment, false),
                new ScopeCase(fixture.tenantAdmin(), "TENANT", null, true),
                new ScopeCase(fixture.tenantAdmin(), "DEPARTMENT", ownerDepartment, false));
        int published = 0;
        for (ScopeCase scopeCase : cases) {
            ApiTestClient.ApiResponse validation =
                    validate(scopeCase.session(), workflowId, scopeCase.runScope(), scopeCase.departmentId());
            if (scopeCase.allowed()) {
                assertThat(validation.status()).as(validation.body()).isEqualTo(200);
                assertThat(client.json(validation).path("valid").asBoolean())
                        .as(scopeCase.toString())
                        .isTrue();
            } else {
                assertProblem(validation, 400, WF_400);
            }

            ApiTestClient.ApiResponse publish = publish(
                    scopeCase.session(),
                    workflowId,
                    scopeCase.runScope(),
                    scopeCase.departmentId(),
                    "publish-key-scope-" + published);
            if (scopeCase.allowed()) {
                assertThat(publish.status()).as(publish.body()).isEqualTo(201);
                published++;
            } else {
                assertProblem(publish, 400, WF_400);
            }
        }

        // 省略范围：只校图/审批可达；与带范围的成功/失败互不影响。
        ApiTestClient.ApiResponse omitted = validate(fixture.owner(), workflowId, null, null);
        assertThat(omitted.status()).as(omitted.body()).isEqualTo(200);
        assertThat(client.json(omitted).path("valid").asBoolean()).isTrue();

        // 非法参数统一 400 RICCON-WF-400。
        assertProblem(validate(fixture.owner(), workflowId, "UNKNOWN", null), 400, WF_400);
        assertProblem(validate(fixture.owner(), workflowId, null, ownerDepartment), 400, WF_400);

        assertThat(getDetail(fixture.owner(), workflowId, 200)
                        .path("draftVersion")
                        .asInt())
                .isEqualTo(draftVersion);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(published);
        assertThat(auditRows("workflow.draft.saved").stream()
                        .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                && audit.targetId().equals(workflowId))
                        .count())
                .isEqualTo(1);
    }

    /** 并发发布同一草稿：行锁串行化，第二个请求返回既有版本，不产生重复版本。 */
    @Test
    @DisplayName("IT-WF-102 并发发布同一草稿只产生一个版本")
    void publish_concurrentSameDraft_producesSingleVersion() throws Exception {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "并发发布").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiTestClient.ApiResponse>> futures = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return publish(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0601");
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return publish(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-0602");
                    }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ApiTestClient.ApiResponse> responses = List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            for (ApiTestClient.ApiResponse response : responses) {
                assertThat(response.status()).as(response.body()).isEqualTo(201);
            }
            assertThat(client.json(responses.get(0)).path("id").asText())
                    .isEqualTo(client.json(responses.get(1)).path("id").asText());
            assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(1);
            assertThat(auditRows("workflow.published").stream()
                            .filter(audit -> audit.tenantId().equals(fixture.tenant())
                                    && audit.targetId().equals(workflowId))
                            .count())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 发布权限：私有草稿对其他编辑者 404，已发布 TENANT 可见但非所有者 403；跨租户/平台管理员 404。 */
    @Test
    @DisplayName("IT-WF-102 发布权限 403/404 矩阵")
    void publish_permissionMatrix() {
        Fixture fixture = fixture("wfp");
        String workflowId = createDraft(fixture.owner(), "权限发布").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        assertProblem(
                publish(fixture.otherEditor(), workflowId, "ONLY_SELF", null, "publish-key-0501"), 404, TENANT_404);

        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-key-0502");
        assertProblem(publish(fixture.otherEditor(), workflowId, "ONLY_SELF", null, "publish-key-0503"), 403, AUTH_403);
        assertProblem(publish(fixture.member(), workflowId, "ONLY_SELF", null, "publish-key-0504"), 403, AUTH_403);

        String otherTenant = uniqueTenantCode("wfx");
        createTenant(fixture.platform(), otherTenant);
        LoginSession crossTenantEditor = seedAndLoginLocalUser(
                fixture.platform(),
                otherTenant,
                "cross" + UUID.randomUUID(),
                "cross-password-12",
                List.of("WORKFLOW_EDITOR"));
        assertProblem(publish(crossTenantEditor, workflowId, "ONLY_SELF", null, "publish-key-0505"), 404, TENANT_404);

        LoginSession platformAdmin = loginPlatformAdmin();
        assertProblem(publish(platformAdmin, workflowId, "ONLY_SELF", null, "publish-key-0506"), 404, TENANT_404);
        assertThat(versionCount(fixture.tenant(), workflowId)).isEqualTo(1);
    }

    /**
     * 调用 VALIDATE 端点，按需附加范围 query 参数。
     *
     * @param session 会话
     * @param workflowId 流程 ID
     * @param runScope 运行范围；可空
     * @param departmentId 部门 ID；可空
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse validate(
            LoginSession session, String workflowId, String runScope, UUID departmentId) {
        List<String> parameters = new ArrayList<>();
        if (runScope != null) {
            parameters.add("runScope=" + runScope);
        }
        if (departmentId != null) {
            parameters.add("departmentId=" + departmentId);
        }
        String suffix = parameters.isEmpty() ? "" : "?" + String.join("&", parameters);
        return client.post(
                "/api/v1/workflows/" + workflowId + "/validate" + suffix,
                null,
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * VALIDATE/PUBLISH 同口径对照用例。
     *
     * @param session 调用会话
     * @param runScope 运行范围
     * @param departmentId 部门 ID；可空
     * @param allowed 该范围是否允许发布
     */
    private record ScopeCase(LoginSession session, String runScope, UUID departmentId, boolean allowed) {}
}
