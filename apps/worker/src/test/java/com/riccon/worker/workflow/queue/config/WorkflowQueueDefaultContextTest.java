package com.riccon.worker.workflow.queue.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.WorkerApplication;
import com.riccon.worker.persistence.SchemaVersionChecker;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.service.OutboxLaneScheduler;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestSchema;
import com.riccon.worker.support.TestServiceToken;
import com.riccon.worker.workflow.queue.domain.WorkflowTaskScheduler;
import com.riccon.worker.workflow.queue.service.WorkflowRunScheduler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 默认配置 Worker context 启动测试（F-001 / REV-TASK-0224-001）。
 *
 * <p>与 {@code WorkerIntegrationTestBase} 明确关闭 outbox 调度不同，本测试不设置
 * {@code riccon.worker.outbox.scheduling-enabled=false}，验证默认配置下：
 *
 * <ul>
 *   <li>{@link OutboxLaneScheduler} 需要且能拿到名为 {@code taskScheduler} 的
 *       {@link ThreadPoolTaskScheduler}（Boot 自动配置因 workflow 的
 *       {@link ScheduledExecutorService} Bean 回退，必须显式提供）；
 *   <li>workflow 槽位池、workflow 心跳池与共享 outbox 调度池是三个不同线程池，大小分别取
 *       {@code spring.task.scheduling.pool.size}、{@code RICCON_WORKFLOW_SCHEDULER_POOL_SIZE}、
 *       {@code RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY}；
 *   <li>{@code @Scheduled}/outbox 的共享池不会被 workflow 槽位执行占满。
 * </ul>
 *
 * <p>只启动 WorkerApplication 默认上下文；数据库使用独立 MySQL 容器并执行 V1–V15 权威迁移，
 * 不启动 OpenSearch/RustFS（Worker 启动阶段不连接）。测试类结束后主动关闭上下文，取消 outbox
 * 固定延迟任务，避免影响同一 JVM 的后续测试。
 */
@SpringBootTest(classes = WorkerApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowQueueDefaultContextTest {

    /** 测试主密钥 pepper；与 WorkerIntegrationTestBase 一致的已知测试占位。 */
    private static final String TEST_FINGERPRINT_PEPPER = "cmljY29uLXRlc3QtZmluZ2VycHJpbnQtcGVwcGVyMzI=";

    /** DEMO 端点允许列表；只用于启动校验，不发起调用。 */
    private static final String DEMO_ENDPOINTS =
            "[{\"code\":\"demo-local\",\"baseUrl\":\"http://127.0.0.1:19090/v1\",\"environmentLabel\":\"DEMO_ONLY\"}]";

    /** 独立 MySQL 容器；执行 Worker 期望的 V1–V15 迁移与 Flyway 版本记录。 */
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
        TestSchema.applyMigrations(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        TestSchema.createFlywayHistory(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
    }

    /** Spring 上下文；用于断言 TaskScheduler Bean 唯一性。 */
    @Autowired
    private ApplicationContext applicationContext;

    /** Spring 共享调度池；outbox 与 @Scheduled 使用。 */
    @Autowired
    @Qualifier("taskScheduler")
    private TaskScheduler taskScheduler;

    /** workflow 槽位池。 */
    @Autowired
    @Qualifier("workflowRunSlotExecutor")
    private ScheduledExecutorService slotExecutor;

    /** workflow 心跳池。 */
    @Autowired
    @Qualifier("workflowHeartbeatExecutor")
    private ScheduledExecutorService heartbeatExecutor;

    /** workflow 槽位调度端口。 */
    @Autowired
    @Qualifier("workflowRunTaskScheduler")
    private WorkflowTaskScheduler runTaskScheduler;

    /** workflow 心跳调度端口。 */
    @Autowired
    @Qualifier("workflowHeartbeatTaskScheduler")
    private WorkflowTaskScheduler heartbeatTaskScheduler;

    /** 默认配置仍有 outbox 调度器 Bean。 */
    @Autowired
    private OutboxLaneScheduler outboxLaneScheduler;

    /** outbox 通道配置，证明默认通道启用。 */
    @Autowired
    private OutboxLaneProperties outboxLaneProperties;

    /** workflow 调度器 Bean；无执行器时不注册槽位，但必须可装配。 */
    @Autowired
    private WorkflowRunScheduler workflowRunScheduler;

    /** workflow 配置。 */
    @Autowired
    private WorkflowQueueProperties workflowQueueProperties;

    /** 运行环境。 */
    @Autowired
    private Environment environment;

    /**
     * 注入默认上下文所需的基础设施属性。
     *
     * <p>只提供数据库、测试主密钥/服务令牌与 DEMO 端点；不关闭 outbox 调度，保持与生产默认
     * 一致的 {@code riccon.worker.outbox.scheduling-enabled=true}。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("riccon.worker.crypto.master-key", () -> TestCredentials.MASTER_KEY_BASE64);
        registry.add("riccon.worker.crypto.master-key-id", () -> TestCredentials.MASTER_KEY_ID);
        registry.add("riccon.worker.crypto.canary", () -> "test-canary");
        registry.add("riccon.worker.crypto.fingerprint-pepper", () -> TEST_FINGERPRINT_PEPPER);
        registry.add("riccon.worker.service-token.secret", () -> TestServiceToken.SECRET);
        registry.add("riccon.model.deployment-mode", () -> "DEMO");
        registry.add("riccon.model.endpoints", () -> DEMO_ENDPOINTS);
        registry.add("riccon.model.allow-legacy-endpoints", () -> "true");
        registry.add("riccon.model.allow-http", () -> "true");
        registry.add("riccon.worker.model.allow-insecure-http", () -> "true");
    }

    /** 默认配置启动：outbox 与 workflow 三个池同时装配且互不共用。 */
    @Test
    @DisplayName("默认配置同时装配 outbox TaskScheduler 与独立 workflow 池")
    void defaultContext_wiresOutboxAndWorkflowPoolsIndependently() {
        assertThat(outboxLaneScheduler).isNotNull();
        assertThat(workflowRunScheduler).isNotNull();
        assertThat(outboxLaneProperties.enabledLanes()).isNotEmpty();
        assertThat(environment.getProperty("riccon.worker.outbox.scheduling-enabled", Boolean.class, true))
                .isTrue();
        assertThat(environment.getProperty("riccon.worker.outbox.legacy-all-lane-scheduling", Boolean.class, false))
                .isFalse();

        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler shared = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(shared.getScheduledThreadPoolExecutor()).isNotSameAs(slotExecutor);
        assertThat(shared.getScheduledThreadPoolExecutor()).isNotSameAs(heartbeatExecutor);
        assertThat(slotExecutor).isNotSameAs(heartbeatExecutor);
        assertThat(shared.getThreadNamePrefix())
                .isEqualTo(environment.getProperty("spring.task.scheduling.thread-name-prefix", "riccon-sched-"));
        assertThat(shared.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isEqualTo(environment.getProperty("spring.task.scheduling.pool.size", Integer.class, 1));
        assertThat(((ScheduledThreadPoolExecutor) slotExecutor).getCorePoolSize())
                .isEqualTo(workflowQueueProperties.schedulerPoolSize());
        assertThat(((ScheduledThreadPoolExecutor) heartbeatExecutor).getCorePoolSize())
                .isEqualTo(workflowQueueProperties.heartbeatConcurrency());
        assertThat(runTaskScheduler).isNotSameAs(heartbeatTaskScheduler);
        // 直接核对装配来源：outbox 用共享 taskScheduler，workflow 槽位用独立调度端口。
        assertThat(ReflectionTestUtils.getField(outboxLaneScheduler, "taskScheduler"))
                .isSameAs(taskScheduler);
        assertThat(ReflectionTestUtils.getField(workflowRunScheduler, "taskScheduler"))
                .isSameAs(runTaskScheduler);
        assertThat(applicationContext.getBeansOfType(TaskScheduler.class)).containsOnlyKeys("taskScheduler");
    }
}
