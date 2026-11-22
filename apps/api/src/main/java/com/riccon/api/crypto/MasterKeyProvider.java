package com.riccon.api.crypto;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.MasterKeyRing;

/**
 * API 主密钥提供者。
 *
 * <p>API 从 M04 起具备信封加密能力（ADR-0021 §6.5）：新增/轮换平台 key 时需要包裹 DEK、加密明文，
 * 并为去重计算 HMAC 指纹。真正的密钥语义集中在 {@code platform-common} 的 {@link MasterKeyRing}，
 * 本类只做部署物内的薄封装，避免 API 与 Worker 出现两套 AAD/格式实现。
 *
 * <p>不缓存任何明文、DEK 或密文；每次调用都委托到共享实现。异常只携带稳定错误码与脱敏说明。
 */
public final class MasterKeyProvider {

    /** 共享主密钥环。 */
    private final MasterKeyRing ring;

    /**
     * 创建提供者。
     *
     * @param ring 已通过启动自检的主密钥环
     */
    public MasterKeyProvider(MasterKeyRing ring) {
        this.ring = ring;
    }

    /**
     * 返回当前主密钥标识。
     *
     * @return 例如 {@code mk-1}
     */
    public String masterKeyId() {
        return ring.masterKeyId();
    }

    /**
     * 是否已注入主密钥。
     *
     * @return 已配置返回 {@code true}
     */
    public boolean configured() {
        return ring.configured();
    }

    /**
     * 是否处于主密钥轮换窗口（同时持有上一把密钥）。
     *
     * @return 配置了上一把密钥返回 {@code true}
     */
    public boolean hasPreviousKey() {
        return ring.hasPreviousKey();
    }

    /**
     * 是否配置了指纹 pepper。
     *
     * @return 配置了 pepper 返回 {@code true}
     */
    public boolean pepperConfigured() {
        return ring.pepperConfigured();
    }

    /**
     * 用当前主密钥包裹新的 DEK。
     *
     * @param dek 32 字节数据密钥
     * @param aad 附加认证数据，必须与解密侧一致
     * @return 包裹后的密文与 nonce
     */
    public MasterKeyRing.WrappedDek wrap(byte[] dek, byte[] aad) {
        return ring.wrap(dek, aad);
    }

    /**
     * 用当前或上一把主密钥解开 DEK。
     *
     * @param nonce 包裹 nonce
     * @param ciphertext 密文与认证标签
     * @param aad 附加认证数据
     * @return 32 字节 DEK
     */
    public byte[] unwrap(byte[] nonce, byte[] ciphertext, byte[] aad) {
        return ring.unwrap(nonce, ciphertext, aad);
    }

    /**
     * 计算平台凭据指纹。
     *
     * @param providerId 供应商 ID
     * @param apiKey 明文 key
     * @return 64 位小写十六进制指纹
     */
    public String fingerprint(String providerId, String apiKey) {
        return ring.fingerprint(providerId, apiKey);
    }

    /**
     * 生成管理端展示用的末 4 位掩码。
     *
     * @param apiKey 明文 key
     * @return 例如 {@code ****a1b2}
     */
    public String maskedHint(String apiKey) {
        return CredentialEnvelope.maskedHint(apiKey);
    }

    /** 只输出标识与配置状态，不输出密钥材料。 */
    @Override
    public String toString() {
        return ring.toString();
    }
}
