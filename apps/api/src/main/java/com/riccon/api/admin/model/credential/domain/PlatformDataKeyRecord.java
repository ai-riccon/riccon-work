package com.riccon.api.admin.model.credential.domain;

/**
 * 平台模型凭据数据密钥记录（被主密钥包裹的 DEK）。
 *
 * <p>平台托管 key 池固定使用 {@code tenant_id='platform'} 与
 * {@code purpose='model-credential'}；记录只保存包裹后的 DEK 与 key 引用，不保存明文 DEK 或凭据明文
 * （ADR-0006、ADR-0021 §6.5）。字节数组在构造与读取时防御性复制，避免调用方修改影响解密。
 *
 * @param keyId DEK 标识，写入密文前缀
 * @param purpose 用途，固定 {@code model-credential}
 * @param wrappedDek 被主密钥包裹的 DEK
 * @param wrapNonce 包裹 DEK 使用的 nonce
 * @param algorithm 固定 {@code AES-256-GCM}
 * @param masterKeyId 主密钥标识，用于轮换
 */
public record PlatformDataKeyRecord(
        String keyId, String purpose, byte[] wrappedDek, byte[] wrapNonce, String algorithm, String masterKeyId) {

    /** 防御性复制字节数组，避免调用方修改后影响解密。 */
    public PlatformDataKeyRecord {
        wrappedDek = wrappedDek == null ? null : wrappedDek.clone();
        wrapNonce = wrapNonce == null ? null : wrapNonce.clone();
    }

    /** 返回包裹密文副本。 */
    @Override
    public byte[] wrappedDek() {
        return wrappedDek == null ? null : wrappedDek.clone();
    }

    /** 返回包裹 nonce 副本。 */
    @Override
    public byte[] wrapNonce() {
        return wrapNonce == null ? null : wrapNonce.clone();
    }
}
