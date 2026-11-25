package com.riccon.api.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.api.auth.repository.TenantDirectoryRepository;
import com.riccon.api.config.WorkflowProperties;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.TenantStatus;
import com.riccon.api.workflow.runtime.domain.WorkflowRetentionPolicy;
import com.riccon.api.workflow.runtime.dto.WorkflowRuntimeLimitsResponse;
import com.riccon.api.workflow.runtime.service.WorkflowRuntimeLimitsService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行限制只读服务单元测试（AC-17，ADR-0024 §10.1）。
 *
 * <p>覆盖开启/关闭原因回退、租户保留策略归因与固定时区输出；HTTP 形状与权限由集成测试覆盖。
 */
class WorkflowRuntimeLimitsServiceTest {

    /** 固定时钟，便于断言 generatedAt。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);

    /** 关闭态未配置原因的固定文案。 */
    private static final String FALLBACK = "workflow orchestration is disabled by deployment configuration";

    /** 开启状态返回部署限制与部署默认保留。 */
    @Test
    @DisplayName("开启状态返回部署限制")
    void enabled_returnsDeploymentLimits() {
        WorkflowRuntimeLimitsService service = service(properties(true, ""), tenant(180));

        WorkflowRuntimeLimitsResponse response = service.current("tenant-a");

        assertThat(response.featureEnabled()).isTrue();
        assertThat(response.disabledReason()).isNull();
        assertThat(response.maxNodes()).isEqualTo(50);
        assertThat(response.maxSteps()).isEqualTo(200);
        assertThat(response.nodeTimeoutSeconds().model()).isEqualTo(300);
        assertThat(response.nodeTimeoutSeconds().retrieval()).isEqualTo(120);
        assertThat(response.nodeTimeoutSeconds().mcp()).isEqualTo(60);
        assertThat(response.runTimeoutSeconds()).isEqualTo(1_800);
        assertThat(response.tenantConcurrency()).isEqualTo(10);
        assertThat(response.workflowConcurrency()).isEqualTo(5);
        assertThat(response.resultMaxBytes()).isEqualTo(5_242_880L);
        assertThat(response.retentionDays()).isEqualTo(180);
        assertThat(response.retentionPolicy()).isEqualTo(WorkflowRetentionPolicy.DEPLOYMENT_DEFAULT);
        assertThat(response.generatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T11:00:00+08:00"));
    }

    /** 关闭且未配置原因时回退固定文案。 */
    @Test
    @DisplayName("关闭态未配置原因回退固定文案")
    void disabledWithoutReason_usesFallback() {
        WorkflowRuntimeLimitsService service = service(properties(false, ""), tenant(180));

        WorkflowRuntimeLimitsResponse response = service.current("tenant-a");

        assertThat(response.featureEnabled()).isFalse();
        assertThat(response.disabledReason()).isEqualTo(FALLBACK);
    }

    /** 关闭且配置原因时原样返回（去首尾空白）。 */
    @Test
    @DisplayName("关闭态使用部署配置原因")
    void disabledWithReason_usesConfiguredReason() {
        WorkflowRuntimeLimitsService service = service(properties(false, "  只读演练  "), tenant(180));

        assertThat(service.current("tenant-a").disabledReason()).isEqualTo("只读演练");
    }

    /** 租户保留与部署默认不同时归因租户策略。 */
    @Test
    @DisplayName("租户保留天数不同于部署默认时标记 TENANT_POLICY")
    void tenantRetentionDiffers_marksTenantPolicy() {
        WorkflowRuntimeLimitsService service = service(properties(true, ""), tenant(90));

        WorkflowRuntimeLimitsResponse response = service.current("tenant-a");

        assertThat(response.retentionDays()).isEqualTo(90);
        assertThat(response.retentionPolicy()).isEqualTo(WorkflowRetentionPolicy.TENANT_POLICY);
    }

    /** 租户不存在按统一 404 处理。 */
    @Test
    @DisplayName("租户不存在返回 NotFound")
    void unknownTenant_throwsNotFound() {
        WorkflowRuntimeLimitsService service = service(properties(true, ""), null);

        assertThatThrownBy(() -> service.current("missing")).isInstanceOf(NotFoundException.class);
    }

    /**
     * 构造服务。
     *
     * @param properties 部署配置
     * @param tenant 租户记录；空表示不存在
     * @return 服务
     */
    private static WorkflowRuntimeLimitsService service(WorkflowProperties properties, TenantRecord tenant) {
        TenantDirectoryRepository repository = mock(TenantDirectoryRepository.class);
        when(repository.findById(tenant == null ? "missing" : "tenant-a")).thenReturn(Optional.ofNullable(tenant));
        return new WorkflowRuntimeLimitsService(properties, repository, CLOCK);
    }

    /**
     * 构造默认部署配置并覆盖开关与原因。
     *
     * @param enabled 是否开启
     * @param disabledReason 关闭原因
     * @return 部署配置
     */
    private static WorkflowProperties properties(boolean enabled, String disabledReason) {
        return new WorkflowProperties(
                enabled,
                disabledReason,
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
    }

    /**
     * 构造租户记录。
     *
     * @param retentionDays 保留天数
     * @return 租户记录
     */
    private static TenantRecord tenant(int retentionDays) {
        return new TenantRecord(
                "tenant-a",
                "Tenant A",
                TenantStatus.ACTIVE,
                "standard",
                retentionDays,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
