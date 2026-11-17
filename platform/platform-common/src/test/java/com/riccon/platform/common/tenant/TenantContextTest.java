package com.riccon.platform.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link TenantContext} 的线程隔离、嵌套恢复与失败语义测试。 */
class TenantContextTest {

    /** 每个用例结束后清理线程变量，避免测试之间串租户。 */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 设置的租户应能被当前线程读取。 */
    @Test
    void setAndGetReturnsCurrentTenant() {
        TenantContext.set(TenantId.of("tenant-a"));

        assertThat(TenantContext.get()).contains(TenantId.of("tenant-a"));
        assertThat(TenantContext.require().value()).isEqualTo("tenant-a");
    }

    /** 未设置租户时 require 必须失败，防止无租户访问数据。 */
    @Test
    void requireFailsWhenTenantMissing() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant context is not set");
    }

    /** clear 必须把当前线程恢复为无租户状态。 */
    @Test
    void clearRemovesCurrentTenant() {
        TenantContext.set(TenantId.of("tenant-a"));

        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
    }

    /** 嵌套作用域关闭后必须恢复外层租户，而不是清空。 */
    @Test
    void nestedScopeRestoresPreviousTenant() {
        TenantContext.set(TenantId.of("outer"));

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of("inner"))) {
            assertThat(TenantContext.require().value()).isEqualTo("inner");
        }

        assertThat(TenantContext.require().value()).isEqualTo("outer");
    }

    /** 最外层作用域关闭后必须清空线程变量，适配线程池复用。 */
    @Test
    void outermostScopeClearsTenant() {
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of("tenant-a"))) {
            assertThat(TenantContext.get()).isPresent();
        }

        assertThat(TenantContext.get()).isEmpty();
    }

    /** 重复关闭作用域不得破坏已经恢复的外层上下文。 */
    @Test
    void closingScopeTwiceIsIdempotent() {
        TenantContext.TenantScope scope = TenantContext.open(TenantId.of("tenant-a"));

        scope.close();

        assertThatCode(scope::close).doesNotThrowAnyException();
        assertThat(TenantContext.get()).isEmpty();
    }

    /** callWithTenant 应在执行期间生效，并在结束后恢复原租户。 */
    @Test
    void callWithTenantRestoresPreviousTenant() {
        TenantContext.set(TenantId.of("outer"));

        String result = TenantContext.callWithTenant(
                TenantId.of("inner"), () -> TenantContext.require().value());

        assertThat(result).isEqualTo("inner");
        assertThat(TenantContext.require().value()).isEqualTo("outer");
    }

    /** runWithTenant 同样必须恢复原租户。 */
    @Test
    void runWithTenantRestoresPreviousTenant() {
        TenantContext.runWithTenant(
                TenantId.of("tenant-a"), () -> assertThat(TenantContext.get()).isPresent());

        assertThat(TenantContext.get()).isEmpty();
    }

    /** 非法租户 ID 必须在入口处被拒绝。 */
    @Test
    void tenantIdRejectsIllegalValues() {
        assertThatThrownBy(() -> TenantId.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> TenantId.of("tenant/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal characters");
        assertThatThrownBy(() -> TenantId.of(null)).isInstanceOf(NullPointerException.class);
    }

    /** 合法租户 ID 应保留原始值。 */
    @Test
    void tenantIdAcceptsAllowedCharacters() {
        TenantId tenantId = TenantId.of("tenant_01-A");

        assertThat(tenantId.value()).isEqualTo("tenant_01-A");
        assertThat(tenantId).hasToString("tenant_01-A");
    }
}
