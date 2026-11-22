package com.riccon.api.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.admin.cost.repository.PlatformCostQueryRepository;
import com.riccon.api.admin.cost.repository.PlatformCostRepository;
import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.api.cost.repository.CostQueryRepository;
import com.riccon.api.cost.repository.CostRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * F-002：真实 MySQL 下的 3 秒查询保护。
 *
 * <p>生产 {@code CostJdbc}/{@code PlatformCostJdbc} 的 SQL 带 {@code MAX_EXECUTION_TIME(3000)} 提示。
 * 测试数据量下真实聚合 SQL 无法稳定超过 3 秒，因此这里用真实 MySQL 连接执行带同一提示的受控慢查询，
 * 分别证明：
 *
 * <ol>
 *   <li>MySQL 8.4 在约 3 秒以错误 3024（{@code ER_QUERY_TIMEOUT}）中止查询；
 *   <li>JDBC 将其翻译为 {@link DataAccessException}，真实服务与 HTTP 层映射为 503
 *       {@code RICCON-COST-503}，响应不含部分结果。
 * </ol>
 *
 * <p><b>覆盖边界</b>：本类证明“MySQL 8.4 提示生效 + Spring 异常翻译 + 服务/HTTP 映射”，
 * 不冒充生产聚合 SQL 本身的执行耗时；生产 SQL 是否超时取决于真实数据量。
 */
@Import(CostQueryTimeoutIntegrationTest.SlowCostRepositoryConfiguration.class)
class CostQueryTimeoutIntegrationTest extends CostApiTestSupport {

    /**
     * 受控慢查询：4 行派生表每行 {@code SLEEP(1)}，同一 {@code MAX_EXECUTION_TIME(3000)} 提示应在第 3 秒中止。
     *
     * <p>若提示未生效，最坏 4 秒自然结束（不会无限运行），用例会因“未抛出超时异常”失败而不是挂起。
     */
    private static final String SLOW_QUERY = """
            SELECT /*+ MAX_EXECUTION_TIME(3000) */ SLEEP(1)
              FROM (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) probe
            """;

    /**
     * MySQL 8.4 在 3 秒中止带提示的慢查询，并落到 Spring {@link DataAccessException}。
     */
    @Test
    @DisplayName("F-002：MySQL 8.4 MAX_EXECUTION_TIME(3000) 约 3 秒中止慢查询")
    @Timeout(30)
    void maxExecutionTimeHint_abortsSlowQueryAroundThreeSeconds() {
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> jdbcClient.sql(SLOW_QUERY).query(Long.class).list())
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> assertThat(mysqlErrorCode(exception))
                                .as("MySQL 超时错误码 3024（ER_QUERY_TIMEOUT）")
                                .isEqualTo(3024));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed).as("应在 3 秒附近中止，而不是立即失败或完整执行慢查询").isBetween(Duration.ofMillis(2500), Duration.ofSeconds(15));
    }

    /**
     * 租户报告在真实 MySQL 超时后返回 503，且不返回部分结果。
     */
    @Test
    @DisplayName("F-002：租户成本查询真实超时返回 503 RICCON-COST-503")
    @Timeout(30)
    void tenantReport_realTimeoutReturns503WithoutPartialResult() {
        Fixture fixture = fixture();
        long startedAt = System.nanoTime();
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY",
                fixture.admin().sessionCookie(),
                Map.of());
        assertUnavailable(response, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /**
     * 平台报告在真实 MySQL 超时后返回 503，且不返回部分结果。
     */
    @Test
    @DisplayName("F-002：平台成本查询真实超时返回 503 RICCON-COST-503")
    @Timeout(30)
    void platformReport_realTimeoutReturns503WithoutPartialResult() {
        Fixture fixture = fixture();
        long startedAt = System.nanoTime();
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/admin/costs?from=2026-11-01&to=2026-11-30&groupBy=TENANT",
                fixture.platform().sessionCookie(),
                Map.of());
        assertUnavailable(response, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /**
     * 断言真实超时响应为 503 + {@code RICCON-COST-503}，且不含聚合结果或 SQL 细节。
     *
     * @param response HTTP 响应
     * @param elapsed 请求耗时
     */
    private void assertUnavailable(ApiTestClient.ApiResponse response, Duration elapsed) {
        assertThat(response.status()).as(response.body()).isEqualTo(503);
        JsonNode problem = client.json(response);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-COST-503");
        assertThat(problem.has("items")).isFalse();
        assertThat(problem.has("totals")).isFalse();
        assertThat(response.body())
                .doesNotContain("MAX_EXECUTION_TIME")
                .doesNotContain("SLEEP")
                .doesNotContain("probe");
        assertThat(elapsed)
                .as("HTTP 请求应等待真实 MySQL 超时，而不是被立即拒绝")
                .isBetween(Duration.ofMillis(2500), Duration.ofSeconds(9));
    }

    /**
     * 从异常链中读取 MySQL 错误码。
     *
     * @param throwable 异常
     * @return 首个非零 SQL 错误码；不存在时返回 0
     */
    private static int mysqlErrorCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() != 0) {
                return sqlException.getErrorCode();
            }
            current = current.getCause();
        }
        return 0;
    }

    /**
     * 执行受控慢查询；预期在约 3 秒抛出真实 MySQL 超时。
     *
     * @param jdbcClient 真实数据库入口
     */
    private static void runSlowQuery(JdbcClient jdbcClient) {
        jdbcClient.sql(SLOW_QUERY).query(Long.class).list();
    }

    /**
     * 测试专用装配：把两类成本合计替换为真实 MySQL 慢查询。
     *
     * <p>只覆盖 {@code totals}（服务层第一个查询），其余方法委托生产仓储；不用 mock 伪造异常，
     * 超时来自真实 MySQL 与同一优化器提示。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SlowCostRepositoryConfiguration {

        /**
         * 覆盖租户成本仓储。
         *
         * @param jdbcClient 真实数据库入口
         * @param production 生产仓储
         * @return 慢查询仓储
         */
        @Bean
        @Primary
        CostQueryRepository slowCostQueryRepository(JdbcClient jdbcClient, CostRepository production) {
            return new SlowTenantCostRepository(jdbcClient, production);
        }

        /**
         * 覆盖平台成本仓储。
         *
         * @param jdbcClient 真实数据库入口
         * @param production 生产仓储
         * @return 慢查询仓储
         */
        @Bean
        @Primary
        PlatformCostQueryRepository slowPlatformCostQueryRepository(
                JdbcClient jdbcClient, PlatformCostRepository production) {
            return new SlowPlatformCostRepository(jdbcClient, production);
        }
    }

    /** 只让 {@code totals} 触发真实慢查询的租户仓储。 */
    private static final class SlowTenantCostRepository implements CostQueryRepository {

        /** 真实数据库入口。 */
        private final JdbcClient jdbcClient;

        /** 生产仓储。 */
        private final CostQueryRepository delegate;

        /**
         * 创建测试仓储。
         *
         * @param jdbcClient 真实数据库入口
         * @param delegate 生产仓储
         */
        SlowTenantCostRepository(JdbcClient jdbcClient, CostQueryRepository delegate) {
            this.jdbcClient = jdbcClient;
            this.delegate = delegate;
        }

        /**
         * 执行受控慢查询，触发真实 MySQL 超时。
         *
         * @param tenantId 当前租户
         * @param range 时间范围
         * @param currency 部署币种
         * @return 不会正常返回
         */
        @Override
        public CostTotalsRow totals(TenantId tenantId, CostRange range, String currency) {
            runSlowQuery(jdbcClient);
            throw new IllegalStateException("controlled slow query completed without timeout");
        }

        /** {@inheritDoc} */
        @Override
        public List<CostBucketRow> buckets(
                TenantId tenantId, CostRange range, TenantCostGroupBy groupBy, String currency, int limit, int offset) {
            return delegate.buckets(tenantId, range, groupBy, currency, limit, offset);
        }

        /** {@inheritDoc} */
        @Override
        public long bucketCount(TenantId tenantId, CostRange range, TenantCostGroupBy groupBy) {
            return delegate.bucketCount(tenantId, range, groupBy);
        }

        /** {@inheritDoc} */
        @Override
        public List<CostInvocationRow> invocations(
                TenantId tenantId, CostRange range, UUID userId, UUID modelId, int limit, int offset) {
            return delegate.invocations(tenantId, range, userId, modelId, limit, offset);
        }

        /** {@inheritDoc} */
        @Override
        public long invocationCount(TenantId tenantId, CostRange range, UUID userId, UUID modelId) {
            return delegate.invocationCount(tenantId, range, userId, modelId);
        }

        /** {@inheritDoc} */
        @Override
        public boolean userExists(TenantId tenantId, UUID userId) {
            return delegate.userExists(tenantId, userId);
        }
    }

    /** 只让 {@code totals} 触发真实慢查询的平台仓储。 */
    private static final class SlowPlatformCostRepository implements PlatformCostQueryRepository {

        /** 真实数据库入口。 */
        private final JdbcClient jdbcClient;

        /** 生产仓储。 */
        private final PlatformCostQueryRepository delegate;

        /**
         * 创建测试仓储。
         *
         * @param jdbcClient 真实数据库入口
         * @param delegate 生产仓储
         */
        SlowPlatformCostRepository(JdbcClient jdbcClient, PlatformCostQueryRepository delegate) {
            this.jdbcClient = jdbcClient;
            this.delegate = delegate;
        }

        /**
         * 执行受控慢查询，触发真实 MySQL 超时。
         *
         * @param range 时间范围
         * @param currency 部署币种
         * @param tenantId 可选租户过滤
         * @return 不会正常返回
         */
        @Override
        public CostTotalsRow totals(CostRange range, String currency, String tenantId) {
            runSlowQuery(jdbcClient);
            throw new IllegalStateException("controlled slow query completed without timeout");
        }

        /** {@inheritDoc} */
        @Override
        public List<CostBucketRow> buckets(
                CostRange range, PlatformCostGroupBy groupBy, String currency, String tenantId, int limit, int offset) {
            return delegate.buckets(range, groupBy, currency, tenantId, limit, offset);
        }

        /** {@inheritDoc} */
        @Override
        public long bucketCount(CostRange range, PlatformCostGroupBy groupBy, String tenantId) {
            return delegate.bucketCount(range, groupBy, tenantId);
        }
    }
}
