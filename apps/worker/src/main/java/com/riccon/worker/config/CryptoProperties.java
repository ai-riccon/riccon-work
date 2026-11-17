package com.riccon.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker 信封加密配置。
 *
 * <p>主密钥只允许由部署环境注入（ADR-0006），仓库与镜像中不得保存真实值。配置项可以为空，
 * 由 {@link com.riccon.worker.crypto.service.MasterKeyProvider} 在启动阶段执行 fail-closed 校验：
 * 缺失、长度不足或无法解码都让进程启动失败，禁止降级为明文。
 *
 * @param masterKey Base64 编码的 32 字节主密钥
 * @param previousMasterKey 轮换窗口内的上一把主密钥，可为空
 * @param masterKeyId 主密钥标识，只用于审计与轮换，不参与加密强度
 * @param rejectTestKeys 是否拒绝已知测试密钥；生产必须保持 true
 */
@ConfigurationProperties(prefix = "riccon.worker.crypto")
public record CryptoProperties(
        @DefaultValue("") String masterKey,
        @DefaultValue("") String previousMasterKey,
        @DefaultValue("mk-1") String masterKeyId,
        @DefaultValue("true") boolean rejectTestKeys) {

    /** 规范化可选配置，避免 null 传播到加密实现。 */
    public CryptoProperties {
        masterKey = masterKey == null ? "" : masterKey;
        previousMasterKey = previousMasterKey == null ? "" : previousMasterKey;
        if (masterKeyId == null || masterKeyId.isBlank() || masterKeyId.length() > 64) {
            throw new IllegalArgumentException("riccon.worker.crypto.master-key-id must be 1-64 characters");
        }
    }
}
