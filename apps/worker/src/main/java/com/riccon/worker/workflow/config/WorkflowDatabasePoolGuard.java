package com.riccon.worker.workflow.config;

import com.riccon.worker.workflow.queue.config.WorkflowQueueProperties;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.stereotype.Component;

/**
 * Worker Hikari 容量规则观测（ADR-0024 §4.3/§10.2，OPS-M06-022）。
 *
 * <p>合同部署键 {@code RICCON_DB_POOL_SIZE >= RICCON_WORKFLOW_WORKER_CONCURRENCY + 4} 由 T06a 的
 * {@code WorkflowQueueStartupValidator} fail-closed。这里额外读取 Hikari 实际配置并暴露
 * {@code riccon_workflow_db_pool_shortfall}（0 表示满足，正数表示缺多少连接），用于发现“部署键正确、
 * 但有人单独覆盖 {@code spring.datasource.hikari.maximum-pool-size}”的漂移。该指标是 ADR §10.2
 * {@code hikaricp_connections_pending}/{@code hikaricp_connections_timeout_total} 的补充信号。
 *
 * <p>不在启动期 fail-closed：测试栈与部分客户会显式把 Hikari 池调小以控制连接数，部署键才是合同口径；
 * 实际池偏小只记录 WARN，由 RUNBOOK 的 preflight 与告警处置。执行槽位只在节点 DB 操作时取连接，
 * 不长期占用。
 */
@Component
public class WorkflowDatabasePoolGuard implements InitializingBean {

    /** Hikari 实际池低于“并发 + 4”时的告警码；RUNBOOK 按此检索。 */
    public static final String SHORTFALL_WARN_CODE = "RICCON-WORKFLOW-022";

    /** Hikari 容量缺口指标名；Prometheus 暴露为同名。 */
    public static final String SHORTFALL_METRIC = "riccon_workflow_db_pool_shortfall";

    /** 心跳/outbox/健康检查预留连接数。 */
    private static final int RESERVED_CONNECTIONS = 4;

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowDatabasePoolGuard.class);

    /** 数据源；可能是代理或包装。 */
    private final DataSource dataSource;

    /** 队列配置，用于计算所需连接。 */
    private final WorkflowQueueProperties properties;

    /** 指标注册表。 */
    private final MeterRegistry meterRegistry;

    /**
     * 创建容量观测组件。
     *
     * @param dataSource 数据源
     * @param properties 队列配置
     * @param meterRegistry 指标注册表
     */
    public WorkflowDatabasePoolGuard(
            DataSource dataSource, WorkflowQueueProperties properties, MeterRegistry meterRegistry) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /** 读取 Hikari 实际池大小，注册缺口 Gauge 并在不足时记录 WARN。 */
    @Override
    public void afterPropertiesSet() {
        HikariDataSource hikari =
                DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class, HikariDataSource.class);
        if (hikari == null) {
            // 非 Hikari 数据源（例如测试切片）不注册该指标；hikaricp_* 指标也只在 Hikari 存在时可用。
            LOG.info("workflow.db.pool.guard_skipped reason=NOT_HIKARI dataSource={}", dataSource.getClass().getName());
            return;
        }
        int required = properties.workerConcurrency() + RESERVED_CONNECTIONS;
        Gauge.builder(
                        SHORTFALL_METRIC,
                        hikari,
                        config -> Math.max(0, required - config.getMaximumPoolSize()))
                .description("Configured Hikari pool connections below workflow worker concurrency + 4")
                .register(meterRegistry);
        if (hikari.getMaximumPoolSize() < required) {
            LOG.warn(
                    "{} workflow.db.pool.below_required actual={} required={} workerConcurrency={}"
                            + " (RICCON_DB_POOL_SIZE is the authoritative deployment key)",
                    SHORTFALL_WARN_CODE,
                    hikari.getMaximumPoolSize(),
                    required,
                    properties.workerConcurrency());
        }
    }
}
