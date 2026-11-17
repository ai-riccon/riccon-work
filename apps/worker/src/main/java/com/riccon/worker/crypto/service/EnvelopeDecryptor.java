package com.riccon.worker.crypto.service;

import com.riccon.worker.crypto.domain.CredentialException;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM 信封解密。
 *
 * <p>密文格式 {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>}；AAD 固定为
 * {@code tenantId|purpose|keyId|schemaVersion}，把密文绑定到租户、用途与密钥版本，防止把
 * A 租户的密文换到 B 租户后仍能解密（ADR-0006 第 2 节）。
 *
 * <p>解密失败只抛统一异常，不携带密文、nonce、DEK 或底层异常；凭据明文只在返回的
 * {@link SecretValue} 中短暂存在。
 */
@Component
public class EnvelopeDecryptor {

    /** 支持的密文前缀。 */
    private static final String PREFIX = "enc:v1:";

    /** 信封格式版本。 */
    private static final int ENVELOPE_VERSION = 1;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** 主密钥提供者。 */
    private final MasterKeyProvider masterKeyProvider;

    /**
     * 创建信封解密器。
     *
     * @param masterKeyProvider 主密钥提供者
     */
    public EnvelopeDecryptor(MasterKeyProvider masterKeyProvider) {
        this.masterKeyProvider = masterKeyProvider;
    }

    /**
     * 解密凭据密文。
     *
     * @param credential 凭据记录
     * @param dataKey 凭据引用的数据密钥
     * @return 明文凭据
     * @throws CredentialException 格式错误、密钥不匹配或认证失败
     */
    public SecretValue decrypt(CredentialRecord credential, TenantDataKeyRecord dataKey) {
        requireConsistent(credential, dataKey);
        byte[] aad = aad(credential.tenantId(), dataKey.purpose(), dataKey.keyId());
        byte[] dekBytes = masterKeyProvider.unwrap(dataKey.wrapNonce(), dataKey.wrappedDek(), aad);
        byte[] payload = parsePayload(credential.ciphertext(), dataKey.keyId());
        byte[] nonce = new byte[MasterKeyProvider.NONCE_LENGTH];
        System.arraycopy(payload, 0, nonce, 0, nonce.length);
        byte[] ciphertext = new byte[payload.length - nonce.length];
        System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dekBytes, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new SecretValue(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // 统一错误，不区分认证失败/密钥错误，避免成为密文探测信号。
            throw new CredentialException("RICCON-CRYPTO-500", "credential decryption failed");
        }
    }

    /**
     * 构造与信封实现一致的 AAD。
     *
     * @param tenantId 租户
     * @param purpose 用途
     * @param keyId DEK 标识
     * @return UTF-8 AAD
     */
    public static byte[] aad(String tenantId, String purpose, String keyId) {
        return (tenantId + "|" + purpose + "|" + keyId + "|" + ENVELOPE_VERSION).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 校验凭据与数据密钥属于同一租户、同一 key 与同一用途。
     *
     * @param credential 凭据
     * @param dataKey 数据密钥
     */
    private static void requireConsistent(CredentialRecord credential, TenantDataKeyRecord dataKey) {
        if (!credential.tenantId().equals(dataKey.tenantId())) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential and data key tenant mismatch");
        }
        if (!credential.keyId().equals(dataKey.keyId())) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential and data key id mismatch");
        }
        if (!"model-credential".equals(dataKey.purpose())) {
            throw new CredentialException("RICCON-CRYPTO-500", "data key purpose is not allowed for model credentials");
        }
        if (!"AES-256-GCM".equals(dataKey.algorithm())) {
            throw new CredentialException("RICCON-CRYPTO-500", "unsupported data key algorithm");
        }
    }

    /**
     * 解析密文前缀与 Base64 载荷。
     *
     * @param ciphertext 密文字符串
     * @param keyId 期望的 DEK 标识
     * @return nonce 与密文
     */
    private static byte[] parsePayload(String ciphertext, String keyId) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential ciphertext has unsupported format");
        }
        String remainder = ciphertext.substring(PREFIX.length());
        int separator = remainder.indexOf(':');
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential ciphertext is missing key id or payload");
        }
        String embeddedKeyId = remainder.substring(0, separator);
        if (!embeddedKeyId.equals(keyId)) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential ciphertext key id does not match data key");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(remainder.substring(separator + 1));
            if (payload.length <= MasterKeyProvider.NONCE_LENGTH + TAG_LENGTH_BITS / 8) {
                throw new CredentialException("RICCON-CRYPTO-500", "credential ciphertext payload is truncated");
            }
            return payload;
        } catch (IllegalArgumentException ex) {
            throw new CredentialException("RICCON-CRYPTO-500", "credential ciphertext is not valid Base64");
        }
    }
}
