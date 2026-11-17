package com.riccon.worker.crypto.domain;

/**
 * 租户数据密钥记录（被主密钥包裹的 DEK）。
 *
 * @param tenantId 密钥所属租户；平台级凭据为 {@code platform}
 * @param keyId DEK 标识，写入密文前缀
 * @param purpose 用途，例如 {@code model-credential}
 * @param wrappedDek 被主密钥包裹的 DEK
 * @param wrapNonce 包裹 DEK 使用的 12 字节 nonce
 * @param algorithm 固定 {@code AES-256-GCM}
 * @param masterKeyId 主密钥标识，用于轮换
 */
public record TenantDataKeyRecord(
        String tenantId,
        String keyId,
        String purpose,
        byte[] wrappedDek,
        byte[] wrapNonce,
        String algorithm,
        String masterKeyId) {

    /** 防御性复制字节数组，避免调用方修改后影响解密。 */
    public TenantDataKeyRecord {
        wrappedDek = wrappedDek == null ? null : wrappedDek.clone();
        wrapNonce = wrapNonce == null ? null : wrapNonce.clone();
    }
}
