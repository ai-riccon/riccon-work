package com.riccon.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API → Worker 内部服务令牌配置。
 *
 * <p>HMAC 密钥只由部署环境注入，缺失或过短时 Worker 拒绝启动（ADR-0014 第 5 节）。
 * 令牌有效期上限固定为 300 秒，不允许通过配置放宽。
 *
 * @param secret HMAC-SHA256 共享密钥；至少 32 个字符
 * @param maxTokenAgeSeconds 允许的最大令牌有效期（秒），不得超过 300
 * @param clockSkewSeconds 允许的时钟偏差（秒）
 */
@ConfigurationProperties(prefix = "riccon.worker.service-token")
public record ServiceTokenProperties(
        @DefaultValue("") String secret,
        @DefaultValue("300") long maxTokenAgeSeconds,
        @DefaultValue("5") long clockSkewSeconds) {

    /** 校验密钥长度与时间窗口，防止配置错误被带入运行期。 */
    public ServiceTokenProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "riccon.worker.service-token.secret must be at least 32 characters (injected at deploy)");
        }
        if (maxTokenAgeSeconds < 1 || maxTokenAgeSeconds > 300) {
            throw new IllegalArgumentException(
                    "riccon.worker.service-token.max-token-age-seconds must be between 1 and 300");
        }
        if (clockSkewSeconds < 0 || clockSkewSeconds > 30) {
            throw new IllegalArgumentException("riccon.worker.service-token.clock-skew-seconds must be 0-30");
        }
    }
}
