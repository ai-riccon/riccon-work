package com.riccon.api.quota.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.quota.domain.QuotaLimitSource;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaWindow;
import org.junit.jupiter.api.Test;

/**
 * 部署默认值与显式限额解析。
 *
 * <p>验证 {@code null}/0 都表示不限、“无显式行”与“显式 null”可区分、负数在启动或解析时失败。
 */
class QuotaPropertiesTest {

    /**
     * 无显式行时使用部署默认值并标记来源。
     */
    @Test
    void resolve_withoutExplicitRow_usesDeploymentDefault() {
        QuotaProperties properties = properties(10_000, 200_000, 1_000, 20_000);

        var effective = properties.resolve(QuotaScope.TENANT, QuotaWindow.DAILY, false, null);

        assertThat(effective.limit()).isEqualTo(10_000);
        assertThat(effective.source()).isEqualTo(QuotaLimitSource.DEPLOYMENT_DEFAULT);
    }

    /**
     * 显式 null 表示明确不限，不回退到部署默认值。
     */
    @Test
    void resolve_explicitNull_isExplicitUnlimited() {
        QuotaProperties properties = properties(10_000, 200_000, 1_000, 20_000);

        var effective = properties.resolve(QuotaScope.TENANT, QuotaWindow.DAILY, true, null);

        assertThat(effective.unlimited()).isTrue();
        assertThat(effective.source()).isEqualTo(QuotaLimitSource.EXPLICIT_UNLIMITED);
    }

    /**
     * 显式 0 等价于明确不限。
     */
    @Test
    void resolve_explicitZero_isExplicitUnlimited() {
        QuotaProperties properties = properties(10_000, 200_000, 1_000, 20_000);

        var effective = properties.resolve(QuotaScope.USER, QuotaWindow.MONTHLY, true, 0);

        assertThat(effective.unlimited()).isTrue();
        assertThat(effective.source()).isEqualTo(QuotaLimitSource.EXPLICIT_UNLIMITED);
    }

    /**
     * 显式正数表示有限限额。
     */
    @Test
    void resolve_explicitPositive_isExplicit() {
        QuotaProperties properties = properties(10_000, 200_000, 1_000, 20_000);

        var effective = properties.resolve(QuotaScope.USER, QuotaWindow.DAILY, true, 5);

        assertThat(effective.limit()).isEqualTo(5);
        assertThat(effective.source()).isEqualTo(QuotaLimitSource.EXPLICIT);
    }

    /**
     * 部署默认值为 0 时表示不限，但仍保留部署来源。
     */
    @Test
    void resolve_deploymentZero_isUnlimitedWithDeploymentSource() {
        QuotaProperties properties = properties(0, 200_000, 1_000, 20_000);

        var effective = properties.resolve(QuotaScope.TENANT, QuotaWindow.DAILY, false, null);

        assertThat(effective.unlimited()).isTrue();
        assertThat(effective.source()).isEqualTo(QuotaLimitSource.DEPLOYMENT_DEFAULT);
    }

    /**
     * 部署默认值为 null（未配置）时表示不限。
     */
    @Test
    void resolve_missingDeploymentDefault_isUnlimited() {
        QuotaProperties properties = new QuotaProperties();

        var effective = properties.resolve(QuotaScope.USER, QuotaWindow.DAILY, false, null);

        assertThat(effective.unlimited()).isTrue();
    }

    /**
     * 负数默认值在启动校验时失败。
     */
    @Test
    void afterPropertiesSet_negativeDefault_fails() {
        QuotaProperties properties = properties(-1, 200_000, 1_000, 20_000);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant-daily");
    }

    /**
     * 显式负数在解析时失败。
     */
    @Test
    void resolve_negativeExplicit_fails() {
        QuotaProperties properties = properties(10_000, 200_000, 1_000, 20_000);

        assertThatThrownBy(() -> properties.resolve(QuotaScope.USER, QuotaWindow.DAILY, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 组装测试配置。
     *
     * @param tenantDaily 租户日
     * @param tenantMonthly 租户月
     * @param userDaily 用户日
     * @param userMonthly 用户月
     * @return 配置
     */
    private static QuotaProperties properties(
            Integer tenantDaily, Integer tenantMonthly, Integer userDaily, Integer userMonthly) {
        QuotaProperties properties = new QuotaProperties();
        properties.getDefaults().setTenantDaily(tenantDaily);
        properties.getDefaults().setTenantMonthly(tenantMonthly);
        properties.getDefaults().setUserDaily(userDaily);
        properties.getDefaults().setUserMonthly(userMonthly);
        return properties;
    }
}
