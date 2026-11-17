package com.riccon.platform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 信封加解密（纯 JDK，无 Spring 依赖）。
 *
 * <p>用于“需要精确重放、但不得明文入库”的小数据，例如审批通过后的 MCP 工具参数（ADR-0017）。
 * 本类不读取配置、不持有长期密钥、不写日志；密钥由调用方通过 {@link EnvelopeKey} 注入。
 *
 * <p>载荷格式：{@code version(1) || nonce(12) || ciphertext || tag(16)}。每次加密使用
 * {@link SecureRandom} 生成新的 12 字节 nonce；GCM 认证标签固定 128 位。解密失败统一抛
 * {@link EnvelopeException}，不区分密钥错误、AAD 不匹配与密文篡改，避免成为探测信号。
 *
 * <p>AAD 由 {@link #aad(String, String)} 生成，固定为 {@code UTF-8(tenantId + "|" + approvalId)}，
 * 把密文绑定到具体租户与审批行；把密文换到另一行、另一租户或另一密钥都无法通过认证。
 */
public final class AesGcmEnvelope {

    /** AES-256 密钥长度（字节）。 */
    public static final int KEY_BYTES = 32;

    /** GCM nonce 长度（字节）。 */
    public static final int NONCE_BYTES = 12;

    /** GCM 认证标签长度（字节）。 */
    public static final int TAG_BYTES = 16;

    /** 载荷最小长度：版本 1 + nonce 12 + tag 16；空明文也至少 29 字节。 */
    public static final int MIN_PAYLOAD_BYTES = 1 + NONCE_BYTES + TAG_BYTES;

    /** 当前载荷格式版本。 */
    private static final byte FORMAT_VERSION = 1;

    /** nonce 起始偏移。 */
    private static final int NONCE_OFFSET = 1;

    /** 密文起始偏移。 */
    private static final int CIPHERTEXT_OFFSET = NONCE_OFFSET + NONCE_BYTES;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = TAG_BYTES * 8;

    /** JDK 提供的 GCM 变换。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 进程级安全随机源；{@link SecureRandom} 线程安全。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private AesGcmEnvelope() {}

    /**
     * 加密一段明文。
     *
     * @param key 当前写密钥
     * @param plaintext 明文；允许空数组，禁止 null
     * @param aad 附加认证数据；必须非空，固定由 {@link #aad(String, String)} 生成
     * @return key id 与完整载荷
     * @throws EnvelopeException JDK 加密能力不可用
     */
    public static Sealed seal(EnvelopeKey key, byte[] plaintext, byte[] aad) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        requireAad(aad);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE, new SecretKeySpec(key.secret(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] payload = new byte[CIPHERTEXT_OFFSET + encrypted.length];
            payload[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, payload, NONCE_OFFSET, NONCE_BYTES);
            System.arraycopy(encrypted, 0, payload, CIPHERTEXT_OFFSET, encrypted.length);
            return new Sealed(key.keyId(), payload);
        } catch (GeneralSecurityException ex) {
            // 算法在 JDK 21 必然存在；走到这里说明运行环境被裁剪，必须失败而不是降级明文。
            throw new EnvelopeException("envelope encryption is unavailable");
        }
    }

    /**
     * 用显式密钥解密。
     *
     * @param key 解密密钥
     * @param payload {@code version||nonce||ciphertext||tag}
     * @param aad 附加认证数据，必须与加密时完全一致
     * @return 明文
     * @throws EnvelopeException 载荷格式错误、密钥错误、AAD 不匹配或密文被篡改
     */
    public static byte[] open(EnvelopeKey key, byte[] payload, byte[] aad) {
        Objects.requireNonNull(key, "key must not be null");
        requireAad(aad);
        requirePayload(payload);
        byte[] nonce = Arrays.copyOfRange(payload, NONCE_OFFSET, CIPHERTEXT_OFFSET);
        byte[] ciphertext = Arrays.copyOfRange(payload, CIPHERTEXT_OFFSET, payload.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(key.secret(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException ex) {
            // 认证失败不区分原因，也不把底层异常带入日志或响应。
            throw new EnvelopeException("envelope authentication failed");
        }
    }

    /**
     * 按 key id 从密钥环解析密钥并解密。
     *
     * @param keyRing 当前与轮换窗口内的密钥
     * @param keyId 密文记录中的 key id
     * @param payload {@code version||nonce||ciphertext||tag}
     * @param aad 附加认证数据
     * @return 明文
     * @throws EnvelopeException key id 未知、载荷格式错误或认证失败
     */
    public static byte[] open(EnvelopeKeyRing keyRing, String keyId, byte[] payload, byte[] aad) {
        Objects.requireNonNull(keyRing, "keyRing must not be null");
        return open(keyRing.require(keyId), payload, aad);
    }

    /**
     * 生成审批参数的 AAD。
     *
     * <p>租户 ID 与审批 ID 都不允许包含分隔符 {@code |}，避免不同组合拼出相同字节串。
     *
     * @param tenantId 租户 ID
     * @param approvalId 审批 ID（UUID 字符串）
     * @return UTF-8 AAD 字节
     * @throws IllegalArgumentException 任一值为空或包含分隔符
     */
    public static byte[] aad(String tenantId, String approvalId) {
        requireAadPart(tenantId, "tenantId");
        requireAadPart(approvalId, "approvalId");
        return (tenantId + "|" + approvalId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 校验 AAD 非空。
     *
     * @param aad 附加认证数据
     */
    private static void requireAad(byte[] aad) {
        if (aad == null || aad.length == 0) {
            throw new IllegalArgumentException("aad must not be empty");
        }
    }

    /**
     * 校验 AAD 片段非空且不含分隔符。
     *
     * @param value 片段
     * @param name 字段名，用于错误信息
     */
    private static void requireAadPart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|'");
        }
    }

    /**
     * 校验载荷版本与最小长度。
     *
     * @param payload 待解密载荷
     */
    private static void requirePayload(byte[] payload) {
        if (payload == null || payload.length < MIN_PAYLOAD_BYTES) {
            throw new EnvelopeException("envelope format is invalid");
        }
        if (payload[0] != FORMAT_VERSION) {
            throw new EnvelopeException("envelope version is not supported");
        }
    }

    /**
     * 加密结果。
     *
     * <p>{@code keyId} 与 {@code ciphertext} 必须成对持久化；密文列只保存 {@code ciphertext}，
     * key id 单独存列，便于轮换与按 key 检索。
     *
     * @param keyId 加密使用的 key id
     * @param ciphertext 完整载荷
     */
    public record Sealed(String keyId, byte[] ciphertext) {

        /**
         * 复制载荷，避免调用方修改内部状态。
         */
        public Sealed {
            Objects.requireNonNull(keyId, "keyId must not be null");
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            ciphertext = ciphertext.clone();
        }

        /**
         * 返回载荷副本。
         *
         * @return 载荷副本
         */
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
