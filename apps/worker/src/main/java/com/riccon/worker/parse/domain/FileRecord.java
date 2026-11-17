package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.file.FileFormat;
import java.util.UUID;

/**
 * 文件元数据快照。
 *
 * <p>只包含解析所需字段；对象 key 由服务端生成，Worker 读取前再次校验租户前缀。
 *
 * @param id 文件 ID
 * @param originalName 原始文件名（仅展示与元数据，不进入路径）
 * @param format 文件格式
 * @param mimeType MIME 类型
 * @param sizeBytes 文件大小
 * @param storageKey 对象存储 key
 */
public record FileRecord(
        UUID id, String originalName, FileFormat format, String mimeType, long sizeBytes, String storageKey) {}
