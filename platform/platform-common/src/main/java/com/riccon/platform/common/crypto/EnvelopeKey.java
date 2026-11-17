package com.riccon.platform.common.crypto;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 信封加密密钥。
 *
 * <p>只保存 32 字节 AES-256 密钥的副本与稳定 key id；构造时立即校验长度与 key id 字符集，
 * 避免把错误配置带到运行期。{@link #toString()} 不输出密钥材料。
 *
 * @param keyId 稳定密钥标识，1–64 位字母、数字、点、下划线或短横线
 * @param secret 32 字节 AES-256 密钥
 */
public record EnvelopeKey(String keyId, byte[] secret) {

    /** key id 允许的字符集；避免空白、分隔符与日志注入。 */
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * 校验并复制密钥材料。
     *
     * @throws IllegalArgumentException key id 非法或密钥长度不是 32 字节
     */
    public EnvelopeKey {
        Objects.requireNonNull(keyId, "keyId must not be null");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId must be 1-64 characters of [A-Za-z0-9._-]");
        }
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length != AesGcmEnvelope.KEY_BYTES) {
            throw new IllegalArgumentException("secret must be exactly " + AesGcmEnvelope.KEY_BYTES + " bytes");
        }
        secret = secret.clone();
    }

    /**
     * 返回密钥副本，避免调用方修改内部状态。
     *
     * @return 32 字节密钥副本
     */
    @Override
    public byte[] secret() {
        return secret.clone();
    }

    /** 只输出 key id；禁止把密钥材料写入日志。 */
    @Override
    public String toString() {
        return "EnvelopeKey[keyId=" + keyId + "]";
    }

    /** 比较密钥材料，避免数组身份比较；不用于输出。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EnvelopeKey that)) {
            return false;
        }
        return keyId.equals(that.keyId) && Arrays.equals(secret, that.secret);
    }

    /** 与 {@link #equals(Object)} 一致，哈希包含密钥材料。 */
    @Override
    public int hashCode() {
        return 31 * keyId.hashCode() + Arrays.hashCode(secret);
    }
}
