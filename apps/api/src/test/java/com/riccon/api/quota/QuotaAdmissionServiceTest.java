package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.quota.domain.QuotaUnavailableException;
import com.riccon.api.quota.service.QuotaAdmissionService;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配额准入契约（ADR-0022 §1.3-R）：prepare 无活动事务守卫与 admit 行缺失 fail-closed。
 *
 * <p>两个用例都直接调用 service，验证无法从 HTTP 状态稳定构造的边界：业务事务内调用 prepare 必须立即失败
 * （避免 REQUIRES_NEW 嵌套占用第二条连接）；窗口行被清理后 admit 必须返回 503 语义而不是误报“已满 429”。
 */
class QuotaAdmissionServiceTest extends QuotaApiTestSupport {

    /** 准入服务。 */
    @Autowired
    private QuotaAdmissionService quotaAdmissionService;

    /** 事务管理器。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 每个用例前恢复执行模式。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * prepare 在活动事务内调用必须 fail-fast（防止第二条连接与 REQUIRES_NEW 嵌套）。
     */
    @Test
    @DisplayName("IT-QUOTA-106 prepare 在活动事务内 fail-fast")
    void prepare_insideActiveTransaction_failsFast() {
        Fixture fixture = fixture();
        TenantId tenantId = TenantId.of(fixture.tenant());
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenantId,
                        () -> transactionTemplate.execute(status -> quotaAdmissionService.prepare(
                                tenantId, UUID.fromString(userId(fixture.member())), Instant.now()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside an active transaction");
    }

    /**
     * 窗口行缺失时 admit 必须按 503 失败，不能当作“已满 429”。
     */
    @Test
    @DisplayName("IT-QUOTA-106 admit 行缺失返回 503 而不是 429")
    void admit_missingUsageRow_returnsUnavailable() {
        Fixture fixture = fixture();
        TenantId tenantId = TenantId.of(fixture.tenant());
        QuotaAdmissionService.AdmissionContext context = TenantContext.callWithTenant(
                tenantId,
                () -> quotaAdmissionService.prepare(
                        tenantId, UUID.fromString(userId(fixture.member())), Instant.now()));
        int deleted = jdbcClient.sql("""
                        DELETE FROM quota_usage
                         WHERE tenant_id = :tenantId
                           AND scope_type = 'TENANT'
                           AND window_kind = 'DAILY'
                        """).param("tenantId", fixture.tenant()).update();
        assertThat(deleted).isPositive();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> TenantContext.runWithTenant(
                        tenantId,
                        () -> transactionTemplate.executeWithoutResult(status -> {
                            quotaAdmissionService.lockTenantShared(tenantId);
                            quotaAdmissionService.admit(context);
                        })))
                .isInstanceOf(QuotaUnavailableException.class);
    }
}
