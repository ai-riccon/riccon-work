package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.chat.domain.ChatSubmission;
import com.riccon.api.chat.dto.SendMessageRequest;
import com.riccon.api.chat.service.ChatSubmissionService;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * IT-QUOTA-107：J1/J2/M06 混合 50 VU 并发准入（ADR-0022 §1.3-R）。
 *
 * <p>真实 MySQL、真实事务：J1 走 {@link ChatSubmissionService#prepare}（不依赖 Worker）、J2 与 M06 走真实
 * HTTP 创建端点。断言各 scope 的 {@code used_count} 严格等于成功创建数（失败/并发上限回滚不扣配额）、
 * 无 5xx、无重复对象；同键 10 并发只产生 1 个对象与 1 次配额；J1 提交准入 p95 ≤3s。M06 运行准入的合同预算
 * （p95 ≤500ms）由 PERF-M06-001 在真实栈持续负载下量测；本用例是同步 50 VU 突发，对 M06 只保留防止病态
 * 回归的宽松上界，不把测试客户端的同步启动当成持续负载。首字 p95 同样由 PERF-M06-001 覆盖，本用例不打开
 * Worker 流。
 */
class QuotaMixedWorkloadApiTest extends WorkflowRunTestSupport {

    /** 混合负载总 VU 数。 */
    private static final int TOTAL_VUS = 50;

    /** J1 VU 数。 */
    private static final int J1_VUS = 39;

    /** M06 VU 数。 */
    private static final int M06_VUS = 10;

    /** 每个 VU 的迭代次数。 */
    private static final int ITERATIONS = 1;

    /** 同键并发数。 */
    private static final int SAME_KEY_CONCURRENCY = 10;

    /** J1 提交准入 p95 预算（毫秒）。 */
    private static final long J1_P95_BUDGET_MS = 3000L;

    /** M06 同步突发病态回归上界（毫秒）；合同预算由 PERF-M06-001 持续负载验证。 */
    private static final long M06_P95_SANITY_BOUND_MS = 3000L;

    /** 提交服务；直接驱动 J1 准入，避免依赖 Worker 首字。 */
    @Autowired
    private ChatSubmissionService chatSubmissionService;

    /**
     * 提高连接池，避免 50 个并发请求在测试客户端排队超时。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void poolProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
    }

    /** 每个用例前恢复执行模式。 */
    @BeforeEach
    void enableEnforcement() {
        setQuotaEnforcement(true);
    }

    /**
     * 同键并发后执行 50 VU 混合负载。
     *
     * @throws Exception 并发执行失败
     */
    @Test
    @DisplayName("IT-QUOTA-107 50 VU 混合突发：计数=成功数、无 5xx、同键只建 1 个对象、J1 p95≤3s")
    void mixedWorkload_keepsCountsExactAndLatencyWithinBudget() throws Exception {
        Fixture fixture = fixture("wfq");
        String workspaceId = personalWorkspace(fixture.member());
        String conversationId = createConversation(fixture.member(), workspaceId);
        String workflowId = publishWorkflow(fixture, "混合配额");
        RicconPrincipal principal = principal(fixture.member(), fixture.tenant());
        // 限额足够大，确保混合阶段的拒绝只来自 M06 并发上限，而不是配额本身耗尽。
        setTenantQuota(fixture.platform(), fixture.tenant(), 1000, 1000);
        setUserQuota(fixture.platform(), fixture.tenant(), fixture.memberId(), 1000, 1000);
        setUserQuota(fixture.platform(), fixture.tenant(), fixture.ownerId(), 1000, 1000);

        // 1) J2 同键 10 并发：1 个任务、1 次配额、其余返回既有对象（201）。
        List<Integer> sameKeyResearch = runConcurrently(
                SAME_KEY_CONCURRENCY,
                index -> postResearchTask(
                                fixture.member(), workspaceId, "同键并发报告", fixture.toolId(), "it-quota-107-j2-same-key")
                        .status());
        assertThat(sameKeyResearch).containsOnly(201);
        assertThat(taskCount(fixture.tenant())).isEqualTo(1);
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);

        // 2) M06 同键 10 并发：1 条运行、1 次配额；未命中并发上限前不得出现 WF-429。
        List<Integer> sameKeyRuns = runConcurrently(
                SAME_KEY_CONCURRENCY,
                index -> createRun(fixture.owner(), workflowId, null, DEFAULT_INPUTS, "it-quota-107-m06-same-key")
                        .status());
        assertThat(sameKeyRuns).containsOnly(201);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(2);

        // 3) 50 VU 混合：39 J1（直接提交准入）+ 1 J2 + 10 M06。
        List<Sample> samples = runMixedLoad(fixture, principal, conversationId, workspaceId, workflowId);
        List<Sample> j1 =
                samples.stream().filter(sample -> "J1".equals(sample.kind())).toList();
        List<Sample> j2 =
                samples.stream().filter(sample -> "J2".equals(sample.kind())).toList();
        List<Sample> m06 =
                samples.stream().filter(sample -> "M06".equals(sample.kind())).toList();

        assertThat(j1).hasSize(J1_VUS * ITERATIONS);
        assertThat(j2).hasSize(ITERATIONS);
        assertThat(m06).hasSize(M06_VUS * ITERATIONS);
        assertThat(j1).allMatch(Sample::success);
        assertThat(j2).allMatch(sample -> sample.status() == 201);
        assertThat(m06).allMatch(sample -> sample.status() == 201 || sample.status() == 429);
        assertThat(samples).noneMatch(sample -> sample.status() >= 500);

        long j1Success = j1.size();
        long j2Success = j2.stream().filter(sample -> sample.status() == 201).count();
        long m06Success = m06.stream().filter(sample -> sample.status() == 201).count();
        // 同键阶段分别由 member（J2 1 次）与 owner（M06 1 次）消耗；M06 单流程上限 5，本阶段最多再成功 4 个。
        long expectedUsed = 2 + j1Success + j2Success + m06Success;
        long expectedMember = 1 + j1Success + j2Success;
        long expectedOwner = 1 + m06Success;
        assertThat(m06Success).isEqualTo(4);
        assertThat(expectedUsed).isEqualTo(2 + J1_VUS * ITERATIONS + ITERATIONS + m06Success);

        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(expectedUsed);
        assertThat(quotaUsed(fixture.tenant(), "TENANT", fixture.tenant(), "MONTHLY"))
                .isEqualTo(expectedUsed);
        assertThat(quotaUsed(fixture.tenant(), "USER", fixture.memberId().toString(), "DAILY"))
                .isEqualTo(expectedMember);
        assertThat(quotaUsed(fixture.tenant(), "USER", fixture.memberId().toString(), "MONTHLY"))
                .isEqualTo(expectedMember);
        assertThat(quotaUsed(fixture.tenant(), "USER", fixture.ownerId().toString(), "DAILY"))
                .isEqualTo(expectedOwner);
        assertThat(quotaUsed(fixture.tenant(), "USER", fixture.ownerId().toString(), "MONTHLY"))
                .isEqualTo(expectedOwner);
        assertThat(agentRunCount(fixture.tenant())).isEqualTo(1 + j1Success + j2Success);
        assertThat(runCount(fixture.tenant())).isEqualTo(1 + m06Success);
        assertThat(taskCount(fixture.tenant())).isEqualTo(1 + j2Success);

        long j1P95 = percentile95(j1.stream().map(Sample::elapsedMs).toList());
        long m06P95 = percentile95(m06.stream().map(Sample::elapsedMs).toList());
        assertThat(j1P95).as("J1 提交准入 p95（毫秒）").isLessThanOrEqualTo(J1_P95_BUDGET_MS);
        // 同步 50 VU 突发下的病态回归上界；合同预算（≤500ms）由 PERF-M06-001 持续负载量测。
        assertThat(m06P95).as("M06 运行准入 p95（毫秒，同步突发病态回归上界）").isLessThanOrEqualTo(M06_P95_SANITY_BOUND_MS);
    }

    /**
     * 执行混合负载并返回每个请求的样本。
     *
     * @param fixture 租户夹具
     * @param principal J1 主体
     * @param conversationId 会话 ID
     * @param workspaceId 工作空间 ID
     * @param workflowId 流程 ID
     * @return 全部样本
     * @throws Exception 并发执行失败
     */
    private List<Sample> runMixedLoad(
            Fixture fixture, RicconPrincipal principal, String conversationId, String workspaceId, String workflowId)
            throws Exception {
        TenantId tenantId = TenantId.of(fixture.tenant());
        ExecutorService executor = Executors.newFixedThreadPool(TOTAL_VUS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Sample>>> futures = new ArrayList<>(TOTAL_VUS);
        try {
            for (int index = 0; index < J1_VUS; index++) {
                int vu = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    List<Sample> samples = new ArrayList<>(ITERATIONS);
                    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                        int iterationIndex = iteration;
                        String clientMessageId = "it107-j1-" + vu + "-" + iterationIndex + "-" + UUID.randomUUID();
                        long startedAt = System.nanoTime();
                        boolean success = true;
                        try {
                            ChatSubmission submission = TenantContext.callWithTenant(
                                    tenantId,
                                    () -> chatSubmissionService.prepare(
                                            principal,
                                            UUID.fromString(conversationId),
                                            new SendMessageRequest(
                                                    "混合负载 J1 主题 " + vu + "-" + iterationIndex,
                                                    clientMessageId,
                                                    List.of()),
                                            "trace-it107-j1",
                                            "req-it107-j1"));
                            success = submission.runId() != null;
                        } catch (RuntimeException exception) {
                            success = false;
                        }
                        samples.add(new Sample("J1", success, success ? 201 : 500, elapsedMillis(startedAt)));
                    }
                    return samples;
                }));
            }
            futures.add(executor.submit(() -> {
                start.await();
                List<Sample> samples = new ArrayList<>(ITERATIONS);
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    long startedAt = System.nanoTime();
                    ApiTestClient.ApiResponse response = postResearchTask(
                            fixture.member(),
                            workspaceId,
                            "混合负载 J2 主题 " + iteration,
                            fixture.toolId(),
                            "it107-j2-" + iteration + "-" + UUID.randomUUID());
                    samples.add(
                            new Sample("J2", response.status() == 201, response.status(), elapsedMillis(startedAt)));
                }
                return samples;
            }));
            for (int index = 0; index < M06_VUS; index++) {
                int vu = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    List<Sample> samples = new ArrayList<>(ITERATIONS);
                    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                        long startedAt = System.nanoTime();
                        ApiTestClient.ApiResponse response = createRun(
                                fixture.owner(),
                                workflowId,
                                null,
                                DEFAULT_INPUTS,
                                "it107-m06-" + vu + "-" + iteration + "-" + UUID.randomUUID());
                        samples.add(new Sample(
                                "M06", response.status() == 201, response.status(), elapsedMillis(startedAt)));
                    }
                    return samples;
                }));
            }
            start.countDown();
            List<Sample> samples = new ArrayList<>(J1_VUS * ITERATIONS + ITERATIONS + M06_VUS * ITERATIONS);
            for (Future<List<Sample>> future : futures) {
                samples.addAll(future.get(180, TimeUnit.SECONDS));
            }
            return samples;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 并发执行固定数量的任务并返回状态码。
     *
     * @param concurrency 并发数
     * @param task 任务
     * @return 状态码列表
     * @throws Exception 并发执行失败
     */
    private List<Integer> runConcurrently(int concurrency, ThrowingIntTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(concurrency);
        try {
            for (int index = 0; index < concurrency; index++) {
                int ordinal = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.run(ordinal);
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

    /**
     * 读取当前会话的个人空间；首次列表调用会自动创建。
     *
     * @param session 登录会话
     * @return 个人空间 ID
     */
    private String personalWorkspace(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=PERSONAL", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("items")).isNotEmpty();
        return body.path("items").get(0).path("id").asText();
    }

    /**
     * 创建会话并断言 201。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @return 会话 ID
     */
    private String createConversation(LoginSession session, String workspaceId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                "{}",
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 创建并发布 ONLY_SELF 流程。
     *
     * @param fixture 租户夹具
     * @param name 流程名
     * @return 流程 ID
     */
    private String publishWorkflow(Fixture fixture, String name) {
        String workflowId = createDraft(fixture.owner(), name).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-" + UUID.randomUUID());
        return workflowId;
    }

    /**
     * 提交报告任务并返回原始响应。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param topic 主题
     * @param toolId MCP 工具
     * @param idempotencyKey 幂等键
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse postResearchTask(
            LoginSession session, String workspaceId, String topic, UUID toolId, String idempotencyKey) {
        return client.post(
                "/api/v1/workspaces/" + workspaceId + "/research-tasks",
                client.jsonBody("topic", topic, "mcpToolId", toolId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    /**
     * 从登录响应构造主体。
     *
     * @param session 登录会话
     * @param tenant 租户代码
     * @return 会话主体
     */
    private RicconPrincipal principal(LoginSession session, String tenant) {
        JsonNode user = session.body().path("user");
        return new RicconPrincipal(
                UUID.fromString(user.path("id").asText()),
                tenant,
                user.path("username").asText(),
                user.path("displayName").asText());
    }

    /**
     * 统计报告任务数。
     *
     * @param tenant 租户
     * @return 行数
     */
    private long taskCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM research_task WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 统计 agent_run 数（J1/J2）。
     *
     * @param tenant 租户
     * @return 行数
     */
    private long agentRunCount(String tenant) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
    }

    /**
     * 计算纳秒耗时（毫秒）。
     *
     * @param startedAt 开始时间（{@link System#nanoTime()}）
     * @return 毫秒
     */
    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * 计算 p95（最近秩法）。
     *
     * @param samples 耗时样本
     * @return p95 毫秒
     */
    private static long percentile95(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95d) - 1;
        return sorted.get(Math.max(0, index));
    }

    /**
     * 单个请求样本。
     *
     * @param kind J1/J2/M06
     * @param success 是否成功
     * @param status HTTP 状态（J1 直接调用时成功记 201，失败记 500）
     * @param elapsedMs 耗时毫秒
     */
    private record Sample(String kind, boolean success, int status, long elapsedMs) {}

    /** 可抛异常的并发任务。 */
    @FunctionalInterface
    private interface ThrowingIntTask {

        /**
         * 执行任务。
         *
         * @param index 序号
         * @return 状态码
         * @throws Exception 执行失败
         */
        int run(int index) throws Exception;
    }
}
