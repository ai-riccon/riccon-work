package com.riccon.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型网关客户端配置。
 *
 * <p>端点地址来自全局 {@code model_provider} 目录，不由请求方传入；本配置只控制超时、
 * 有限重试与本地测试是否允许明文 HTTP。生产必须使用 HTTPS（SSRF 与凭据保护要求）。
 *
 * @param connectTimeout 建立连接超时
 * @param requestTimeout 等待响应头的超时；流式正文另有空闲超时
 * @param maxAttempts 单个模型请求最多尝试次数（包含首次）
 * @param retryDelays 每次可重试失败后的退避时长
 * @param allowInsecureHttp 是否允许 http 端点；仅本地测试可开启
 * @param defaultMaxOutputTokens 未指定时的输出上限
 */
@ConfigurationProperties(prefix = "riccon.worker.model")
public record ModelGatewayProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue({"200ms", "1s"}) List<Duration> retryDelays,
        @DefaultValue("false") boolean allowInsecureHttp,
        @DefaultValue("2048") int defaultMaxOutputTokens) {

    /** 校验超时与重试上限，禁止无限等待或无界重试。 */
    public ModelGatewayProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("riccon.worker.model.max-attempts must be between 1 and 5");
        }
        if (retryDelays == null || retryDelays.isEmpty()) {
            throw new IllegalArgumentException("riccon.worker.model.retry-delays must not be empty");
        }
        retryDelays = List.copyOf(retryDelays);
        if (defaultMaxOutputTokens < 1 || defaultMaxOutputTokens > 32768) {
            throw new IllegalArgumentException(
                    "riccon.worker.model.default-max-output-tokens must be between 1 and 32768");
        }
    }

    /** 校验时长为正，避免零超时把正常请求判为失败。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("riccon.worker.model." + name + " must be positive");
        }
    }
}
