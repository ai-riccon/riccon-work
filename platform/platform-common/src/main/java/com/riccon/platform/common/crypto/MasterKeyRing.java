package com.riccon.platform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 主密钥环（API 与 Worker 共用的唯一实现）。
 *
 * <p>负责三件事：
 *
 * <ol>
 *   <li>启动时校验主密钥：Base64、恰好 32 字节、拒绝已知测试密钥（生产），并做一次 canary 自检；
 *   <li>用当前主密钥包裹新 DEK；解密时先试当前、再试轮换窗口内的上一把，两者都失败才报错；
 *   <li>用独立 pepper 计算凭据指纹（HMAC-SHA-256），供去重使用且不可反推（ADR-0021 §6.5）。
 * </ol>
 *
 * <p>本类只依赖 JDK（ADR-0006）。密钥材料只保存在 {@link SecretKey} 中，不缓存 DEK、明文或密文；
 * 每次 {@link #unwrap} 返回新数组，调用方可安全清零。<strong>禁止</strong>把密钥、DEK、nonce 或密文
 * 写入日志、异常消息与 {@link #toString()}。
 */
public final class MasterKeyRing {

    /** 主密钥长度（字节）。 */
    public static final int KEY_BYTES = 32;

    /** GCM nonce 长度（字节）。 */
    public static final int NONCE_BYTES = 12;

    /** GCM 认证标签长度（字节）。 */
    private static final int TAG_BYTES = 16;

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = TAG_BYTES * 8;

    /** JDK 提供的 GCM 变换。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 指纹算法；ADR-0021 §3.2 固定 HMAC-SHA-256。 */
    private static final String FINGERPRINT_ALGORITHM = "HmacSHA256";

    /** 进程级安全随机源；{@link SecureRandom} 线程安全。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 已知测试密钥：生产误用必须启动失败。 */
    private static final Set<String> KNOWN_TEST_KEYS =
            Set.of("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "cmljY29uLXRlc3QtbWFzdGVyLWtleS0zMi1ieXRlcyE=");

    /** canary 明文；只用于启动自检，不是密钥。 */
    private static final byte[] CANARY_PLAINTEXT = "riccon-master-key-canary-v1".getBytes(StandardCharsets.UTF_8);

    /** canary 的附加认证数据；固定值让自检同时覆盖 AAD 路径。 */
    private static final byte[] CANARY_AAD = "riccon-master-key-canary".getBytes(StandardCharsets.UTF_8);

    /** 当前写密钥。 */
    private final SecretKey current;

    /** 上一把主密钥；未配置时为 {@code null}。 */
    private final SecretKey previous;

    /** 指纹 pepper；未配置时为 {@code null}。 */
    private final SecretKey pepper;

    /** 当前主密钥标识，只用于日志、审计与轮换，不参与加密强度。 */
    private final String masterKeyId;

    /** 是否为测试 profile 的未配置占位环；占位环的所有加密操作失败关闭。 */
    private final boolean configured;

    /**
     * 内部构造。
     *
     * @param current 当前写密钥
     * @param previous 上一把密钥，可为 {@code null}
     * @param pepper 指纹 pepper，可为 {@code null}
     * @param masterKeyId 主密钥标识
     * @param configured 是否已配置
     */
    private MasterKeyRing(
            SecretKey current, SecretKey previous, SecretKey pepper, String masterKeyId, boolean configured) {
        this.current = current;
        this.previous = previous;
        this.pepper = pepper;
        this.masterKeyId = masterKeyId;
        this.configured = configured;
    }

    /**
     * 从部署注入的 Base64 配置装配主密钥环，并执行启动自检。
     *
     * <p>当前主密钥缺失、无法解码、长度不是 32 字节或命中已知测试密钥时抛出
     * {@link MasterKeyConfigException}；上一把主密钥与 pepper 可选，但一旦提供就必须合法。
     * 自检失败同样拒绝启动，禁止降级为明文或临时密钥。
     *
     * @param currentBase64 当前主密钥，Base64 编码的 32 字节
     * @param previousBase64 上一把主密钥，可为空
     * @param masterKeyId 主密钥标识
     * @param pepperBase64 指纹 pepper，可为空
     * @param rejectTestKeys 是否拒绝已知测试密钥；生产必须为 {@code true}
     * @return 完成自检的密钥环
     * @throws MasterKeyConfigException 配置缺失、非法或自检失败
     */
    public static MasterKeyRing load(
            String currentBase64,
            String previousBase64,
            String masterKeyId,
            String pepperBase64,
            boolean rejectTestKeys) {
        String keyId = requireMasterKeyId(masterKeyId);
        SecretKey current = decode(currentBase64, "RICCON_MASTER_KEY", rejectTestKeys);
        SecretKey previous =
                isBlank(previousBase64) ? null : decode(previousBase64, "RICCON_MASTER_KEY_PREVIOUS", rejectTestKeys);
        SecretKey pepper =
                isBlank(pepperBase64) ? null : decode(pepperBase64, "RICCON_CREDENTIAL_FINGERPRINT_PEPPER", false);
        MasterKeyRing ring = new MasterKeyRing(current, previous, pepper, keyId, true);
        ring.selfCheck();
        return ring;
    }

    /**
     * 测试 profile 未注入主密钥时的占位环。
     *
     * <p>只允许在显式 {@code test} profile 下由装配代码创建；任何加密、解密或指纹操作都会抛出
     * {@link MasterKeyConfigException}，确保缺失密钥时行为仍然失败关闭，不会静默返回明文。
     *
     * @param masterKeyId 主密钥标识，可为空
     * @return 未配置的占位环
     */
    public static MasterKeyRing unconfigured(String masterKeyId) {
        return new MasterKeyRing(null, null, null, isBlank(masterKeyId) ? "mk-1" : masterKeyId.trim(), false);
    }

    /**
     * 返回当前主密钥标识。
     *
     * @return 例如 {@code mk-1}
     */
    public String masterKeyId() {
        return masterKeyId;
    }

    /**
     * 是否已注入主密钥。
     *
     * @return 已配置返回 {@code true}
     */
    public boolean configured() {
        return configured;
    }

    /**
     * 是否配置了轮换窗口内的上一把主密钥。
     *
     * @return 配置了上一把返回 {@code true}
     */
    public boolean hasPreviousKey() {
        return previous != null;
    }

    /**
     * 是否配置了指纹 pepper。
     *
     * @return 配置了 pepper 返回 {@code true}
     */
    public boolean pepperConfigured() {
        return pepper != null;
    }

    /**
     * 用当前主密钥包裹新的 DEK。
     *
     * <p>每次包裹都生成新的 12 字节 nonce，禁止复用；AAD 必须与密文的 AAD 一致，否则后续无法解包。
     *
     * @param dek 32 字节数据密钥
     * @param aad 附加认证数据
     * @return 包裹后的密文与 nonce
     * @throws EnvelopeException DEK 长度错误、AAD 为空或运行环境缺少 AES-GCM
     */
    public WrappedDek wrap(byte[] dek, byte[] aad) {
        requireConfigured();
        if (dek == null || dek.length != KEY_BYTES) {
            throw new EnvelopeException("data key must be exactly 32 bytes");
        }
        if (aad == null || aad.length == 0) {
            throw new EnvelopeException("data key AAD must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            return new WrappedDek(gcm(Cipher.ENCRYPT_MODE, current, nonce, dek, aad), nonce);
        } catch (GeneralSecurityException ex) {
            // JDK 21 必然提供 AES/GCM；走到这里说明运行环境被裁剪，必须失败而不是写入明文。
            throw new EnvelopeException("data key wrapping is unavailable");
        }
    }

    /**
     * 用当前或上一把主密钥解开被包裹的 DEK。
     *
     * <p>先试当前密钥、再试轮换窗口中的上一把；两者都失败返回统一错误，不暴露具体是哪一把失败，
     * 避免成为密钥探测信号。
     *
     * @param nonce 12 字节包裹 nonce
     * @param ciphertext 密文与认证标签
     * @param aad 附加认证数据
     * @return 32 字节 DEK（调用方负责使用后清零）
     * @throws EnvelopeException 两把主密钥都无法解开或明文长度不是 32 字节
     */
    public byte[] unwrap(byte[] nonce, byte[] ciphertext, byte[] aad) {
        requireConfigured();
        byte[] plaintext = tryDecrypt(current, nonce, ciphertext, aad);
        if (plaintext == null && previous != null) {
            plaintext = tryDecrypt(previous, nonce, ciphertext, aad);
        }
        if (plaintext == null) {
            throw new EnvelopeException("data key cannot be unwrapped by any master key");
        }
        if (plaintext.length != KEY_BYTES) {
            Arrays.fill(plaintext, (byte) 0);
            throw new EnvelopeException("unwrapped data key has invalid length");
        }
        return plaintext;
    }

    /**
     * 计算平台凭据指纹：{@code HEX(HMAC-SHA-256(pepper, providerId || 0x00 || apiKey))}。
     *
     * <p>用于去重与唯一键，不可反推明文；pepper 不随主密钥轮换（ADR-0021 §3.2）。
     *
     * @param providerId 供应商 ID
     * @param apiKey 明文 API key
     * @return 64 位小写十六进制指纹
     * @throws MasterKeyConfigException pepper 未配置
     * @throws EnvelopeException 运行环境缺少 HMAC-SHA-256
     */
    public String fingerprint(String providerId, String apiKey) {
        requireConfigured();
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (pepper == null) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.FINGERPRINT_UNAVAILABLE,
                    MasterKeyConfigException.Reason.PEPPER_MISSING,
                    "RICCON_CREDENTIAL_FINGERPRINT_PEPPER is required for credential fingerprints");
        }
        try {
            Mac mac = Mac.getInstance(FINGERPRINT_ALGORITHM);
            mac.init(pepper);
            mac.update(providerId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new EnvelopeException("credential fingerprint is unavailable");
        }
    }

    /** 启动自检：当前密钥可往返 canary；上一把密钥配置时也能解开自己包裹的 DEK。 */
    private void selfCheck() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            byte[] sealed = gcm(Cipher.ENCRYPT_MODE, current, nonce, CANARY_PLAINTEXT, CANARY_AAD);
            byte[] opened = gcm(Cipher.DECRYPT_MODE, current, nonce, sealed, CANARY_AAD);
            if (!MessageDigest.isEqual(opened, CANARY_PLAINTEXT)) {
                throw new MasterKeyConfigException(
                        CryptoErrorCodes.MASTER_KEY_CONFIG,
                        MasterKeyConfigException.Reason.CANARY_FAILED,
                        "master key canary self-check failed");
            }
            Arrays.fill(sealed, (byte) 0);
            Arrays.fill(opened, (byte) 0);
            if (previous != null) {
                byte[] previousNonce = new byte[NONCE_BYTES];
                RANDOM.nextBytes(previousNonce);
                byte[] previousSealed = gcm(Cipher.ENCRYPT_MODE, previous, previousNonce, CANARY_PLAINTEXT, CANARY_AAD);
                byte[] previousOpened = gcm(Cipher.DECRYPT_MODE, previous, previousNonce, previousSealed, CANARY_AAD);
                if (!MessageDigest.isEqual(previousOpened, CANARY_PLAINTEXT)) {
                    throw new MasterKeyConfigException(
                            CryptoErrorCodes.MASTER_KEY_CONFIG,
                            MasterKeyConfigException.Reason.CANARY_FAILED,
                            "previous master key canary self-check failed");
                }
                Arrays.fill(previousSealed, (byte) 0);
                Arrays.fill(previousOpened, (byte) 0);
            }
        } catch (GeneralSecurityException ex) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.CANARY_FAILED,
                    "AES-GCM is unavailable; refuse to start without envelope encryption");
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    /**
     * 执行一次 AES-GCM 加解密。
     *
     * @param mode {@link Cipher#ENCRYPT_MODE} 或 {@link Cipher#DECRYPT_MODE}
     * @param key 主密钥
     * @param nonce 12 字节 nonce
     * @param input 明文或密文
     * @param aad 附加认证数据
     * @return 结果
     * @throws GeneralSecurityException 算法不可用或认证失败
     */
    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] input, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    /**
     * 用指定主密钥尝试解密；失败返回 {@code null}。
     *
     * @param key 主密钥
     * @param nonce nonce
     * @param ciphertext 密文与标签
     * @param aad 附加认证数据
     * @return 明文；认证失败或参数非法时返回 {@code null}
     */
    private static byte[] tryDecrypt(SecretKey key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        if (nonce == null
                || nonce.length != NONCE_BYTES
                || ciphertext == null
                || ciphertext.length <= TAG_BYTES
                || aad == null
                || aad.length == 0) {
            return null;
        }
        try {
            return gcm(Cipher.DECRYPT_MODE, key, nonce, ciphertext, aad);
        } catch (GeneralSecurityException ex) {
            // GCM 认证失败与密钥错误不可区分；不记录底层异常，避免密文/密钥相关信息进入日志。
            return null;
        }
    }

    /**
     * 解码并校验 Base64 密钥。
     *
     * @param encoded Base64 值
     * @param envName 环境变量名，只用于错误信息
     * @param rejectTestKeys 是否拒绝已知测试密钥
     * @return AES-256 密钥
     * @throws MasterKeyConfigException 缺失、命中测试密钥、编码非法或长度错误
     */
    private static SecretKey decode(String encoded, String envName, boolean rejectTestKeys) {
        if (isBlank(encoded)) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.MASTER_KEY_MISSING,
                    envName + " is required; refuse to start without envelope encryption");
        }
        String value = encoded.trim();
        if (rejectTestKeys && KNOWN_TEST_KEYS.contains(value)) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.TEST_KEY_REJECTED,
                    envName + " is a known test key; inject a real random 32-byte key");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.MASTER_KEY_INVALID,
                    envName + " must be valid Base64; value is not logged");
        }
        if (keyBytes.length != KEY_BYTES) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.MASTER_KEY_INVALID,
                    envName + " must decode to exactly 32 bytes");
        }
        try {
            // SecretKeySpec 会复制密钥字节；随后立即清零临时数组，减少明文驻留时间。
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /** 校验主密钥标识；只允许非空且不超过 64 字符。 */
    private static String requireMasterKeyId(String masterKeyId) {
        if (masterKeyId == null || masterKeyId.isBlank() || masterKeyId.length() > 64) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.MASTER_KEY_INVALID,
                    "RICCON_MASTER_KEY_ID must be 1-64 characters");
        }
        return masterKeyId.trim();
    }

    /** 未配置占位环上的任何加密操作都必须失败关闭。 */
    private void requireConfigured() {
        if (!configured) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.NOT_CONFIGURED,
                    "master key is not configured; envelope encryption is unavailable");
        }
    }

    /** 判断字符串是否为空或全空白。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 只输出标识与配置状态；禁止把密钥材料写入日志。 */
    @Override
    public String toString() {
        return "MasterKeyRing[masterKeyId=" + masterKeyId + ", previous=" + (previous != null) + ", pepper="
                + (pepper != null) + "]";
    }

    /**
     * 被主密钥包裹的 DEK。
     *
     * @param wrappedDek 密文与认证标签
     * @param nonce 包裹 nonce
     */
    public record WrappedDek(byte[] wrappedDek, byte[] nonce) {

        /** 防御性复制，避免调用方修改包裹结果。 */
        public WrappedDek {
            Objects.requireNonNull(wrappedDek, "wrappedDek must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
            wrappedDek = wrappedDek.clone();
            nonce = nonce.clone();
        }

        /** 返回密文副本。 */
        @Override
        public byte[] wrappedDek() {
            return wrappedDek.clone();
        }

        /** 返回 nonce 副本。 */
        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
