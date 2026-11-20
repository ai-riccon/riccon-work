package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelRoute;
import com.riccon.worker.model.domain.RoutedModel;
import com.riccon.worker.model.service.FailoverChatModel;
import com.riccon.worker.model.service.ModelInvocationService;
import com.riccon.worker.model.service.ModelInvocationService.InvocationHandle;
import com.riccon.worker.model.service.ModelRouter;
import com.riccon.worker.model.support.ApiKeyRoutingStub;
import com.riccon.worker.model.support.ModelFailoverFixture;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.UserMessage;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * B4 降级状态机集成测试（IT-MODEL-103/104/105/109）。
 *
 * <p>用真实 MySQL 与本地 127.0.0.1 模型桩验证：尝试顺序/上限、失败矩阵、首字后不重放、
 * 多 key 失效与轮换、平台池优先于租户 BYO、开关回退与链空稳定错误、7 个指标注册。
 * 测试不发真实出网；桩的行为是确定性的。
 */
@TestPropertySource(
        properties = {
            "riccon.worker.model.routing.first-token-timeout=300ms",
            "riccon.worker.model.routing.pre-first-token-budget=3s",
            "riccon.worker.model.routing.retry-delays=0ms",
            "riccon.worker.model.routing.max-attempts=3"
        })
class ModelFailoverIntegrationTest extends WorkerIntegrationTestBase {

    /** 主模型桩。 */
    private TestModelStub primary;

    /** 备用模型桩。 */
    private TestModelStub secondary;

    /** key 维度模型桩。 */
    private ApiKeyRoutingStub keyAware;

    /** 夹具。 */
    private ModelFailoverFixture fixture;

    /** 模型网关。 */
    @Autowired
    private ModelRouter modelRouter;

    /** 调用记录。 */
    @Autowired
    private ModelInvocationService invocationService;

    /** Prometheus registry，用于验证 7 个指标已注册并预置 0 值。 */
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    /** 启动桩与夹具。 */
    @BeforeEach
    void setUp() {
        primary = new TestModelStub();
        secondary = new TestModelStub();
        keyAware = new ApiKeyRoutingStub();
        fixture = new ModelFailoverFixture(jdbcClient);
    }

    /** 关闭桩。 */
    @AfterEach
    void tearDown() {
        primary.close();
        secondary.close();
        keyAware.close();
    }

    /**
     * IT-MODEL-103：首字前超时切到下一个模型，attempt 数 = HTTP 调用数，切换写审计。
     */
    @Test
    @DisplayName("IT-MODEL-103：首字前超时切换到下一个模型")
    void stream_timeoutBeforeFirstToken_failsOverToNextModel() {
        primary.timeSliced(600, 10, 1);
        UUID first = fixture.createModel(primary.url(), "primary-model");
        UUID second = fixture.createModel(secondary.url(), "secondary-model");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.route(ModelPurpose.CHAT, first, second);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(run.model().emitted()).isTrue();
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(secondary.requestCount()).isEqualTo(1);
        assertThat(run.model().attemptCount()).isEqualTo(2);
        assertThat(attemptStatuses(tenant, run.handle())).containsExactly("FAILED", "SUCCEEDED");
        assertThat(attemptErrorCodes(tenant, run.handle())).containsExactly(ModelException.TIMEOUT, null);
        assertThat(failoverReasons(tenant)).containsExactly("TIMEOUT");
    }

    /**
     * IT-MODEL-103：5xx 先同 key 重试一次，再切到下一个模型；审计同时包含 RETRY 与 UNAVAILABLE。
     */
    @Test
    @DisplayName("IT-M04-01：5xx 同 key 重试后切换模型")
    void stream_serverError_retriesSameKeyThenNextModel() {
        primary.forceStatus(500);
        UUID first = fixture.createModel(primary.url(), "primary-model");
        UUID second = fixture.createModel(secondary.url(), "secondary-model");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.route(ModelPurpose.CHAT, first, second);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(primary.requestCount()).isEqualTo(2);
        assertThat(secondary.requestCount()).isEqualTo(1);
        assertThat(run.model().attemptCount()).isEqualTo(3);
        assertThat(failoverReasons(tenant)).containsExactly("RETRY", "UNAVAILABLE");
        assertThat(run.model().selectedTarget().model().modelKey()).isEqualTo("secondary-model");
    }

    /**
     * IT-MODEL-103：429 同 key 退避重试仍失败后切换；尝试上限内成功。
     */
    @Test
    @DisplayName("IT-M04-02：429 重试后切换模型")
    void stream_rateLimited_retriesThenSwitches() {
        primary.rateLimitNext(5);
        UUID first = fixture.createModel(primary.url(), "primary-model");
        UUID second = fixture.createModel(secondary.url(), "secondary-model");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.route(ModelPurpose.CHAT, first, second);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(primary.requestCount()).isEqualTo(2);
        assertThat(secondary.requestCount()).isEqualTo(1);
        assertThat(failoverReasons(tenant)).containsExactly("RETRY", "RATE_LIMITED");
    }

    /**
     * IT-MODEL-105：同一 provider 第一把 key 401 时立即失效，第二把 key 成功；
     * 失效审计以 platform/SYSTEM 写入，attempt 表保留 credential 引用。
     */
    @Test
    @DisplayName("IT-MODEL-105：401 失效当前 key 并使用下一把 key")
    void stream_unauthorized_invalidatesKeyAndUsesNextKey() {
        keyAware.statusFor("sk-first", 401);
        UUID model = fixture.createModel(keyAware.url(), "key-model");
        UUID provider = fixture.providerOf(model);
        UUID firstKey = fixture.addPlatformKey(provider, "sk-first", 10, "****irst");
        UUID secondKey = fixture.addPlatformKey(provider, "sk-second", 20, "****cond");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(keyAware.authorizations()).containsExactly("Bearer sk-first", "Bearer sk-second");
        assertThat(fixture.credentialStatus(firstKey)).isEqualTo("INVALID");
        assertThat(fixture.credentialStatus(secondKey)).isEqualTo("ACTIVE");
        Map<String, Object> invalidated = onlyAudit("model.credential.invalidated");
        assertThat(invalidated.get("tenantId")).isEqualTo("platform");
        assertThat(invalidated.get("actorType")).isEqualTo("SYSTEM");
        assertThat((String) invalidated.get("details")).contains(firstKey.toString(), "UNAUTHORIZED");
        assertThat(attemptStatuses(tenant, run.handle())).containsExactly("FAILED", "SUCCEEDED");
    }

    /**
     * IT-MODEL-105：所有 key 都 401 时全链失败，统一 503 文案，key 全部 INVALID。
     */
    @Test
    @DisplayName("IT-M04-03：全部 key 失效时稳定 503")
    void stream_allKeysUnauthorized_returnsUnified503() {
        keyAware.forceStatus(401);
        UUID model = fixture.createModel(keyAware.url(), "key-model");
        UUID provider = fixture.providerOf(model);
        UUID firstKey = fixture.addPlatformKey(provider, "sk-first", 10, "****irst");
        UUID secondKey = fixture.addPlatformKey(provider, "sk-second", 20, "****cond");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isInstanceOf(ModelException.class);
        ModelException error = (ModelException) run.error();
        assertThat(error.errorCode()).isEqualTo(ModelException.UNAVAILABLE);
        assertThat(error.getMessage()).isEqualTo(ModelException.CHAIN_UNAVAILABLE_MESSAGE);
        assertThat(fixture.credentialStatus(firstKey)).isEqualTo("INVALID");
        assertThat(fixture.credentialStatus(secondKey)).isEqualTo("INVALID");
        assertThat(auditCount("model.credential.invalidated")).isEqualTo(2);
        assertThat(run.model().attemptCount()).isEqualTo(2);
    }

    /**
     * IT-MODEL-105：请求租户的遗留 BYO 凭据被忽略，只使用平台池。
     */
    @Test
    @DisplayName("IT-M04-04：租户级凭据被忽略")
    void stream_tenantCredentialIsIgnored() {
        data.createTenant("tenant-a");
        keyAware.statusFor("sk-tenant", 401);
        UUID model = fixture.createModel(keyAware.url(), "key-model");
        UUID provider = fixture.providerOf(model);
        fixture.addKey("tenant-a", provider, "sk-tenant", 10, "****enant");
        fixture.addPlatformKey(provider, "sk-platform", 50, "****form");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = TenantId.of("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(keyAware.authorizations()).containsExactly("Bearer sk-platform");
    }

    /**
     * IT-MODEL-109：关闭降级后只允许一次尝试，不写降级审计。
     */
    @Test
    @DisplayName("IT-M04-05：failoverEnabled=false 只尝试一次")
    void stream_failoverDisabled_attemptsOnce() {
        primary.forceStatus(500);
        UUID first = fixture.createModel(primary.url(), "primary-model");
        UUID second = fixture.createModel(secondary.url(), "secondary-model");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.route(ModelPurpose.CHAT, first, second);
        fixture.switches("ROUTED", false);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isInstanceOf(ModelException.class);
        assertThat(((ModelException) run.error()).errorCode()).isEqualTo(ModelException.UNAVAILABLE);
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(secondary.requestCount()).isZero();
        assertThat(auditCount("model.failover.triggered")).isZero();
    }

    /**
     * IT-MODEL-109：BASELINE 回到 M02 单模型语义，选择最早创建的 ACTIVE 模型。
     */
    @Test
    @DisplayName("IT-MODEL-109：BASELINE 使用最早创建的模型")
    void switches_baseline_usesEarliestActiveModel() {
        UUID first = fixture.createModel(primary.url(), "primary-model");
        UUID second = fixture.createModel(secondary.url(), "secondary-model");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.route(ModelPurpose.CHAT, second);
        fixture.switches("BASELINE", true);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(secondary.requestCount()).isZero();
        assertThat(run.route().mode()).isEqualTo(com.riccon.worker.model.domain.ModelRoutingMode.BASELINE);
    }

    /**
     * IT-MODEL-109：已保存的链在全部模型停用后稳定 503，不回退链外模型，并累计链空指标。
     */
    @Test
    @DisplayName("IT-M04-06：配置后链为空稳定 503")
    void resolve_configuredChainEmpty_returns503() {
        UUID model = fixture.createModel(primary.url(), "primary-model");
        fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        fixture.route(ModelPurpose.CHAT, model);
        fixture.markChainConfigured(ModelPurpose.CHAT, true);
        fixture.setModelStatus(model, "DISABLED");
        TenantId tenant = tenant("tenant-a");
        double before = prometheusMeterRegistry
                .get("riccon_model_chain_empty")
                .tag("purpose", "CHAT")
                .counter()
                .count();

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, "trace-chain", null)))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException modelError = (ModelException) error;
                    assertThat(modelError.errorCode()).isEqualTo(ModelException.UNAVAILABLE);
                    assertThat(modelError.getMessage()).isEqualTo(ModelException.CHAIN_UNAVAILABLE_MESSAGE);
                });
        assertThat(primary.requestCount()).isZero();
        double after = prometheusMeterRegistry
                .get("riccon_model_chain_empty")
                .tag("purpose", "CHAT")
                .counter()
                .count();
        assertThat(after).isEqualTo(before + 1);
        assertThat(auditCount("model.invocation.failed")).isEqualTo(1);
        assertThat((String) onlyAudit("model.invocation.failed").get("details"))
                .contains("\"reason\": \"CHAIN_EMPTY\"");
    }

    /**
     * IT-M04-12：ROUTED 未配置（entries 为空、updated_by 仍为 NULL）时稳定 503，
     * 不再回退 M02 单模型；不创建模型调用，链空审计带 reason=CHAIN_EMPTY。
     */
    @Test
    @DisplayName("IT-M04-12：ROUTED 未配置空链稳定 503，不回退单模型")
    void resolve_routedEmptyChain_returns503WithoutModelCall() {
        UUID model = fixture.createModel(primary.url(), "primary-model");
        fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        TenantId tenant = tenant("tenant-a");
        double before = prometheusMeterRegistry
                .get("riccon_model_chain_empty")
                .tag("purpose", "CHAT")
                .counter()
                .count();

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, "trace-chain", null)))
                .isInstanceOf(ModelException.class)
                .satisfies(error -> {
                    ModelException modelError = (ModelException) error;
                    assertThat(modelError.errorCode()).isEqualTo(ModelException.UNAVAILABLE);
                    assertThat(modelError.getMessage()).isEqualTo(ModelException.CHAIN_UNAVAILABLE_MESSAGE);
                });

        assertThat(primary.requestCount()).isZero();
        assertThat(jdbcClient
                        .sql("SELECT COUNT(*) FROM model_invocation WHERE tenant_id = :tenantId")
                        .param("tenantId", tenant.value())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(prometheusMeterRegistry
                        .get("riccon_model_chain_empty")
                        .tag("purpose", "CHAT")
                        .counter()
                        .count())
                .isEqualTo(before + 1);
        assertThat((String) onlyAudit("model.invocation.failed").get("details"))
                .contains("\"reason\": \"CHAIN_EMPTY\"");
    }

    /**
     * IT-M04-13：BASELINE 模式仍使用 M02 单模型语义（无路由链也可用），并走端点守卫与平台 key 池。
     */
    @Test
    @DisplayName("IT-M04-13：BASELINE 单模型在空链下仍可用")
    void switches_baseline_withEmptyRoutingChain_usesCatalogModel() {
        UUID model = fixture.createModel(primary.url(), "primary-model");
        fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        fixture.switches("BASELINE", true);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isNull();
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(run.route().mode()).isEqualTo(com.riccon.worker.model.domain.ModelRoutingMode.BASELINE);
    }

    /**
     * IT-M04-14：BASELINE 空目录同样稳定 503（不做链外或空指针回退）。
     */
    @Test
    @DisplayName("IT-M04-14：BASELINE 空目录稳定 503")
    void switches_baseline_emptyCatalog_returns503() {
        fixture.switches("BASELINE", true);
        TenantId tenant = tenant("tenant-a");

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, "trace-empty", null)))
                .isInstanceOf(ModelException.class)
                .satisfies(error ->
                        assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.UNAVAILABLE));
        assertThat(primary.requestCount()).isZero();
    }

    /**
     * IT-M04-11：成功调用后重置 consecutive_failures，避免历史累计让下次失败立即冷却。
     */
    @Test
    @DisplayName("IT-M04-11：成功调用重置连续失败计数")
    void credential_successResetsConsecutiveFailures() {
        primary.forceStatus(500);
        UUID model = fixture.createModel(primary.url(), "primary-model");
        UUID credential = fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun failed = run(tenant, ModelPurpose.CHAT);
        assertThat(failed.error()).isInstanceOf(ModelException.class);
        assertThat(fixture.consecutiveFailures(credential)).isEqualTo(2);

        primary.reset();
        StreamRun success = run(tenant, ModelPurpose.CHAT);
        assertThat(success.error()).isNull();
        assertThat(fixture.consecutiveFailures(credential)).isZero();
    }

    /**
     * IT-MODEL-104：首字后断流保留已输出内容、不重放、尝试记 FAILED 且首字延迟非空；
     * 调用失败审计带 emitted=true。
     */
    @Test
    @DisplayName("IT-MODEL-104：首字后断流不重试不重放")
    void stream_failureAfterFirstToken_keepsContentAndDoesNotReplay() {
        primary.errorAfterChunk();
        UUID model = fixture.createModel(primary.url(), "primary-model");
        fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);

        assertThat(run.error()).isInstanceOf(ModelException.class);
        ModelException error = (ModelException) run.error();
        assertThat(error.errorCode()).isEqualTo(ModelException.INVALID_RESPONSE);
        assertThat(error.getMessage()).isEqualTo(ModelException.STREAM_INTERRUPTED_MESSAGE);
        assertThat(run.model().emitted()).isTrue();
        assertThat(run.model().attemptCount()).isEqualTo(1);
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(run.responses()).isNotEmpty();
        List<Integer> firstTokens = firstTokenValues(tenant, run.handle());
        assertThat(firstTokens).hasSize(1);
        assertThat(firstTokens.get(0)).isNotNull();
        assertThat(attemptStatuses(tenant, run.handle())).containsExactly("FAILED");

        // 模拟上层终态写入，验证 emitted 进入 model.invocation.failed 审计。
        TenantContext.runWithTenant(
                tenant,
                () -> invocationService.failed(
                        run.handle(),
                        "trace-b4",
                        null,
                        error.errorCode(),
                        true,
                        firstTokens.get(0),
                        true,
                        run.model().attemptCount()));
        Map<String, Object> failed = onlyAudit("model.invocation.failed");
        assertThat((String) failed.get("details")).contains("\"emitted\": true");
    }

    /**
     * IT-MODEL-104/开关：切换前后调用历史仍可读，attempt_count 落库。
     */
    @Test
    @DisplayName("IT-M04-07：开关切换后历史调用仍可读")
    void history_remainsReadableAfterSwitchChange() {
        UUID model = fixture.createModel(primary.url(), "primary-model");
        fixture.addPlatformKey(fixture.providerOf(model), "sk-primary", 10, "****mary");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun run = run(tenant, ModelPurpose.CHAT);
        assertThat(run.error()).isNull();
        TenantContext.runWithTenant(
                tenant,
                () -> invocationService.succeeded(
                        run.handle(),
                        run.model().selectedTarget().model().id(),
                        run.model().selectedTarget().model().modelKey(),
                        "trace-b4",
                        null,
                        5,
                        10,
                        5,
                        run.model().attemptCount()));

        fixture.switches("BASELINE", false);

        Map<String, Object> row = jdbcClient
                .sql("""
                        SELECT status, attempt_count FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", "tenant-a")
                .param(
                        "id",
                        com.riccon.worker.persistence.UuidBinary.toBytes(
                                run.handle().id()))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"), "attemptCount", rs.getInt("attempt_count")))
                .single();
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("attemptCount")).isEqualTo(1);
    }

    /**
     * IT-M04-09：连续 3 次可重试失败后 key 进入冷却，下一次请求跳过该 key（链为空稳定 503）。
     */
    @Test
    @DisplayName("IT-M04-09：连续失败进入冷却后跳过 key")
    void credential_entersCooldownAfterThreeFailures() {
        primary.forceStatus(500);
        UUID model = fixture.createModel(primary.url(), "primary-model");
        UUID provider = fixture.providerOf(model);
        UUID credential = fixture.addPlatformKey(provider, "sk-primary", 10, "****mary");
        fixture.route(ModelPurpose.CHAT, model);
        TenantId tenant = tenant("tenant-a");

        StreamRun first = run(tenant, ModelPurpose.CHAT);
        assertThat(first.error()).isInstanceOf(ModelException.class);
        StreamRun second = run(tenant, ModelPurpose.CHAT);
        assertThat(second.error()).isInstanceOf(ModelException.class);
        assertThat(fixture.consecutiveFailures(credential)).isGreaterThanOrEqualTo(3);

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, "trace-b4", null)))
                .isInstanceOf(ModelException.class)
                .satisfies(error ->
                        assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.UNAVAILABLE));
    }

    /**
     * OPS-M04-02a：7 个 Worker 指标已在启动时注册并预置 0 值，无 tenant 标签。
     */
    @Test
    @DisplayName("IT-M04-08：7 个指标已注册且预置 0 值（OPS-M04-02a 的 B4 注册证据）")
    void metrics_exposeSevenFrozenSeries() {
        String scrape = prometheusMeterRegistry.scrape();
        assertThat(scrape)
                .contains("riccon_model_requests_total{")
                .contains("riccon_model_failover_total{")
                .contains("riccon_model_attempt_duration_seconds_bucket")
                .contains("riccon_model_credential_invalid_total{")
                .contains("riccon_model_credential_cooldown_total{")
                .contains("riccon_model_chain_empty_total{")
                .contains("riccon_agent_runs_inflight");
        assertThat(scrape)
                .doesNotContain(
                        "riccon_model_requests_total{provider=\"none\",model=\"none\",outcome=\"none\",tenant=");
    }

    /**
     * 解析链并在租户作用域内执行一次流式调用。
     *
     * @param tenant 租户
     * @param purpose 用途
     * @return 运行结果
     */
    private StreamRun run(TenantId tenant, ModelPurpose purpose) {
        ModelRoute route =
                TenantContext.callWithTenant(tenant, () -> modelRouter.resolve(tenant, purpose, "trace-b4", null));
        RoutedModel first = route.firstModel().orElseThrow();
        InvocationHandle handle = TenantContext.callWithTenant(
                tenant,
                () -> invocationService.start(
                        tenant,
                        null,
                        null,
                        null,
                        first.target().model().id(),
                        first.target().model().modelKey(),
                        purpose,
                        "trace-b4",
                        null));
        FailoverChatModel model = modelRouter.createModel(route, tenant, handle, "trace-b4", null, purpose);
        List<io.agentscope.core.model.ChatResponse> responses = new ArrayList<>();
        RuntimeException error = null;
        try {
            model.stream(List.of(new UserMessage("你好")), List.of(), null)
                    .doOnNext(responses::add)
                    .blockLast(Duration.ofSeconds(10));
        } catch (RuntimeException ex) {
            error = ex;
        }
        return new StreamRun(route, model, handle, responses, error);
    }

    /**
     * 创建租户并返回标识。
     *
     * @param tenantId 租户 ID
     * @return 租户标识
     */
    private TenantId tenant(String tenantId) {
        data.createTenant(tenantId);
        return TenantId.of(tenantId);
    }

    /**
     * 读取 attempt 状态序列。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @return 状态列表
     */
    private List<String> attemptStatuses(TenantId tenant, InvocationHandle handle) {
        return jdbcClient
                .sql("""
                        SELECT status FROM model_invocation_attempt
                         WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                         ORDER BY attempt_no
                        """)
                .param("tenantId", tenant.value())
                .param("invocationId", com.riccon.worker.persistence.UuidBinary.toBytes(handle.id()))
                .query((rs, rowNum) -> rs.getString("status"))
                .list();
    }

    /**
     * 读取 attempt 错误码序列。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @return 错误码列表
     */
    private List<String> attemptErrorCodes(TenantId tenant, InvocationHandle handle) {
        return jdbcClient
                .sql("""
                        SELECT error_code FROM model_invocation_attempt
                         WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                         ORDER BY attempt_no
                        """)
                .param("tenantId", tenant.value())
                .param("invocationId", com.riccon.worker.persistence.UuidBinary.toBytes(handle.id()))
                .query((rs, rowNum) -> rs.getString("error_code"))
                .list();
    }

    /**
     * 读取 attempt 首字延迟序列。
     *
     * @param tenant 租户
     * @param handle 调用句柄
     * @return 首字延迟列表
     */
    private List<Integer> firstTokenValues(TenantId tenant, InvocationHandle handle) {
        return jdbcClient
                .sql("""
                        SELECT first_token_ms FROM model_invocation_attempt
                         WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                         ORDER BY attempt_no
                        """)
                .param("tenantId", tenant.value())
                .param("invocationId", com.riccon.worker.persistence.UuidBinary.toBytes(handle.id()))
                .query((rs, rowNum) -> (Integer) rs.getObject("first_token_ms"))
                .list();
    }

    /**
     * 读取降级审计原因序列（按发生顺序）。
     *
     * @param tenant 租户
     * @return 原因列表
     */
    private List<String> failoverReasons(TenantId tenant) {
        return jdbcClient
                .sql("""
                        SELECT details FROM audit_event
                         WHERE tenant_id = :tenantId AND action = 'model.failover.triggered'
                         ORDER BY occurred_at
                        """)
                .param("tenantId", tenant.value())
                .query((rs, rowNum) -> {
                    try {
                        return objectMapper
                                .readTree(rs.getString("details"))
                                .path("reason")
                                .asText();
                    } catch (tools.jackson.core.JacksonException ex) {
                        throw new IllegalStateException("invalid audit details", ex);
                    }
                })
                .list();
    }

    /**
     * 读取唯一一条指定动作审计的关键列。
     *
     * @param action 动作
     * @return 列映射
     */
    private Map<String, Object> onlyAudit(String action) {
        return jdbcClient
                .sql("""
                        SELECT tenant_id, actor_type, details FROM audit_event WHERE action = :action
                        """)
                .param("action", action)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "tenantId", rs.getString("tenant_id"),
                        "actorType", rs.getString("actor_type"),
                        "details", rs.getString("details")))
                .single();
    }

    /**
     * 统计指定动作审计条数。
     *
     * @param action 动作
     * @return 条数
     */
    private int auditCount(String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE action = :action")
                .param("action", action)
                .query((rs, rowNum) -> rs.getInt(1))
                .single();
    }

    /**
     * 一次测试运行的结果。
     *
     * @param route 路由链
     * @param model 降级客户端
     * @param handle 调用句柄
     * @param responses 已输出响应
     * @param error 失败错误
     */
    private record StreamRun(
            ModelRoute route,
            FailoverChatModel model,
            InvocationHandle handle,
            List<io.agentscope.core.model.ChatResponse> responses,
            RuntimeException error) {}
}
