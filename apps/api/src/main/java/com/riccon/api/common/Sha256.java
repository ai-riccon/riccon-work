package com.riccon.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 十六进制摘要工具。
 *
 * <p>文件校验和、对象 key 指纹与审计 details 都需要稳定的 64 位小写十六进制；统一放在这里，避免各处
 * 自行实现摘要格式导致数据库 CHECK 或审计字段不一致。
 */
public final class Sha256 {

    /** 工具类禁止实例化。 */
    private Sha256() {}

    /**
     * 计算字节内容的 SHA-256。
     *
     * @param content 字节内容
     * @return 64 位小写十六进制摘要
     */
    public static String hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 SHA-256；缺失属于运行环境损坏，不能降级为无校验。
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 计算 UTF-8 文本的 SHA-256。
     *
     * @param content 文本内容
     * @return 64 位小写十六进制摘要
     */
    public static String hex(String content) {
        return hex(content.getBytes(StandardCharsets.UTF_8));
    }
}
