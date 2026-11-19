package com.riccon.api.admin.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * OIDC 身份审计哈希工具。
 *
 * <p>{@code user.oidc.bound}/{@code user.oidc.unbound} 的 details 只允许 64 位 hex 的
 * {@code issuerHash}/{@code subjectHash}，禁止写入 issuer 或 subject 原文（ADR-0019 第 3 节、
 * ADR-0020 第 6.1 节）。哈希不可逆，仅用于关联排查与去重核对。
 */
public final class OidcIdentityHashes {

    /** 工具类禁止实例化。 */
    private OidcIdentityHashes() {}

    /**
     * 计算标识的 SHA-256 小写十六进制值。
     *
     * @param value 待哈希值
     * @return 64 位小写 hex
     * @throws IllegalArgumentException 值为空
     */
    public static String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value to hash must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
