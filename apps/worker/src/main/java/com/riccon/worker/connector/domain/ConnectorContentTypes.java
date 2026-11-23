package com.riccon.worker.connector.domain;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 连接器对象类型白名单、MIME 映射与对象 key 派生。
 *
 * <p>只允许 {@code pdf,docx,xlsx,png,jpg,jpeg,txt,md}；其他类型不下载、记为 {@code SKIPPED}。对象 key
 * 固定为 {@code tenants/{tenantId}/connector/{yyyy}/{documentId}}，由服务端派生，不信任源端 key。
 */
public final class ConnectorContentTypes {

    /** 部署白名单。 */
    public static final List<String> DEFAULT_EXTENSIONS =
            List.of("pdf", "docx", "xlsx", "png", "jpg", "jpeg", "txt", "md");

    /** 扩展名到 MIME。 */
    private static final Map<String, String> MIME_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "txt", "text/plain",
            "md", "text/markdown");

    /** 工具类禁止实例化。 */
    private ConnectorContentTypes() {}

    /**
     * 提取小写扩展名；没有扩展名返回空串。
     *
     * @param key 对象 key
     * @return 扩展名
     */
    public static String extension(String key) {
        if (key == null) {
            return "";
        }
        String name = key;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断扩展名是否在白名单（配置为空时使用部署白名单）。
     *
     * @param extension 扩展名
     * @param configured 连接器配置
     * @return 允许时返回 true
     */
    public static boolean supported(String extension, List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_EXTENSIONS.contains(extension);
        }
        return configured.stream()
                .anyMatch(value ->
                        value != null && value.trim().toLowerCase(Locale.ROOT).equals(extension));
    }

    /**
     * 返回 MIME；未知类型回退为八位字节流。
     *
     * @param extension 扩展名
     * @return MIME
     */
    public static String mimeType(String extension) {
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /**
     * 从对象 key 生成文档标题（最后一段，最多 300 字符）。
     *
     * @param key 对象 key
     * @return 标题
     */
    public static String title(String key) {
        String name = key == null ? "" : key;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            name = "connector-document";
        }
        return name.length() <= 300 ? name : name.substring(name.length() - 300);
    }

    /**
     * 派生连接器对象 key。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param createdAt 创建时间
     * @return 对象 key
     */
    public static String objectKey(String tenantId, UUID documentId, java.time.Instant createdAt) {
        int year = createdAt.atZone(ZoneOffset.UTC).getYear();
        return "tenants/" + tenantId + "/connector/" + year + "/" + documentId;
    }
}
