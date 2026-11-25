package com.riccon.api.workflow.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 运行创建/准入集成测试（IT-WF-109 的资源上限与 AC-03/17）。
 *
 * <p>覆盖：默认绑定最近发布版本与显式选择旧版本、创建幂等键、M04 配额按运行 1 单位、租户 10/单流程 5
 * 并发上限、输入 64 KiB/16 键/键 pattern、未发布/停用/不可见 404 或 400、平台管理员 404。所有断言走
 * 真实 MySQL、会话、CSRF 与权限链。
 */
class WorkflowRunAdmissionApiTest extends WorkflowRunTestSupport {

    /** 创建空白草稿并返回 ID。 */
    private String draft(Fixture fixture, String name) {
        return createDraft(fixture.owner(), name).path("id").asText();
    }

    /** 发布默认图并返回流程 ID；TENANT 范围必须由租户管理员发布。 */
    private String publishWorkflow(Fixture fixture, String name, String scope) {
        String workflowId = draft(fixture, name);
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        LoginSession publisher = "TENANT".equals(scope) ? fixture.tenantAdmin() : fixture.owner();
        publishOk(publisher, workflowId, scope, null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    @Test
    @DisplayName("IT-WF-109 默认绑定最近发布版本，显式版本仍可按选择运行")
    void createRun_bindsSelectedVersion() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "运行版本", "ONLY_SELF");
        UUID v1 = publishedVersionId(fixture.tenant(), workflowId);
        assertThat(v1).isNotNull();

        JsonNode first = createRunOk(fixture.owner(), workflowId, null, "run-key-0001");
        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("currentNodeId").asText()).isEqualTo("start");
        assertThat(first.path("versionNo").asInt()).isEqualTo(1);
        assertThat(first.path("workflowVersionId").asText()).isEqualTo(v1.toString());
        assertThat(first.path("createdAt").asText()).endsWith("+08:00");
        RunRow row = runRow(fixture.tenant(), UUID.fromString(first.path("id").asText()));
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.workflowVersionId()).isEqualTo(v1);

        // 第二版（TENANT）发布后：不选版本默认绑定 v2，显式 v1 仍可运行（AC-03）。
        saveDraft(fixture.owner(), workflowId, 2, defaultGraph(fixture));
        publishOk(fixture.tenantAdmin(), workflowId, "TENANT", null, "publish-" + UUID.randomUUID());
        UUID v2 = publishedVersionId(fixture.tenant(), workflowId);
        assertThat(v2).isNotEqualTo(v1);

        JsonNode defaultRun = createRunOk(fixture.owner(), workflowId, null, "run-key-0002");
        assertThat(defaultRun.path("workflowVersionId").asText()).isEqualTo(v2.toString());
        JsonNode oldRun = createRunOk(fixture.owner(), workflowId, v1, "run-key-0003");
        assertThat(oldRun.path("workflowVersionId").asText()).isEqualTo(v1.toString());
        assertThat(runRow(fixture.tenant(), UUID.fromString(oldRun.path("id").asText()))
                        .workflowVersionId())
                .isEqualTo(v1);
    }

    @Test
    @DisplayName("IT-WF-109 同一幂等键返回既有运行且只扣一次配额/写一条审计")
    void createRun_sameIdempotencyKey_returnsExisting() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "运行幂等", "TENANT");

        JsonNode first = createRunOk(fixture.owner(), workflowId, null, "run-idem-001");
        JsonNode replay = createRunOk(fixture.owner(), workflowId, null, "run-idem-001");
        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.requested")).hasSize(1);
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);

        // 不同键创建第二条运行，证明幂等不是“每个流程只允许一次”。
        JsonNode second = createRunOk(fixture.owner(), workflowId, null, "run-idem-002");
        assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());
        assertThat(runCount(fixture.tenant())).isEqualTo(2);
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("IT-WF-109 M04 配额按运行 1 单位准入，超限返回 RICCON-QUOTA-429")
    void createRun_quotaExceeded_returnsQuota429() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "运行配额", "TENANT");
        setQuotaEnforcement(true);
        try {
            setTenantQuota(fixture.platform(), fixture.tenant(), 1, 10);
            setUserQuota(fixture.platform(), fixture.tenant(), fixture.ownerId(), 1, 10);

            createRunOk(fixture.owner(), workflowId, null, "run-quota-001");
            ApiTestClient.ApiResponse rejected =
                    createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-quota-002");
            assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
            assertThat(client.json(rejected).path("code").asText()).isEqualTo(QUOTA_429);
            assertThat(runCount(fixture.tenant())).isEqualTo(1);
            assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                    .isEqualTo(1);
        } finally {
            // 测试库跨类共享；恢复默认关闭，避免影响其他配额用例的假设。
            setQuotaEnforcement(false);
        }
    }

    @Test
    @DisplayName("IT-WF-109 租户活跃运行达到 10 时返回 RICCON-WF-429")
    void createRun_tenantConcurrencyLimit_returns429() {
        Fixture fixture = fixture("wfr");
        String workflowA = publishWorkflow(fixture, "租户并发A", "TENANT");
        String workflowB = publishWorkflow(fixture, "租户并发B", "TENANT");
        String workflowC = publishWorkflow(fixture, "租户并发C", "TENANT");
        UUID versionA = publishedVersionId(fixture.tenant(), workflowA);
        UUID versionB = publishedVersionId(fixture.tenant(), workflowB);
        for (int index = 0; index < 5; index++) {
            insertActiveRun(fixture.tenant(), workflowA, versionA, 1, fixture.ownerId(), "PENDING", "start");
            insertActiveRun(fixture.tenant(), workflowB, versionB, 1, fixture.ownerId(), "RUNNING", "start");
        }

        ApiTestClient.ApiResponse rejected =
                createRun(fixture.owner(), workflowC, null, DEFAULT_INPUTS, "run-tenant-limit");
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
        JsonNode problem = client.json(rejected);
        assertThat(problem.path("code").asText()).isEqualTo(WF_429);
        assertThat(problem.path("detail").asText()).contains("tenant").contains("limit=10");
        assertThat(runCount(fixture.tenant(), workflowC)).isZero();
    }

    @Test
    @DisplayName("IT-WF-109 单流程活跃运行达到 5 时返回 RICCON-WF-429")
    void createRun_flowConcurrencyLimit_returns429() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "流程并发", "TENANT");
        UUID versionId = publishedVersionId(fixture.tenant(), workflowId);
        for (int index = 0; index < 5; index++) {
            insertActiveRun(
                    fixture.tenant(),
                    workflowId,
                    versionId,
                    1,
                    fixture.ownerId(),
                    index % 2 == 0 ? "PENDING" : "WAITING_APPROVAL",
                    "start");
        }

        ApiTestClient.ApiResponse rejected =
                createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "run-flow-limit");
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
        JsonNode problem = client.json(rejected);
        assertThat(problem.path("code").asText()).isEqualTo(WF_429);
        assertThat(problem.path("detail").asText()).contains("workflow").contains("limit=5");
        assertThat(runCount(fixture.tenant(), workflowId)).isEqualTo(5);
    }

    @Test
    @DisplayName("IT-WF-109 输入超过 16 键/64 KiB 或键非法返回 RICCON-WF-400")
    void createRun_inputBoundaries_return400() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "运行输入", "TENANT");

        StringBuilder seventeenKeys = new StringBuilder("{");
        for (int index = 0; index < 17; index++) {
            if (index > 0) {
                seventeenKeys.append(',');
            }
            seventeenKeys.append('"').append("k").append(index).append("\":").append(index);
        }
        seventeenKeys.append('}');

        ApiTestClient.ApiResponse tooManyKeys =
                createRun(fixture.owner(), workflowId, null, seventeenKeys.toString(), "run-input-001");
        assertProblem(tooManyKeys, 400, WF_400);

        ApiTestClient.ApiResponse badKey =
                createRun(fixture.owner(), workflowId, null, "{\"bad key\":\"value\"}", "run-input-002");
        assertProblem(badKey, 400, WF_400);

        String oversized = "{\"topic\":\"" + "a".repeat(70 * 1024) + "\"}";
        ApiTestClient.ApiResponse tooLarge = createRun(fixture.owner(), workflowId, null, oversized, "run-input-003");
        assertProblem(tooLarge, 400, WF_400);

        ApiTestClient.ApiResponse unknownField = client.post(
                "/api/v1/workflows/" + workflowId + "/runs",
                "{\"inputs\":{\"topic\":\"x\"},\"unexpected\":\"x\"}",
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                java.util.Map.of("Idempotency-Key", "run-input-004"));
        assertProblem(unknownField, 400, WF_400);

        ApiTestClient.ApiResponse malformedInputs = client.post(
                "/api/v1/workflows/" + workflowId + "/runs",
                "{\"inputs\":\"not-an-object\"}",
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                java.util.Map.of("Idempotency-Key", "run-input-006"));
        assertProblem(malformedInputs, 400, WF_400);

        ApiTestClient.ApiResponse missingBody = client.post(
                "/api/v1/workflows/" + workflowId + "/runs",
                null,
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                java.util.Map.of("Idempotency-Key", "run-input-005"));
        assertProblem(missingBody, 400, WF_400);

        ApiTestClient.ApiResponse missingKey = createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, null);
        assertProblem(missingKey, 400, WF_400);

        ApiTestClient.ApiResponse shortKey = createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "short");
        assertProblem(shortKey, 400, WF_400);

        assertThat(runCount(fixture.tenant())).isZero();
        assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.requested")).isEmpty();
    }

    @Test
    @DisplayName("IT-WF-109 未发布/停用流程拒绝运行，未知或跨流程版本 404")
    void createRun_unpublishedDisabledAndVersionVisibility() {
        Fixture fixture = fixture("wfr");
        String unpublished = draft(fixture, "未发布");
        assertProblem(createRun(fixture.owner(), unpublished, null, DEFAULT_INPUTS, "run-state-001"), 400, WF_400);

        String disabled = publishWorkflow(fixture, "停用", "TENANT");
        assertThat(disable(fixture.owner(), disabled).status()).isEqualTo(200);
        assertProblem(createRun(fixture.owner(), disabled, null, DEFAULT_INPUTS, "run-state-002"), 400, WF_400);

        String other = publishWorkflow(fixture, "另一个流程", "TENANT");
        UUID otherVersion = publishedVersionId(fixture.tenant(), other);
        String target = publishWorkflow(fixture, "目标流程", "TENANT");
        assertProblem(
                createRun(fixture.owner(), target, otherVersion, DEFAULT_INPUTS, "run-state-003"), 404, TENANT_404);
        assertProblem(
                createRun(fixture.owner(), UUID.randomUUID().toString(), null, DEFAULT_INPUTS, "run-state-004"),
                404,
                TENANT_404);
        assertProblem(
                createRun(fixture.owner(), target, UUID.randomUUID(), DEFAULT_INPUTS, "run-state-005"),
                404,
                TENANT_404);
        assertThat(runCount(fixture.tenant())).isZero();
    }

    @Test
    @DisplayName("IT-WF-109 并发创建：单流程 5/租户 10 精确，被拒绝不扣配额")
    void createRun_parallelAdmissionLimits_areExact() throws Exception {
        Fixture fixture = fixture("wfr");
        String workflowA = publishWorkflow(fixture, "并发上限A", "TENANT");
        String workflowB = publishWorkflow(fixture, "并发上限B", "TENANT");
        String workflowC = publishWorkflow(fixture, "并发上限C", "TENANT");
        setQuotaEnforcement(true);
        try {
            setTenantQuota(fixture.platform(), fixture.tenant(), 1000, 1000);
            setUserQuota(fixture.platform(), fixture.tenant(), fixture.ownerId(), 1000, 1000);

            List<Integer> firstWave = runConcurrentCreates(fixture, workflowA, "wf-limit-a-", 10);
            assertThat(firstWave).filteredOn(status -> status == 201).hasSize(5);
            assertThat(firstWave).filteredOn(status -> status == 429).hasSize(5);
            assertThat(firstWave).filteredOn(status -> status >= 500).isEmpty();
            assertThat(runCount(fixture.tenant())).isEqualTo(5);

            List<Integer> secondWave = runConcurrentCreates(fixture, workflowB, "wf-limit-b-", 5);
            assertThat(secondWave).containsOnly(201);
            assertThat(runCount(fixture.tenant())).isEqualTo(10);

            // 租户活跃运行已达 10：第三条流程的创建被租户上限拒绝，且配额计数回滚。
            ApiTestClient.ApiResponse rejected =
                    createRun(fixture.owner(), workflowC, null, DEFAULT_INPUTS, "wf-limit-c-1");
            assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
            JsonNode problem = client.json(rejected);
            assertThat(problem.path("code").asText()).isEqualTo(WF_429);
            assertThat(problem.path("detail").asText()).contains("tenant").contains("limit=10");
            assertThat(runCount(fixture.tenant(), workflowC)).isZero();

            assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                    .isEqualTo(10);
            assertThat(quotaUsed(fixture.tenant(), "USER", fixture.ownerId().toString(), "DAILY"))
                    .isEqualTo(10);
            assertThat(tenantAuditRows(fixture.tenant(), "workflow.run.requested"))
                    .hasSize(10);
        } finally {
            setQuotaEnforcement(false);
        }
    }

    /**
     * 并发创建同一流程的运行并返回状态码。
     *
     * @param fixture 夹具
     * @param workflowId 流程 ID
     * @param keyPrefix 幂等键前缀
     * @param concurrency 并发数
     * @return 状态码
     * @throws Exception 并发执行失败
     */
    private List<Integer> runConcurrentCreates(Fixture fixture, String workflowId, String keyPrefix, int concurrency)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(concurrency);
        try {
            for (int index = 0; index < concurrency; index++) {
                int ordinal = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, keyPrefix + ordinal)
                            .status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(concurrency);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("IT-WF-109 平台管理员与跨租户创建运行统一 404")
    void createRun_platformAndCrossTenant_return404() {
        Fixture fixture = fixture("wfr");
        String workflowId = publishWorkflow(fixture, "跨租户", "TENANT");
        Fixture other = fixture("wfr");

        assertProblem(
                createRun(fixture.platform(), workflowId, null, DEFAULT_INPUTS, "run-tenant-001"), 404, TENANT_404);
        assertProblem(createRun(other.owner(), workflowId, null, DEFAULT_INPUTS, "run-tenant-002"), 404, TENANT_404);
        assertThat(runCount(fixture.tenant())).isZero();
    }
}
