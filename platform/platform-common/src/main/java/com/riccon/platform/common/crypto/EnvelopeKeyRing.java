package com.riccon.platform.common.crypto;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 信封密钥环。
 *
 * <p>保存当前写密钥与轮换窗口内的历史密钥，按 key id 解析；构造后不可变。轮换期允许同时存在
 * 当前与上一把密钥：新写入使用 {@link #current()}，读取按密文记录的 key id 选择历史密钥。
 * 未知 key id 直接失败，禁止尝试“所有密钥逐个试”或降级明文。
 */
public final class EnvelopeKeyRing {

    /** 按 key id 索引的密钥。 */
    private final Map<String, EnvelopeKey> keys;

    /** 当前写密钥。 */
    private final EnvelopeKey current;

    /**
     * 创建密钥环。
     *
     * @param currentKeyId 当前写密钥 ID，必须存在于 keys
     * @param keys 当前与轮换窗口内的密钥；key id 不得重复
     * @throws IllegalArgumentException 密钥集合为空、当前密钥缺失或 key id 重复
     */
    public EnvelopeKeyRing(String currentKeyId, Collection<EnvelopeKey> keys) {
        Objects.requireNonNull(currentKeyId, "currentKeyId must not be null");
        Objects.requireNonNull(keys, "keys must not be null");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one envelope key is required");
        }
        Map<String, EnvelopeKey> indexed = new LinkedHashMap<>();
        for (EnvelopeKey key : keys) {
            Objects.requireNonNull(key, "keys must not contain null");
            EnvelopeKey previous = indexed.putIfAbsent(key.keyId(), key);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate envelope key id: " + key.keyId());
            }
        }
        EnvelopeKey resolved = indexed.get(currentKeyId);
        if (resolved == null) {
            throw new IllegalArgumentException("current envelope key id is not present: " + currentKeyId);
        }
        this.keys = Map.copyOf(indexed);
        this.current = resolved;
    }

    /**
     * 返回当前写密钥。
     *
     * @return 当前密钥
     */
    public EnvelopeKey current() {
        return current;
    }

    /**
     * 按 key id 解析密钥。
     *
     * @param keyId 密文记录的 key id
     * @return 对应密钥
     * @throws EnvelopeException key id 未知或已退役
     */
    public EnvelopeKey require(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new EnvelopeException("envelope key id is not available");
        }
        EnvelopeKey key = keys.get(keyId);
        if (key == null) {
            throw new EnvelopeException("envelope key id is not available");
        }
        return key;
    }

    /**
     * 返回已加载的 key id 集合，供运维诊断；不含密钥材料。
     *
     * @return 不可变 key id 集合
     */
    public Set<String> keyIds() {
        return keys.keySet();
    }
}
