package com.riccon.api.oidc.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 会话 ID 哈希工具。
 *
 * <p>审计只允许保存会话 ID 的 SHA-256（ADR-0007 白名单）；与内置账号登录使用同一口径，便于关联排查。
 */
public final class SessionIdHashes {

    /** 无会话时的占位值。 */
    private static final String NONE = "none";

    /** 工具类禁止实例化。 */
    private SessionIdHashes() {}

    /**
     * 计算会话 ID 的 SHA-256 十六进制值。
     *
     * @param sessionId 会话 ID；可为空
     * @return 64 位小写 hex；无会话时返回 {@code none}
     */
    public static String sha256Hex(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return NONE;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
