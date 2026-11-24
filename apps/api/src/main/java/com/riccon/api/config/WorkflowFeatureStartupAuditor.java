package com.riccon.api.config;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 编排开关启动观测（ADR-0024 第 4.8 节、OPS-M06-019/024）。
 *
 * <p>部署配置 {@code RICCON_WORKFLOW_ENABLED=false} 时，API 启动必须写一条
 * {@code workflow.feature.changed} 审计（actor=SYSTEM，tenant=platform），details 带
 * {@code source=DEPLOYMENT_CONFIG}、{@code instanceId} 与带偏移的 {@code observedAt}。语义是
 * “启动观测”，不是变更日志：多实例/重启重复写入可接受。
 *
 * <p>审计写入失败不得阻断 API 启动（OPS-M06-024）：本类是观测组件，关闭态下 API 仍需提供历史/审批
 * 只读与取消；失败只记录 ERROR，交由监控发现。业务审计仍按 ADR-0007 与业务事务同事务 fail-closed，
 * 本类不改变该语义。
 *
 * <p>同时暴露 {@code riccon_workflow_feature_enabled} gauge（1=开启、0=关闭），用于发现
 * “API 已关但 Worker 仍在领”的配置漂移；详细指标与告警阈值由 T15 落 RUNBOOK。
 */
@Component
public class WorkflowFeatureStartupAuditor implements ApplicationRunner {

    /** 关闭态启动观测的固定 WARN 码；供 RUNBOOK 与日志检索识别。 */
    public static final String DISABLED_WARN_CODE = "RICCON-WORKFLOW-010";

    /** 关闭态启动审计失败日志码，供 RUNBOOK 与告警检索。 */
    public static final String AUDIT_FAILURE_CODE = "RICCON-WORKFLOW-001";

    /** 指标名；Prometheus 暴露为 {@code riccon_workflow_feature_enabled}。 */
    public static final String FEATURE_METRIC = "riccon.workflow.feature.enabled";

    /** 审计 details 的固定来源值（ADR-0024 §4.8/§8.1）。 */
    public static final String SOURCE_DEPLOYMENT_CONFIG = "DEPLOYMENT_CONFIG";

    /** 北京时间偏移；审计 details 使用带偏移的 ISO 8601。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowFeatureStartupAuditor.class);

    /** 编排部署配置。 */
    private final WorkflowProperties properties;

    /** 审计写入端口；生产实现委托 {@code AuditService}。 */
    private final WorkflowFeatureAuditWriter auditWriter;

    /** 可注入时钟。 */
    private final Clock clock;

    /** 指标注册表。 */
    private final MeterRegistry meterRegistry;

    /**
     * 创建启动观测组件。
     *
     * @param properties 编排部署配置
     * @param auditWriter 审计写入端口
     * @param clock 可注入时钟
     * @param meterRegistry 指标注册表
     */
    public WorkflowFeatureStartupAuditor(
            WorkflowProperties properties,
            WorkflowFeatureAuditWriter auditWriter,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 注册开关指标；关闭态写一条启动观测审计，失败只记录日志。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        Gauge.builder(FEATURE_METRIC, properties, value -> value.enabled() ? 1d : 0d)
                .description("编排 Studio 总开关（1=开启、0=关闭）")
                .register(meterRegistry);
        if (properties.enabled()) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", Boolean.FALSE);
        details.put("source", SOURCE_DEPLOYMENT_CONFIG);
        if (properties.disabledReason() != null && !properties.disabledReason().isBlank()) {
            details.put("reason", truncate(properties.disabledReason().strip(), 200));
        }
        details.put("instanceId", instanceId());
        details.put(
                "observedAt",
                OffsetDateTime.ofInstant(clock.instant(), DISPLAY_ZONE).toString());
        try {
            // 审计写入走 TenantJdbc：必须显式打开 platform 租户上下文（与 BootstrapAdminRunner 一致），
            // 否则租户守卫会以 "tenant context is not set" fail-closed。
            TenantContext.runWithTenant(
                    PlatformTenant.TENANT_ID,
                    () -> auditWriter.append(AuditEvent.create(
                            clock.instant(),
                            "platform",
                            AuditActorType.SYSTEM,
                            "system",
                            AuditActions.WORKFLOW_FEATURE_CHANGED,
                            AuditTargetType.WORKFLOW,
                            null,
                            AuditOutcome.SUCCESS,
                            "workflow-feature-startup",
                            null,
                            null,
                            details)));
            LOG.warn("{} workflow feature disabled at startup; see audit workflow.feature.changed", DISABLED_WARN_CODE);
        } catch (RuntimeException exception) {
            // 关闭态下 API 仍需提供只读历史与取消；观测失败不能阻断启动（OPS-M06-024）。
            LOG.error(
                    "{} workflow feature startup audit failed; API continues with feature disabled",
                    AUDIT_FAILURE_CODE,
                    exception);
        }
    }

    /**
     * 返回实例标识：优先使用配置，缺省为 {@code hostname-随机后缀}。
     *
     * <p>随机后缀只用于区分同一主机上的并发实例，不进入业务数据；details 单值长度受审计白名单限制。
     *
     * @return 实例标识，长度 ≤200
     */
    private String instanceId() {
        if (properties.instanceId() != null && !properties.instanceId().isBlank()) {
            return truncate(properties.instanceId().strip(), 200);
        }
        String host = System.getenv("HOSTNAME");
        String hostname = host == null || host.isBlank() ? "api" : host.strip();
        return truncate(hostname + "-" + UUID.randomUUID().toString().substring(0, 8), 200);
    }

    /**
     * 截断审计 details 中的外部输入，避免超长值放大审计记录。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @return 截断后的值
     */
    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
