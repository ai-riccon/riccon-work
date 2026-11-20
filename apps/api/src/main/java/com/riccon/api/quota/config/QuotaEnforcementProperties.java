package com.riccon.api.quota.config;

import java.util.Optional;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配额执行开关的部署覆盖。
 *
 * <p>环境变量 {@code RICCON_QUOTA_ENFORCEMENT_ENABLED} 优先级高于 {@code model_gateway_switch}；留空表示使用
 * 数据库开关。非法值在启动时失败，避免静默回退到“只计量”或“执行”中的任意一边（ADR-0022 第 1.3 节；
 * B3 负责写开关与审计，B6 只读取生效值）。
 */
@Component
@ConfigurationProperties(prefix = "riccon.model.gateway")
public class QuotaEnforcementProperties implements InitializingBean {

    /** 覆盖值：空表示使用数据库开关；否则必须为 true/false。 */
    private String quotaEnforcementEnabled = "";

    /**
     * 返回覆盖值原文。
     *
     * @return 覆盖值
     */
    public String getQuotaEnforcementEnabled() {
        return quotaEnforcementEnabled;
    }

    /**
     * 设置覆盖值。
     *
     * @param quotaEnforcementEnabled true/false 或空
     */
    public void setQuotaEnforcementEnabled(String quotaEnforcementEnabled) {
        this.quotaEnforcementEnabled = quotaEnforcementEnabled;
    }

    /**
     * 返回解析后的覆盖值。
     *
     * @return 有覆盖时返回值；空配置返回空
     */
    public Optional<Boolean> enabledOverride() {
        if (quotaEnforcementEnabled == null || quotaEnforcementEnabled.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(quotaEnforcementEnabled.strip().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * 启动时拒绝非 true/false 的覆盖值。
     */
    @Override
    public void afterPropertiesSet() {
        if (quotaEnforcementEnabled == null || quotaEnforcementEnabled.isBlank()) {
            return;
        }
        String normalized = quotaEnforcementEnabled.strip().toLowerCase(java.util.Locale.ROOT);
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            throw new IllegalArgumentException("riccon.model.gateway.quota-enforcement-enabled must be true or false");
        }
    }
}
