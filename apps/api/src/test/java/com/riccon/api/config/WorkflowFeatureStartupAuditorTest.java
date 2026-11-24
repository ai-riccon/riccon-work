package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditDetailsInvalidException;
import com.riccon.api.audit.service.AuditDetailsReader;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 编排开关启动观测（ADR-0024 §4.8、OPS-M06-019/024）。
 *
 * <p>覆盖：关闭态写 {@code workflow.feature.changed}（platform/SYSTEM，details 含
 * enabled/source/instanceId/observedAt）；开启态不写；审计写失败只记录固定错误码、不阻断启动；
 * gauge 值为 0/1。details 通过 {@code AuditEvent.create} 的真实白名单校验，因此同时证明读取白名单
 * 反向兼容。
 */
class WorkflowFeatureStartupAuditorTest {

    /** 固定时钟：2026-11-25T02:00:00Z → 北京时间 10:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneOffset.UTC);

    /** 关闭态启动写一条观测审计，details 满足白名单与 §8.1。 */
    @Test
    void disabledFeatureWritesStartupObservationAudit() {
        List<AuditEvent> events = new ArrayList<>();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowFeatureStartupAuditor auditor = auditor(properties(false, "维护窗口", "api-1"), events::add, meterRegistry);

        auditor.run(new DefaultApplicationArguments(new String[0]));

        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.tenantId()).isEqualTo("platform");
        assertThat(event.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(event.actorId()).isEqualTo("system");
        assertThat(event.action()).isEqualTo(AuditActions.WORKFLOW_FEATURE_CHANGED);
        assertThat(event.targetType()).isEqualTo(AuditTargetType.WORKFLOW);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.details())
                .containsEntry("enabled", Boolean.FALSE)
                .containsEntry("source", WorkflowFeatureStartupAuditor.SOURCE_DEPLOYMENT_CONFIG)
                .containsEntry("reason", "维护窗口")
                .containsEntry("instanceId", "api-1");
        assertThat(String.valueOf(event.details().get("observedAt"))).isEqualTo("2026-11-25T10:00+08:00");
        assertThat(meterRegistry
                        .get(WorkflowFeatureStartupAuditor.FEATURE_METRIC)
                        .gauge()
                        .value())
                .isZero();
    }

    /** 开启态不写审计，避免正常启动产生噪声；gauge 为 1。 */
    @Test
    void enabledFeatureDoesNotWriteAudit() {
        List<AuditEvent> events = new ArrayList<>();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowFeatureStartupAuditor auditor = auditor(properties(true, "", "api-1"), events::add, meterRegistry);

        auditor.run(new DefaultApplicationArguments(new String[0]));

        assertThat(events).isEmpty();
        assertThat(meterRegistry
                        .get(WorkflowFeatureStartupAuditor.FEATURE_METRIC)
                        .gauge()
                        .value())
                .isEqualTo(1d);
    }

    /** 审计失败只记录固定错误码，不向启动流程抛错（OPS-M06-024）。 */
    @Test
    void auditFailureDoesNotBlockStartup() {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowFeatureStartupAuditor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            WorkflowFeatureStartupAuditor auditor = auditor(
                    properties(false, "", "api-1"),
                    event -> {
                        throw new IllegalStateException("audit store unavailable");
                    },
                    new SimpleMeterRegistry());

            auditor.run(new DefaultApplicationArguments(new String[0]));

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains(WorkflowFeatureStartupAuditor.AUDIT_FAILURE_CODE);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 未配置 instanceId 时生成非空短标识，仍通过 details 白名单。 */
    @Test
    void blankInstanceIdFallsBackToGeneratedIdentifier() {
        List<AuditEvent> events = new ArrayList<>();
        WorkflowFeatureStartupAuditor auditor =
                auditor(properties(false, "", ""), events::add, new SimpleMeterRegistry());

        auditor.run(new DefaultApplicationArguments(new String[0]));

        assertThat(events).hasSize(1);
        assertThat(String.valueOf(events.get(0).details().get("instanceId")))
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(200);
    }

    /** 写入的 details 必须能被 M10 读取白名单回读；未知动作仍 fail-closed。 */
    @Test
    void featureChangedDetailsRoundTripThroughReader() throws Exception {
        List<AuditEvent> events = new ArrayList<>();
        auditor(properties(false, "演练", "api-9"), events::add, new SimpleMeterRegistry())
                .run(new DefaultApplicationArguments(new String[0]));
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(events.get(0).details());

        assertThat(AuditDetailsReader.read(mapper, AuditActions.WORKFLOW_FEATURE_CHANGED, json))
                .containsEntry("enabled", Boolean.FALSE)
                .containsEntry("instanceId", "api-9");
        // 未登记的动作即使 details 形状合法也必须拒绝，避免 M10 查询/导出凭空多出动作。
        assertThatThrownBy(() -> AuditDetailsReader.read(mapper, "workflow.unknown.action", json))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }

    /** instanceId/observedAt 缺失必须 fail-closed（OPS-M06-019）。 */
    @Test
    void featureChangedRequiresInstanceAndObservedAt() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_FEATURE_CHANGED,
                        Map.of(
                                "enabled",
                                Boolean.FALSE,
                                "source",
                                "DEPLOYMENT_CONFIG",
                                "observedAt",
                                "2026-11-25T10:00+08:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_FEATURE_CHANGED,
                        Map.of("enabled", Boolean.FALSE, "source", "DEPLOYMENT_CONFIG", "instanceId", "api-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedAt");
    }

    /**
     * 构造被测组件。
     *
     * @param properties 配置
     * @param writer 审计写入端口
     * @param meterRegistry 指标注册表
     * @return 启动观测组件
     */
    private static WorkflowFeatureStartupAuditor auditor(
            WorkflowProperties properties, WorkflowFeatureAuditWriter writer, SimpleMeterRegistry meterRegistry) {
        return new WorkflowFeatureStartupAuditor(properties, writer, CLOCK, meterRegistry);
    }

    /**
     * 构造配置。
     *
     * @param enabled 开关
     * @param disabledReason 关闭原因
     * @param instanceId 实例标识
     * @return 配置
     */
    private static WorkflowProperties properties(boolean enabled, String disabledReason, String instanceId) {
        return new WorkflowProperties(
                enabled,
                disabledReason,
                instanceId,
                50,
                200,
                300,
                120,
                60,
                30,
                10,
                5,
                5242880L,
                10,
                1,
                1000L,
                5000L,
                10,
                10,
                30,
                90,
                true,
                120,
                180);
    }
}
