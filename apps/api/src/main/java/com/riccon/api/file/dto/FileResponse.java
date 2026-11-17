package com.riccon.api.file.dto;

import com.riccon.api.file.domain.FileRecord;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.file.FileStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件响应，对应 OpenAPI {@code File}。
 *
 * @param id 文件 ID
 * @param workspaceId 所属工作空间
 * @param conversationId 上传所在会话
 * @param originalName 原始文件名
 * @param format 业务格式
 * @param mimeType 服务端嗅探后的 MIME
 * @param sizeBytes 文件大小
 * @param sha256 内容 SHA-256
 * @param status 文件状态
 * @param parse 最近一次解析尝试
 * @param createdAt 创建时间
 * @param deletedAt 软删除时间
 */
public record FileResponse(
        UUID id,
        UUID workspaceId,
        UUID conversationId,
        String originalName,
        FileFormat format,
        String mimeType,
        long sizeBytes,
        String sha256,
        FileStatus status,
        FileParseResponse parse,
        Instant createdAt,
        Instant deletedAt) {

    /**
     * 映射文件快照；不暴露对象存储 key。
     *
     * @param file 文件快照
     * @return 响应
     */
    public static FileResponse from(FileRecord file) {
        return new FileResponse(
                file.id(),
                file.workspaceId(),
                file.conversationId(),
                file.originalName(),
                file.format(),
                file.mimeType(),
                file.sizeBytes(),
                file.sha256(),
                file.status(),
                FileParseResponse.from(file.parse()),
                file.createdAt(),
                file.deletedAt());
    }
}
