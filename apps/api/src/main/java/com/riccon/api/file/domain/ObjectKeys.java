package com.riccon.api.file.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 对象存储 key 生成与租户前缀校验。
 *
 * <p>ADR-0012 固定格式 {@code tenants/{tenantId}/file/{yyyy}/{objectId}}；客户端不能传入或修改 key。
 * 存储适配器在每次写/读前再次校验前缀，形成第二道防线。
 */
public final class ObjectKeys {

    /** 文件对象类别。 */
    private static final String FILE_CATEGORY = "file";

    /** 工具类禁止实例化。 */
    private ObjectKeys() {}

    /**
     * 生成文件对象 key。
     *
     * @param tenantId 租户代码
     * @param fileId 文件 ID（同时作为 objectId）
     * @param createdAt 创建时间，用于确定 UTC 年份
     * @return 对象 key
     */
    public static String fileKey(String tenantId, UUID fileId, Instant createdAt) {
        int year = createdAt.atZone(ZoneOffset.UTC).getYear();
        return "tenants/" + tenantId + "/" + FILE_CATEGORY + "/" + year + "/" + fileId;
    }

    /**
     * 校验 key 属于指定租户。
     *
     * @param tenantId 租户代码
     * @param key 对象 key
     * @throws IllegalArgumentException key 为空或缺少租户前缀
     */
    public static void requireTenantPrefix(String tenantId, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("storage key must not be blank");
        }
        String prefix = "tenants/" + tenantId + "/";
        if (!key.startsWith(prefix)) {
            throw new IllegalArgumentException("storage key is outside tenant prefix");
        }
    }
}
