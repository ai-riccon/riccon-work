package com.riccon.api.quota;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT-QUOTA-102：剩余 1 次时 50 并发准入。
 *
 * <p>真实 MySQL、真实 HTTP、真实事务。断言恰好 1 个 201、49 个 429、0 个 5xx，且四个窗口的
 * {@code used_count} 与成功 run 数严格相等；出现死锁或锁等待即视为缺陷。
 */
class QuotaConcurrencyApiTest extends QuotaApiTestSupport {

    /** 并发数。 */
    private static final int CONCURRENCY = 50;

    /**
     * 提高连接池，避免 50 个请求在本地测试客户端超时；生产连接池配置由 RUNBOOK 约束。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void poolProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    /** 每个用例前恢复执行模式。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * 50 并发只剩 1 次时只有 1 个成功，其余 429，无 500。
     *
     * @throws Exception 并发执行失败
     */
    @Test
    @DisplayName("IT-QUOTA-102 50 并发恰好 1 成功 49 个 429")
    void fiftyConcurrentRequests_onlyOneSucceeds() throws Exception {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 1, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 10);
        UUID toolId = createMcpTool(fixture.tenant());

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiTestClient.ApiResponse>> futures = new ArrayList<>(CONCURRENCY);
        try {
            for (int index = 0; index < CONCURRENCY; index++) {
                int ordinal = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return postResearchTask(
                            fixture.member(),
                            fixture.memberWorkspace(),
                            "并发热点主题" + ordinal,
                            toolId,
                            "quota-concurrent-" + ordinal);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(CONCURRENCY);
            for (Future<ApiTestClient.ApiResponse> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS).status());
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 429).hasSize(CONCURRENCY - 1);
            assertThat(statuses).filteredOn(status -> status >= 500).isEmpty();
        } finally {
            executor.shutdownNow();
        }

        assertThat(runCount(fixture.tenant())).isEqualTo(1);
        assertThat(taskCount(fixture.tenant())).isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "MONTHLY"))
                .isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(1);
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "MONTHLY"))
                .isEqualTo(1);
    }
}
