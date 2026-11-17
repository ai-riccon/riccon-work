package com.riccon.api.file.domain;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.file.FileStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件行与最近一次解析尝试的联合快照。
 *
 * <p>数据库按文件与解析尝试分表；读取时用最近一次 attempt 关联，响应只暴露业务字段，{@code storageKey}
 * 仅在服务端用于签发下载地址与删除补偿，不进入 DTO。
 *
 * @param id 文件 ID
 * @param workspaceId 所属工作空间
 * @param conversationId 上传所在会话，可为空
 * @param originalName 原始文件名
 * @param format 业务格式
 * @param mimeType 服务端嗅探后的 MIME
 * @param sizeBytes 文件大小
 * @param sha256 内容 SHA-256
 * @param storageKey 对象存储 key
 * @param status 文件状态
 * @param parse 最近一次解析尝试，可为空
 * @param createdAt 创建时间
 * @param deletedAt 软删除时间，可为空
 */
public record FileRecord(
        UUID id,
        UUID workspaceId,
        UUID conversationId,
        String originalName,
        FileFormat format,
        String mimeType,
        long sizeBytes,
        String sha256,
        String storageKey,
        FileStatus status,
        FileParseRecord parse,
        Instant createdAt,
        Instant deletedAt) {}
