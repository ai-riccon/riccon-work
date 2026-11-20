package com.riccon.api.model.catalog;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 部署计费币种配置（{@code RICCON_BILLING_CURRENCY}）。
 *
 * <p>M04 只支持部署级单币种；币种在部署后不可在线变更（ADR-0022 §2.1）。模型目录的价格字段使用该币种展示，
 * 成本金额使用同一币种快照。配置只允许 ISO 4217 三位大写字母，非法值启动失败。
 */
@Component
@ConfigurationProperties(prefix = "riccon.billing")
public class BillingProperties implements InitializingBean {

    /** ISO 4217 币种格式。 */
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    /** 部署币种，默认 CNY。 */
    private String currency = "CNY";

    /**
     * 绑定币种。
     *
     * @param currency ISO 4217 三位代码
     */
    public void setCurrency(String currency) {
        this.currency = currency;
    }

    /**
     * 返回部署币种。
     *
     * @return 三位大写币种
     */
    public String currency() {
        return currency;
    }

    /**
     * 校验币种格式。
     *
     * @throws IllegalStateException 币种不是 ISO 4217 三位代码
     */
    @Override
    public void afterPropertiesSet() {
        String normalized = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("riccon.billing.currency must be an ISO 4217 code");
        }
        this.currency = normalized;
    }
}
