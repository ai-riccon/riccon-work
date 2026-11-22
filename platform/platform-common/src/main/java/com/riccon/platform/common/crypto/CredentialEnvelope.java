package com.riccon.platform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭据信封格式与 AAD（API 与 Worker 共用的唯一实现）。
 *
 * <p>密文格式固定为 {@code enc:v1:<keyId>:<Base64(nonce || ciphertext || tag)>}，AAD 固定为
 * {@code tenantId|purpose|keyId|schemaVersion}（ADR-0006 第 2 节）。把租户、用途与 DEK 标识绑定进 AAD，
 * 使“把 A 租户的密文换到 B 租户”“换用途”“换 DEK”都无法通过认证，而不是依赖调用方自觉。
 *
 * <p>本类只做 DEK 级别的加解密；DEK 本身的包裹/解包由 {@link MasterKeyRing} 完成。实现只依赖 JDK，
 * 不缓存明文、DEK 或密文，也不在异常中携带密文与 nonce。
 */
public final class CredentialEnvelope {

    /** 支持的密文前缀。 */
    public static final String PREFIX = "enc:v1:";

    /** 信封格式版本，参与 AAD。 */
    public static final int SCHEMA_VERSION = 1;

    /** 模型凭据用途；Worker 解密时只接受该用途。 */
    public static final String PURPOSE_MODEL_CREDENTIAL = "model-credential";

    /** GCM nonce 长度（字节）。 */
    private static final int NONCE_BYTES = 12;

    /** GCM 认证标签长度（字节）。 */
    private static final int TAG_BYTES = 16;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = TAG_BYTES * 8;

    /** AES-256 密钥长度（字节）。 */
    private static final int KEY_BYTES = 32;

    /** JDK 提供的 GCM 变换。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** DEK 标识字符集；与 {@link EnvelopeKey} 的 key id 规则保持一致，并额外禁止格式分隔符。 */
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** 进程级安全随机源；{@link SecureRandom} 线程安全。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private CredentialEnvelope() {}

    /**
     * 构造凭据 AAD：{@code tenantId|purpose|keyId|schemaVersion}。
     *
     * <p>任一片段为空或包含分隔符 {@code |} 都直接拒绝，避免不同组合拼出相同字节串。
     *
     * @param tenantId 租户 ID
     * @param purpose 用途，例如 {@code model-credential}
     * @param keyId DEK 标识
     * @return UTF-8 AAD 字节
     * @throws IllegalArgumentException 片段为空或包含分隔符
     */
    public static byte[] aad(String tenantId, String purpose, String keyId) {
        requireAadPart(tenantId, "tenantId");
        requireAadPart(purpose, "purpose");
        requireKeyId(keyId);
        return (tenantId + "|" + purpose + "|" + keyId + "|" + SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 用 DEK 加密凭据明文。
     *
     * <p>每次加密生成新的 12 字节 nonce；调用方必须把返回的字符串整体入库，不得截断。
     *
     * @param tenantId 租户 ID
     * @param purpose 用途
     * @param keyId DEK 标识，写入密文前缀
     * @param dek 32 字节数据密钥
     * @param plaintext 明文密码/密钥
     * @return {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>}
     * @throws EnvelopeException 运行环境缺少 AES-GCM
     */
    public static String encrypt(String tenantId, String purpose, String keyId, byte[] dek, byte[] plaintext) {
        requireDek(dek);
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] aad = aad(tenantId, purpose, keyId);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            // JDK 21 必然提供 AES/GCM；走到这里说明运行环境被裁剪，必须失败而不是写入明文。
            throw new EnvelopeException("credential encryption is unavailable");
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    /**
     * 用 DEK 解密凭据密文。
     *
     * @param tenantId 租户 ID
     * @param purpose 用途
     * @param keyId 期望的 DEK 标识
     * @param dek 32 字节数据密钥
     * @param ciphertext {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>}
     * @return 明文；调用方负责使用后清零
     * @throws EnvelopeException 格式错误、key id 不匹配、认证失败或运行环境缺少 AES-GCM
     */
    public static byte[] decrypt(String tenantId, String purpose, String keyId, byte[] dek, String ciphertext) {
        requireDek(dek);
        byte[] aad = aad(tenantId, purpose, keyId);
        Parsed parsed = parse(ciphertext, keyId);
        byte[] payload = parsed.payload();
        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException ex) {
            // 认证失败与错误密钥不可区分；不携带底层异常，避免成为密文探测信号。
            throw new EnvelopeException("credential decryption failed");
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(sealed, (byte) 0);
        }
    }

    /**
     * 返回凭据末 4 位掩码，供管理端展示；短于 8 位时只返回 {@code ****}。
     *
     * @param plaintext 明文凭据
     * @return 例如 {@code ****a1b2}
     */
    public static String maskedHint(String plaintext) {
        if (plaintext == null || plaintext.length() < 8) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - 4);
    }

    /**
     * 解析密文前缀与 Base64 载荷。
     *
     * @param ciphertext 密文字符串
     * @param keyId 期望的 DEK 标识
     * @return 解析结果
     * @throws EnvelopeException 格式、Base64 或 key id 校验失败
     */
    private static Parsed parse(String ciphertext, String keyId) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new EnvelopeException("credential ciphertext has unsupported format");
        }
        String remainder = ciphertext.substring(PREFIX.length());
        int separator = remainder.indexOf(':');
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new EnvelopeException("credential ciphertext is missing key id or payload");
        }
        String embeddedKeyId = remainder.substring(0, separator);
        if (!embeddedKeyId.equals(keyId)) {
            throw new EnvelopeException("credential ciphertext key id does not match data key");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(remainder.substring(separator + 1));
            if (payload.length <= NONCE_BYTES + TAG_BYTES) {
                Arrays.fill(payload, (byte) 0);
                throw new EnvelopeException("credential ciphertext payload is truncated");
            }
            return new Parsed(embeddedKeyId, payload);
        } catch (IllegalArgumentException ex) {
            throw new EnvelopeException("credential ciphertext is not valid Base64");
        }
    }

    /** 校验 AAD 片段非空且不含分隔符。 */
    private static void requireAadPart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|'");
        }
    }

    /** 校验 DEK 标识字符集。 */
    private static void requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId must be 1-64 characters of [A-Za-z0-9._-]");
        }
    }

    /** 校验 DEK 长度。 */
    private static void requireDek(byte[] dek) {
        if (dek == null || dek.length != KEY_BYTES) {
            throw new EnvelopeException("data key must be exactly 32 bytes");
        }
    }

    /**
     * 解析后的密文。
     *
     * @param keyId 密文携带的 DEK 标识
     * @param payload {@code nonce||ciphertext||tag}
     */
    private record Parsed(String keyId, byte[] payload) {}
}
