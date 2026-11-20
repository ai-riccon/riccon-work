package com.riccon.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API 信封加密与凭据指纹配置。
 *
 * <p>主密钥与 pepper 只允许由部署环境注入（ADR-0006、ADR-0021 §6.5），仓库与镜像中不得保存真实值。
 * 配置项本身允许为空，由 {@link com.riccon.api.crypto.config.CryptoConfig} 在启动阶段执行 fail-closed 校验：
 * 缺失、长度不足、无法解码或命中测试密钥都让 API 启动失败，禁止降级为明文。
 *
 * <p>同时保留与 Worker 一致的 {@code riccon.crypto.*} 键名，环境变量以 {@code RICCON_MASTER_KEY}、
 * {@code RICCON_MASTER_KEY_PREVIOUS}、{@code RICCON_MASTER_KEY_ID}、
 * {@code RICCON_CREDENTIAL_FINGERPRINT_PEPPER} 为准。
 *
 * @param masterKey Base64 编码的 32 字节主密钥
 * @param previousMasterKey 轮换窗口内的上一把主密钥，可为空
 * @param masterKeyId 主密钥标识，只用于审计与轮换，不参与加密强度
 * @param rejectTestKeys 是否拒绝已知测试密钥；非 test profile 强制 true，配置为 false 会导致启动失败
 * @param fingerprintPepper Base64 编码的 32 字节指纹 pepper，可为空
 */
@ConfigurationProperties(prefix = "riccon.crypto")
public record CryptoProperties(
        @DefaultValue("") String masterKey,
        @DefaultValue("") String previousMasterKey,
        @DefaultValue("mk-1") String masterKeyId,
        @DefaultValue("true") boolean rejectTestKeys,
        @DefaultValue("") String fingerprintPepper) {

    /** 规范化可选配置，避免 null 传播到加密实现。 */
    public CryptoProperties {
        masterKey = masterKey == null ? "" : masterKey;
        previousMasterKey = previousMasterKey == null ? "" : previousMasterKey;
        fingerprintPepper = fingerprintPepper == null ? "" : fingerprintPepper;
        if (masterKeyId == null || masterKeyId.isBlank() || masterKeyId.length() > 64) {
            throw new IllegalArgumentException("riccon.crypto.master-key-id must be 1-64 characters");
        }
    }

    /**
     * 脱敏输出：主密钥、上一把主密钥与 pepper 只显示是否已注入，禁止进入日志、异常或调试输出。
     *
     * <p>记录默认 {@code toString()} 会拼接全部组件原值，一旦被直接打印即违反 ADR-0006 §5；
     * 因此在这里覆盖，保持 {@code masterKeyId} 与 {@code rejectTestKeys} 可诊断。
     */
    @Override
    public String toString() {
        return "CryptoProperties[masterKey=" + redacted(masterKey)
                + ", previousMasterKey=" + redacted(previousMasterKey)
                + ", masterKeyId=" + masterKeyId
                + ", rejectTestKeys=" + rejectTestKeys
                + ", fingerprintPepper=" + redacted(fingerprintPepper)
                + "]";
    }

    /**
     * 返回脱敏占位，不暴露原值。
     *
     * @param value 配置值
     * @return 已设置返回 {@code [redacted]}，未设置返回 {@code [not set]}
     */
    private static String redacted(String value) {
        return value == null || value.isEmpty() ? "[not set]" : "[redacted]";
    }
}
