package com.riccon.api.quota.config;

import com.riccon.api.quota.domain.QuotaEffectiveLimit;
import com.riccon.api.quota.domain.QuotaLimitSource;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaWindow;
import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配额部署默认值（ADR-0022 第 1.1 节）。
 *
 * <p>只从部署配置 {@code RICCON_QUOTA_DEFAULT_TENANT_DAILY}、{@code RICCON_QUOTA_DEFAULT_TENANT_MONTHLY}、
 * {@code RICCON_QUOTA_DEFAULT_USER_DAILY}、{@code RICCON_QUOTA_DEFAULT_USER_MONTHLY} 读取；代码内不得再硬编码
 * 第二套默认值。{@code null} 与 {@code 0} 都表示不限；负数非法，启动直接失败。
 */
@Component
@ConfigurationProperties(prefix = "riccon.quota")
public class QuotaProperties implements InitializingBean {

    /** 四组部署默认值；B1a 已在 application.yml 声明同名键。 */
    private Defaults defaults = new Defaults();

    /**
     * 返回部署默认值。
     *
     * @return 默认值
     */
    public Defaults getDefaults() {
        return defaults;
    }

    /**
     * 设置部署默认值。
     *
     * @param defaults 默认值
     */
    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    /**
     * 解析指定维度与窗口的生效限额。
     *
     * <p>无显式行时使用部署默认值；显式行 {@code null} 或 {@code 0} 都映射为“明确不限”，与部署默认 0 一样
     * 不参与条件比较（ADR-0022 第 1.1 节）。用 {@code explicitPresent} 区分“没有显式行”和“显式 null”，
     * 否则数据库中的明确不限会错误回退到部署默认值。
     *
     * @param scope 维度
     * @param window 窗口
     * @param explicitPresent 是否存在显式行
     * @param explicitValue 显式限额；{@code null} 表示明确不限
     * @return 生效限额与来源
     */
    public QuotaEffectiveLimit resolve(
            QuotaScope scope, QuotaWindow window, boolean explicitPresent, Integer explicitValue) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(window, "window must not be null");
        if (explicitPresent) {
            Integer normalized = normalize(explicitValue);
            return new QuotaEffectiveLimit(
                    normalized, normalized == null ? QuotaLimitSource.EXPLICIT_UNLIMITED : QuotaLimitSource.EXPLICIT);
        }
        Integer deploymentDefault = deploymentDefault(scope, window);
        return new QuotaEffectiveLimit(normalize(deploymentDefault), QuotaLimitSource.DEPLOYMENT_DEFAULT);
    }

    /**
     * 启动时校验四个默认值非负。
     */
    @Override
    public void afterPropertiesSet() {
        if (defaults == null) {
            throw new IllegalArgumentException("riccon.quota.defaults must not be null");
        }
        defaults.validate();
    }

    /**
     * 返回部署默认限额。
     *
     * @param scope 维度
     * @param window 窗口
     * @return 部署默认值；{@code null} 表示不限
     */
    private Integer deploymentDefault(QuotaScope scope, QuotaWindow window) {
        return switch (scope) {
            case TENANT ->
                switch (window) {
                    case DAILY -> defaults.getTenantDaily();
                    case MONTHLY -> defaults.getTenantMonthly();
                };
            case USER ->
                switch (window) {
                    case DAILY -> defaults.getUserDaily();
                    case MONTHLY -> defaults.getUserMonthly();
                };
        };
    }

    /**
     * 将 {@code 0} 归一为 {@code null}（不限），并拒绝负数。
     *
     * @param value 原始值
     * @return 归一化限额
     */
    private static Integer normalize(Integer value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("quota limit must not be negative");
        }
        return value;
    }

    /** 四个部署默认值。 */
    public static class Defaults {

        /** 租户日限额。 */
        private Integer tenantDaily;

        /** 租户月限额。 */
        private Integer tenantMonthly;

        /** 用户日限额。 */
        private Integer userDaily;

        /** 用户月限额。 */
        private Integer userMonthly;

        /**
         * 返回租户日限额。
         *
         * @return 限额；{@code null}/0 表示不限
         */
        public Integer getTenantDaily() {
            return tenantDaily;
        }

        /**
         * 设置租户日限额。
         *
         * @param tenantDaily 限额
         */
        public void setTenantDaily(Integer tenantDaily) {
            this.tenantDaily = tenantDaily;
        }

        /**
         * 返回租户月限额。
         *
         * @return 限额
         */
        public Integer getTenantMonthly() {
            return tenantMonthly;
        }

        /**
         * 设置租户月限额。
         *
         * @param tenantMonthly 限额
         */
        public void setTenantMonthly(Integer tenantMonthly) {
            this.tenantMonthly = tenantMonthly;
        }

        /**
         * 返回用户日限额。
         *
         * @return 限额
         */
        public Integer getUserDaily() {
            return userDaily;
        }

        /**
         * 设置用户日限额。
         *
         * @param userDaily 限额
         */
        public void setUserDaily(Integer userDaily) {
            this.userDaily = userDaily;
        }

        /**
         * 返回用户月限额。
         *
         * @return 限额
         */
        public Integer getUserMonthly() {
            return userMonthly;
        }

        /**
         * 设置用户月限额。
         *
         * @param userMonthly 限额
         */
        public void setUserMonthly(Integer userMonthly) {
            this.userMonthly = userMonthly;
        }

        /**
         * 校验默认值非负；{@code null} 与 0 合法。
         */
        void validate() {
            validate("tenant-daily", tenantDaily);
            validate("tenant-monthly", tenantMonthly);
            validate("user-daily", userDaily);
            validate("user-monthly", userMonthly);
        }

        /**
         * 校验单个默认值。
         *
         * @param name 配置键
         * @param value 值
         */
        private static void validate(String name, Integer value) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException("riccon.quota.defaults." + name + " must not be negative");
            }
        }
    }
}
