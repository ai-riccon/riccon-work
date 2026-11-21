package com.riccon.api.admin.model.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.api.admin.model.gateway.config.ModelGatewaySwitchProperties;
import com.riccon.api.admin.model.gateway.domain.GatewaySwitchRow;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchEnvOverrideException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchValidationException;
import com.riccon.api.admin.model.gateway.domain.ModelGatewaySwitchVersionConflictException;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchResponse;
import com.riccon.api.admin.model.gateway.dto.ModelGatewaySwitchUpdateRequest;
import com.riccon.api.admin.model.gateway.repository.ModelGatewaySwitchRepository;
import com.riccon.api.admin.model.gateway.service.ModelGatewaySwitchService;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.quota.config.QuotaEnforcementProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网关开关服务的边界单元行为。
 *
 * <p>集成测试覆盖正常读写与环境覆盖；这里补充三个需要精确控制仓储返回值的边界：
 * 全局表缺行时回落到 V11 种子默认值、数据库非法值 fail-closed、条件更新影响 0 行时的并发冲突。
 * 这些分支不能靠真实数据库稳定构造，因此使用 mock 仓储；服务本身不启动 Spring。
 */
class ModelGatewaySwitchServiceTest {

    /** 仓储 mock。 */
    private ModelGatewaySwitchRepository repository;

    /** 路由/降级覆盖。 */
    private ModelGatewaySwitchProperties properties;

    /** 配额覆盖。 */
    private QuotaEnforcementProperties quotaProperties;

    /** 审计 mock。 */
    private AuditService auditService;

    /** 被测服务。 */
    private ModelGatewaySwitchService service;

    /** 固定时钟，保证时间断言可重复。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);

    /** 平台管理员主体。 */
    private final RicconPrincipal actor =
            new RicconPrincipal(UUID.fromString("0199c3a0-0000-7000-8000-000000000001"), "platform", "admin", "Admin");

    /** 请求元数据。 */
    private final RequestMeta meta = new RequestMeta("trace-test", "request-test", null);

    /** 每个测试重建 mock 与服务，避免交互状态串扰。 */
    @BeforeEach
    void createService() {
        repository = mock(ModelGatewaySwitchRepository.class);
        properties = new ModelGatewaySwitchProperties();
        quotaProperties = new QuotaEnforcementProperties();
        auditService = mock(AuditService.class);
        service = new ModelGatewaySwitchService(repository, properties, quotaProperties, auditService, clock);
    }

    /** 全局表缺行时 GET 回落到 ROUTED/true/false，版本 1，来源 DATABASE。 */
    @Test
    @DisplayName("缺行时返回 V11 种子默认值")
    void read_withoutRows_returnsSeedDefaults() {
        when(repository.findAll()).thenReturn(List.of());

        ModelGatewaySwitchResponse response = service.read();

        assertThat(response.routingMode()).isEqualTo("ROUTED");
        assertThat(response.failoverEnabled()).isTrue();
        assertThat(response.quotaEnforcementEnabled()).isFalse();
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.source()).isEqualTo("DATABASE");
        assertThat(response.envOverride()).isNull();
        assertThat(response.updatedBy()).isNull();
        assertThat(response.updatedAt().toString()).startsWith("2026-11-20T08:00");
    }

    /** 数据库值损坏时 GET 500，而不是静默回落掩盖问题。 */
    @Test
    @DisplayName("数据库非法值 fail-closed")
    void read_withInvalidDatabaseValue_failsClosed() {
        when(repository.findAll())
                .thenReturn(List.of(
                        row("routingMode", "SIDEWAYS", 1),
                        row("failoverEnabled", "true", 1),
                        row("quotaEnforcementEnabled", "false", 1)));

        assertThatThrownBy(() -> service.read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routingMode is invalid");

        when(repository.findAll())
                .thenReturn(List.of(
                        row("routingMode", "ROUTED", 1),
                        row("failoverEnabled", "yes", 1),
                        row("quotaEnforcementEnabled", "false", 1)));

        assertThatThrownBy(() -> service.read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failoverEnabled is invalid");
    }

    /** 条件更新影响 0 行时抛出冲突，并重读最新版本用于 409 提示；不写审计。 */
    @Test
    @DisplayName("条件更新 0 行抛出并发冲突")
    void update_whenConditionalUpdateAffectsNoRows_throwsConflict() {
        when(repository.findAll()).thenReturn(seedRows(1)).thenReturn(seedRows(2));
        when(repository.updateValue(anyString(), anyInt(), anyString(), any(UUID.class), any(Instant.class)))
                .thenReturn(0);

        ModelGatewaySwitchUpdateRequest request = new ModelGatewaySwitchUpdateRequest(1, "BASELINE", false, true);

        ModelGatewaySwitchVersionConflictException conflict = catchThrowableOfType(
                () -> service.update(request, actor, meta), ModelGatewaySwitchVersionConflictException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.currentVersion()).isEqualTo(2);
        verify(auditService, never()).append(any(AuditEvent.class));
    }

    /** 环境覆盖存在时任何写入都在访问仓储前被拒绝。 */
    @Test
    @DisplayName("环境覆盖优先拒绝写入")
    void update_withEnvironmentOverride_throwsBeforeWrite() {
        properties.setRoutingMode("BASELINE");
        ModelGatewaySwitchUpdateRequest request = new ModelGatewaySwitchUpdateRequest(1, "ROUTED", true, false);

        ModelGatewaySwitchEnvOverrideException override = catchThrowableOfType(
                () -> service.update(request, actor, meta), ModelGatewaySwitchEnvOverrideException.class);
        assertThat(override).isNotNull();
        assertThat(override.overriddenKeys()).isEqualTo(List.of("routingMode"));
        verify(repository, never()).findAll();
        verify(repository, never())
                .updateValue(anyString(), anyInt(), anyString(), any(UUID.class), any(Instant.class));
    }

    /** 缺少请求体时返回字段级校验异常；控制器同时做了显式空体检查。 */
    @Test
    @DisplayName("空请求体返回字段级校验异常")
    void update_withNullRequest_throwsValidation() {
        ModelGatewaySwitchValidationException validation = catchThrowableOfType(
                () -> service.update(null, actor, meta), ModelGatewaySwitchValidationException.class);
        assertThat(validation).isNotNull();
        assertThat(validation.field()).isEqualTo("version");
        verify(repository, never()).findAll();
    }

    /** 构造一行合法种子。 */
    private static GatewaySwitchRow row(String key, String value, int version) {
        return new GatewaySwitchRow(key, value, version, null, Instant.parse("2026-11-20T00:00:00Z"));
    }

    /** 构造指定版本的完整种子三行。 */
    private static List<GatewaySwitchRow> seedRows(int version) {
        return List.of(
                row("routingMode", "ROUTED", version),
                row("failoverEnabled", "true", version),
                row("quotaEnforcementEnabled", "false", version));
    }
}
