package com.riccon.worker.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具。
 *
 * <p>工具入参与结果只保存 SHA-256，用于审计核对与复现，不保存原文。
 */
public final class Digests {

    /** 工具类禁止实例化。 */
    private Digests() {}

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制值。
     *
     * @param value 文本
     * @return 64 位小写十六进制
     */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
