package com.riccon.api.knowledge.connector.dto;

import com.riccon.api.knowledge.connector.domain.ConnectorItemRecord;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 连接器同步条目响应（OpenAPI {@code ConnectorItem}）。
 *
 * <p>{@code externalId} 只返回末两段路径或最后 32 字符，完整 key 只在库内用于比对；不返回原文、bucket
 * 或端点信息（ADR-0023 第 6.5 节）。
 *
 * @param id 条目 ID
 * @param externalId 展示值
 * @param externalVersion ETag/version
 * @param status 同步状态
 * @param documentId 对应文档
 * @param generation 代数
 * @param errorCode 失败码
 * @param lastSeenAt 最近出现时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param deletedAt 删除时间
 */
public record ConnectorItemResponse(
        UUID id,
        String externalId,
        String externalVersion,
        ConnectorItemStatus status,
        UUID documentId,
        int generation,
        String errorCode,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** 展示值最大长度。 */
    private static final int MAX_DISPLAY_LENGTH = 32;

    /**
     * 映射条目响应。
     *
     * @param item 条目快照
     * @return 响应
     */
    public static ConnectorItemResponse from(ConnectorItemRecord item) {
        return new ConnectorItemResponse(
                item.id(),
                displayExternalId(item.externalId()),
                item.externalVersion(),
                item.status(),
                item.documentId(),
                item.generation(),
                item.errorCode(),
                item.lastSeenAt(),
                item.createdAt(),
                item.updatedAt(),
                item.deletedAt());
    }

    /**
     * 生成 externalId 展示值：优先最后两段路径，再截断到 32 字符。
     *
     * @param externalId 完整 key
     * @return 展示值；空值返回空串
     */
    static String displayExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return "";
        }
        String value = externalId;
        int first = value.lastIndexOf('/');
        if (first > 0) {
            int second = value.lastIndexOf('/', first - 1);
            value = second >= 0 ? value.substring(second + 1) : value.substring(first + 1);
        }
        if (value.length() > MAX_DISPLAY_LENGTH) {
            value = value.substring(value.length() - MAX_DISPLAY_LENGTH);
        }
        return value;
    }
}
