package com.riccon.worker.crypto.service;

import com.riccon.worker.config.CryptoProperties;
import com.riccon.worker.crypto.domain.CredentialException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 主密钥提供者。
 *
 * <p>只接受 Base64 编码的 32 字节 AES-256 密钥（ADR-0006）。缺失、长度错误、无法解码或命中
 * 已知测试密钥时构造失败，让 Worker 在接收流量前退出；禁止生成临时密钥或降级明文。
 *
 * <p>解密时先尝试当前主密钥，再尝试轮换窗口中的上一把；两者都失败返回统一错误，
 * 不暴露具体是哪一把失败，避免成为密钥探测信号。
 */
@Component
public class MasterKeyProvider {

    /** AES-GCM nonce 长度。 */
    static final int NONCE_LENGTH = 12;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** 已知测试密钥：生产误用必须失败。 */
    private static final Set<String> KNOWN_TEST_KEYS =
            Set.of("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "cmljY29uLXRlc3QtbWFzdGVyLWtleS0zMi1ieXRlcyE=");

    /** 当前主密钥。 */
    private final SecretKey current;

    /** 上一把主密钥；未配置时为空。 */
    private final SecretKey previous;

    /** 当前主密钥标识，仅用于日志与审计。 */
    private final String masterKeyId;

    /** 实际使用的主密钥标识，测试用（当前密钥成功时为当前标识）。 */
    private final boolean rejectTestKeys;

    /**
     * 读取并校验主密钥。
     *
     * @param properties 加密配置
     * @throws IllegalStateException 主密钥缺失、长度错误、编码非法或命中测试密钥
     */
    public MasterKeyProvider(CryptoProperties properties) {
        this.rejectTestKeys = properties.rejectTestKeys();
        this.masterKeyId = properties.masterKeyId();
        this.current = decode(properties.masterKey(), "RICCON_MASTER_KEY");
        this.previous = properties.previousMasterKey().isBlank()
                ? null
                : decode(properties.previousMasterKey(), "RICCON_MASTER_KEY_PREVIOUS");
    }

    /**
     * 返回主密钥标识。
     *
     * @return 例如 {@code mk-1}
     */
    public String masterKeyId() {
        return masterKeyId;
    }

    /**
     * 用当前或上一把主密钥解密被包裹的 DEK。
     *
     * @param nonce 12 字节 nonce
     * @param ciphertext 密文与认证标签
     * @param aad 附加认证数据
     * @return 32 字节 DEK
     * @throws CredentialException 两把主密钥都无法解密
     */
    public byte[] unwrap(byte[] nonce, byte[] ciphertext, byte[] aad) {
        byte[] plaintext = tryDecrypt(current, nonce, ciphertext, aad);
        if (plaintext == null && previous != null) {
            plaintext = tryDecrypt(previous, nonce, ciphertext, aad);
        }
        if (plaintext == null) {
            throw new CredentialException("RICCON-CRYPTO-500", "data key cannot be unwrapped by any master key");
        }
        if (plaintext.length != 32) {
            throw new CredentialException("RICCON-CRYPTO-500", "unwrapped data key has invalid length");
        }
        return plaintext;
    }

    /**
     * 用指定主密钥执行一次 AES-GCM 解密。
     *
     * @param key 主密钥
     * @param nonce nonce
     * @param ciphertext 密文与标签
     * @param aad 附加认证数据
     * @return 明文；认证失败时返回 null
     */
    private static byte[] tryDecrypt(SecretKey key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        if (nonce == null
                || nonce.length != NONCE_LENGTH
                || ciphertext == null
                || ciphertext.length <= TAG_LENGTH_BITS / 8) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException ex) {
            // GCM 认证失败与密钥错误不可区分；不记录底层异常，避免密文/密钥相关信息进入日志。
            return null;
        }
    }

    /**
     * 解码并校验主密钥。
     *
     * @param encoded Base64 值
     * @param envName 环境变量名，用于错误信息
     * @return AES-256 密钥
     */
    private SecretKey decode(String encoded, String envName) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    envName + " is required; Worker refuses to start without envelope encryption");
        }
        if (rejectTestKeys && KNOWN_TEST_KEYS.contains(encoded)) {
            throw new IllegalStateException(envName + " is a known test key; inject a real random 32-byte key");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(envName + " must be valid Base64; value is not logged");
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(envName + " must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
