package com.riccon.api.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.config.WorkflowProperties;
import com.riccon.api.workflow.runtime.config.WorkflowRuntimeStartupValidator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * API 编排运行限制启动校验（OPS-M06-002/OPS-M06-011/OPS-M06-022）。
 *
 * <p>逐项构造非法部署键，断言错误信息包含可操作的环境变量键；默认值必须全部通过。连接池与包大小使用
 * 部署声明值校验，不反射 Hikari 实际池（测试栈会显式覆盖 Hikari 键）。
 */
class WorkflowRuntimeStartupValidatorTest {

    /** 默认部署配置；所有字段与 ADR-0024 §10.1 一致。 */
    private static final WorkflowProperties DEFAULTS = new WorkflowProperties(
            true,
            "",
            "it",
            50,
            200,
            300,
            120,
            60,
            30,
            10,
            5,
            5_242_880L,
            10,
            1,
            1_000L,
            5_000L,
            10,
            10,
            30,
            90,
            true,
            120,
            180);

    /** 默认 16 MiB 包大小。 */
    private static final long DEFAULT_PACKET = 16_777_216L;

    /** 默认连接池 16。 */
    private static final int DEFAULT_POOL = 16;

    /** 默认配置必须通过，避免把合法部署误判为不可启动。 */
    @Test
    @DisplayName("默认部署配置通过启动校验")
    void defaults_pass() {
        new WorkflowRuntimeStartupValidator(DEFAULTS, DEFAULT_POOL, DEFAULT_PACKET).afterPropertiesSet();
    }

    /** 非法运行限制给出环境变量键。 */
    static Stream<Arguments> invalidProperties() {
        return Stream.of(
                Arguments.of(
                        copy(
                                DEFAULTS,
                                0,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_MAX_NODES"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                0,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_MAX_STEPS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                0,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                0,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                0,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                0,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                0,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_TENANT_CONCURRENCY"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                0,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_FLOW_CONCURRENCY"),
                Arguments.of(
                        copy(
                                DEFAULTS, 50, 200, 300, 120, 60, 30, 10, 5, 0L, 10, 1, 1_000L, 5_000L, 10, 10, 30, 90,
                                120, 180),
                        "RICCON_WORKFLOW_RESULT_MAX_BYTES"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                0),
                        "RICCON_WORKFLOW_RETENTION_DAYS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                0,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_WORKER_CONCURRENCY"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                2,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_CLAIM_BATCH"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                9,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_SCHEDULER_POOL_SIZE"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                9,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                31,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                0L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                500L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_CLAIM_IDLE_BACKOFF_MAX_MS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                30,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                -1,
                                180),
                        "RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS"),
                Arguments.of(
                        copy(
                                DEFAULTS,
                                50,
                                200,
                                300,
                                120,
                                60,
                                Integer.MAX_VALUE / 60 + 1,
                                10,
                                5,
                                5_242_880L,
                                10,
                                1,
                                1_000L,
                                5_000L,
                                10,
                                10,
                                30,
                                90,
                                120,
                                180),
                        "RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES"));
    }

    /** 每个非法运行限制都以可操作键失败。 */
    @ParameterizedTest
    @MethodSource("invalidProperties")
    @DisplayName("非法运行限制阻断启动并给出键名")
    void invalidProperties_failWithKey(WorkflowProperties properties, String expectedKey) {
        assertThatThrownBy(() -> new WorkflowRuntimeStartupValidator(properties, DEFAULT_POOL, DEFAULT_PACKET)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedKey);
    }

    /** 关闭原因超过契约 maxLength 200 时阻断启动。 */
    @Test
    @DisplayName("关闭原因超过 200 字符阻断启动")
    void disabledReasonTooLong_fails() {
        WorkflowProperties properties = copy(
                DEFAULTS,
                50,
                200,
                300,
                120,
                60,
                30,
                10,
                5,
                5_242_880L,
                10,
                1,
                1_000L,
                5_000L,
                10,
                10,
                30,
                90,
                120,
                180);
        WorkflowProperties withLongReason = new WorkflowProperties(
                properties.enabled(),
                "x".repeat(201),
                properties.instanceId(),
                properties.maxNodes(),
                properties.maxSteps(),
                properties.modelTimeoutSeconds(),
                properties.retrievalTimeoutSeconds(),
                properties.mcpTimeoutSeconds(),
                properties.runTimeoutMinutes(),
                properties.tenantConcurrency(),
                properties.flowConcurrency(),
                properties.resultMaxBytes(),
                properties.workerConcurrency(),
                properties.claimBatch(),
                properties.claimPollIntervalMs(),
                properties.claimIdleBackoffMaxMs(),
                properties.schedulerPoolSize(),
                properties.heartbeatConcurrency(),
                properties.heartbeatIntervalSeconds(),
                properties.leaseSeconds(),
                properties.claimEnabled(),
                properties.shutdownGraceSeconds(),
                properties.retentionDays());
        assertThatThrownBy(() -> new WorkflowRuntimeStartupValidator(withLongReason, DEFAULT_POOL, DEFAULT_PACKET)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_WORKFLOW_DISABLED_REASON");
    }

    /** 连接池低于 worker 并发 + 4 时阻断启动。 */
    @Test
    @DisplayName("连接池小于并发+4阻断启动")
    void databasePoolTooSmall_fails() {
        assertThatThrownBy(() -> new WorkflowRuntimeStartupValidator(DEFAULTS, 13, DEFAULT_PACKET).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_DB_POOL_SIZE");
    }

    /** 包大小低于 8 MiB 时阻断启动。 */
    @Test
    @DisplayName("包大小低于 8 MiB 阻断启动")
    void packetBelowMinimum_fails() {
        assertThatThrownBy(() -> new WorkflowRuntimeStartupValidator(DEFAULTS, DEFAULT_POOL, 4L * 1024L * 1024L)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8 MiB");
    }

    /** 包大小不覆盖结果上限 + 1 MiB 时阻断启动。 */
    @Test
    @DisplayName("包大小未覆盖结果上限加余量阻断启动")
    void packetBelowResultHeadroom_fails() {
        assertThatThrownBy(() ->
                        new WorkflowRuntimeStartupValidator(DEFAULTS, DEFAULT_POOL, 5_242_880L).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_DB_MAX_ALLOWED_PACKET");
    }

    /** 结果上限提高后，默认包大小必须重新调大。 */
    @Test
    @DisplayName("大结果上限要求同步提高包大小")
    void largerResultRequiresLargerPacket() {
        WorkflowProperties largeResult = copy(
                DEFAULTS,
                50,
                200,
                300,
                120,
                60,
                30,
                10,
                5,
                20L * 1024L * 1024L,
                10,
                1,
                1_000L,
                5_000L,
                10,
                10,
                30,
                90,
                120,
                180);
        assertThatThrownBy(() -> new WorkflowRuntimeStartupValidator(largeResult, DEFAULT_POOL, DEFAULT_PACKET)
                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_DB_MAX_ALLOWED_PACKET");
        new WorkflowRuntimeStartupValidator(largeResult, DEFAULT_POOL, 21L * 1024L * 1024L).afterPropertiesSet();
    }

    /**
     * 以默认记录的合并结果构造变体，减少 23 参数重复。
     *
     * @param base 默认配置
     * @param maxNodes 节点上限
     * @param maxSteps 步数上限
     * @param modelTimeout 模型超时
     * @param retrievalTimeout 检索超时
     * @param mcpTimeout MCP 超时
     * @param runTimeoutMinutes 运行超时分钟
     * @param tenantConcurrency 租户并发
     * @param flowConcurrency 流程并发
     * @param resultMaxBytes 结果上限
     * @param workerConcurrency worker 并发
     * @param claimBatch 领取批量
     * @param claimPollIntervalMs 轮询周期
     * @param claimIdleBackoffMaxMs 退避上限
     * @param schedulerPoolSize 调度池
     * @param heartbeatConcurrency 心跳池
     * @param heartbeatIntervalSeconds 续期间隔
     * @param leaseSeconds 租约
     * @param shutdownGraceSeconds 关闭宽限
     * @param retentionDays 保留天数
     * @return 变体配置
     */
    private static WorkflowProperties copy(
            WorkflowProperties base,
            int maxNodes,
            int maxSteps,
            int modelTimeout,
            int retrievalTimeout,
            int mcpTimeout,
            int runTimeoutMinutes,
            int tenantConcurrency,
            int flowConcurrency,
            long resultMaxBytes,
            int workerConcurrency,
            int claimBatch,
            long claimPollIntervalMs,
            long claimIdleBackoffMaxMs,
            int schedulerPoolSize,
            int heartbeatConcurrency,
            int heartbeatIntervalSeconds,
            int leaseSeconds,
            int shutdownGraceSeconds,
            int retentionDays) {
        return new WorkflowProperties(
                base.enabled(),
                base.disabledReason(),
                base.instanceId(),
                maxNodes,
                maxSteps,
                modelTimeout,
                retrievalTimeout,
                mcpTimeout,
                runTimeoutMinutes,
                tenantConcurrency,
                flowConcurrency,
                resultMaxBytes,
                workerConcurrency,
                claimBatch,
                claimPollIntervalMs,
                claimIdleBackoffMaxMs,
                schedulerPoolSize,
                heartbeatConcurrency,
                heartbeatIntervalSeconds,
                leaseSeconds,
                base.claimEnabled(),
                shutdownGraceSeconds,
                retentionDays);
    }
}
