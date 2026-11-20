package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.riccon.worker.model.support.ModelFailoverFixture;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 首字前总预算集成测试（ADR-0021 §5.2）。
 *
 * <p>两个慢模型各在首字超时内失败，合计耗尽 {@code pre-first-token-budget}；状态机必须在
 * 下一次尝试前返回 504，且员工文案仍是统一失败文案。独立的属性上下文避免影响其他用例。
 */
@TestPropertySource(
        properties = {
            "riccon.worker.model.routing.first-token-timeout=250ms",
            "riccon.worker.model.routing.pre-first-token-budget=400ms",
            "riccon.worker.model.routing.retry-delays=0ms",
            "riccon.worker.model.routing.max-attempts=5"
        })
class ModelAttemptBudgetIntegrationTest extends WorkerIntegrationTestBase {

    /** 主模型桩。 */
    private TestModelStub primary;

    /** 备用模型桩。 */
    private TestModelStub secondary;

    /** 第三模型桩；用于证明预算耗尽时不会再发起第三次 HTTP。 */
    private TestModelStub tertiary;

    /** 夹具。 */
    private ModelFailoverFixture fixture;

    /** 模型网关。 */
    @Autowired
    private ModelRouter modelRouter;

    /** 调用记录。 */
    @Autowired
    private ModelInvocationService invocationService;

    /** 启动桩与夹具。 */
    @BeforeEach
    void setUp() {
        primary = new TestModelStub();
        secondary = new TestModelStub();
        tertiary = new TestModelStub();
        fixture = new ModelFailoverFixture(jdbcClient);
    }

    /** 关闭桩。 */
    @AfterEach
    void tearDown() {
        primary.close();
        secondary.close();
        tertiary.close();
    }

    /** 预算耗尽时在下一次尝试前返回 504，不无限重试。 */
    @Test
    @DisplayName("IT-M04-10：首字前预算耗尽返回 504")
    void stream_preFirstTokenBudgetExhausted_returns504() {
        primary.timeSliced(600, 10, 1);
        secondary.timeSliced(600, 10, 1);
        tertiary.timeSliced(600, 10, 1);
        UUID first = fixture.createModel(primary.url(), "slow-primary");
        UUID second = fixture.createModel(secondary.url(), "slow-secondary");
        UUID third = fixture.createModel(tertiary.url(), "slow-tertiary");
        fixture.addPlatformKey(fixture.providerOf(first), "sk-primary", 10, "****mary");
        fixture.addPlatformKey(fixture.providerOf(second), "sk-secondary", 10, "****dary");
        fixture.addPlatformKey(fixture.providerOf(third), "sk-tertiary", 10, "****iary");
        fixture.route(ModelPurpose.CHAT, first, second, third);
        data.createTenant("tenant-a");
        TenantId tenant = TenantId.of("tenant-a");

        ModelRoute route = TenantContext.callWithTenant(
                tenant, () -> modelRouter.resolve(tenant, ModelPurpose.CHAT, "trace-b4", null));
        RoutedModel firstRoute = route.firstModel().orElseThrow();
        InvocationHandle handle = TenantContext.callWithTenant(
                tenant,
                () -> invocationService.start(
                        tenant,
                        null,
                        null,
                        null,
                        firstRoute.target().model().id(),
                        firstRoute.target().model().modelKey(),
                        ModelPurpose.CHAT,
                        "trace-b4",
                        null));
        FailoverChatModel model = modelRouter.createModel(route, tenant, handle, "trace-b4", null, ModelPurpose.CHAT);

        RuntimeException error = null;
        try {
            model.stream(List.of(new UserMessage("你好")), List.of(), null).blockLast(Duration.ofSeconds(10));
        } catch (RuntimeException ex) {
            error = ex;
        }

        assertThat(error).isInstanceOf(ModelException.class);
        ModelException modelError = (ModelException) error;
        assertThat(modelError.errorCode()).isEqualTo(ModelException.TIMEOUT);
        assertThat(modelError.getMessage()).isEqualTo(ModelException.CHAIN_UNAVAILABLE_MESSAGE);
        assertThat(model.attemptCount()).isEqualTo(2);
        assertThat(tertiary.requestCount()).isZero();
        assertThat(jdbcClient
                        .sql("""
                                SELECT COUNT(*) FROM model_invocation_attempt
                                 WHERE tenant_id = :tenantId AND invocation_id = :invocationId
                                """)
                        .param("tenantId", tenant.value())
                        .param("invocationId", UuidBinary.toBytes(handle.id()))
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }
}
