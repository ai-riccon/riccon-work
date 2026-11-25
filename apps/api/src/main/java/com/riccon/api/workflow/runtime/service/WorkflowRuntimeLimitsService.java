package com.riccon.api.workflow.runtime.service;

import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.config.WorkflowProperties;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.workflow.runtime.domain.WorkflowRetentionPolicy;
import com.riccon.api.workflow.runtime.dto.WorkflowRuntimeLimitsResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 编排运行限制只读服务（契约 {@code getWorkflowRuntimeLimits}，ADR-0024 §10.1）。
 *
 * <p>限制值只来自部署配置 {@code RICCON_WORKFLOW_*}，租户不能修改；保留天数取租户行
 * （与运行创建时写入 {@code retention_expires_at} 的口径一致），只有与部署默认不同时才标记为
 * {@link WorkflowRetentionPolicy#TENANT_POLICY}。
 *
 * <p>关闭状态下 {@code disabledReason} 优先返回部署配置的原因；未配置时回退固定文案，避免把空字符串
 * 或内部异常细节暴露给租户。开启状态不返回历史关闭原因，避免误导为“当前仍关闭”。
 *
 * <p>本服务不写审计：读取部署配置与保留策略属于普通只读查询，不构成配置变更。
 */
@Service
public class WorkflowRuntimeLimitsService {

    /** 关闭态未配置原因时的固定文案，与 {@code RICCON-WF-503} 的说明保持一致。 */
    public static final String DEFAULT_DISABLED_REASON =
            "workflow orchestration is disabled by deployment configuration";

    /** 展示时区：北京时间（合同 {@code project.timezone}）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 编排部署配置。 */
    private final WorkflowProperties properties;

    /** 租户目录，用于读取保留天数。 */
    private final TenantDirectoryRepository tenantDirectoryRepository;

    /** 可注入时钟。 */
    private final Clock clock;

    /**
     * 创建运行限制服务。
     *
     * @param properties 编排部署配置
     * @param tenantDirectoryRepository 租户目录
     * @param clock 可注入时钟
     */
    public WorkflowRuntimeLimitsService(
            WorkflowProperties properties, TenantDirectoryRepository tenantDirectoryRepository, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tenantDirectoryRepository =
                Objects.requireNonNull(tenantDirectoryRepository, "tenantDirectoryRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 读取当前租户的生效限制。
     *
     * @param tenantId 当前租户代码
     * @return 只读限制响应
     * @throws NotFoundException 租户不存在（正常请求由租户上下文保证存在）
     */
    public WorkflowRuntimeLimitsResponse current(String tenantId) {
        TenantRecord tenant =
                tenantDirectoryRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("tenant"));
        int retentionDays = tenant.retentionDays();
        WorkflowRetentionPolicy retentionPolicy = retentionDays == properties.retentionDays()
                ? WorkflowRetentionPolicy.DEPLOYMENT_DEFAULT
                : WorkflowRetentionPolicy.TENANT_POLICY;
        return new WorkflowRuntimeLimitsResponse(
                properties.enabled(),
                disabledReason(),
                properties.maxNodes(),
                properties.maxSteps(),
                new WorkflowRuntimeLimitsResponse.NodeTimeoutSeconds(
                        properties.modelTimeoutSeconds(),
                        properties.retrievalTimeoutSeconds(),
                        properties.mcpTimeoutSeconds()),
                // 启动校验保证不会溢出（分钟 <= Integer.MAX_VALUE / 60）。
                properties.runTimeoutMinutes() * 60,
                properties.tenantConcurrency(),
                properties.flowConcurrency(),
                properties.resultMaxBytes(),
                retentionDays,
                retentionPolicy,
                OffsetDateTime.ofInstant(clock.instant(), DISPLAY_ZONE));
    }

    /**
     * 计算关闭原因：开启时为空，关闭时使用部署配置或固定文案。
     *
     * @return 关闭原因或 {@code null}
     */
    private String disabledReason() {
        if (properties.enabled()) {
            return null;
        }
        String configured = properties.disabledReason();
        return configured == null || configured.isBlank() ? DEFAULT_DISABLED_REASON : configured.strip();
    }
}
