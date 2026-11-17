package com.riccon.worker.research.domain;

import java.util.UUID;

/**
 * 产物版本文档元数据。
 *
 * <p>正文保存在对象存储，本记录只保存可校验的元数据；{@code summary} 供列表展示，不含正文原文。
 *
 * @param id 版本 ID
 * @param artifactId 所属产物
 * @param version 版本号，从 1 开始
 * @param storageKey 对象存储 key
 * @param contentType 正文 MIME 类型
 * @param sizeBytes 正文字节数
 * @param sha256 正文 SHA-256
 * @param summary 摘要
 */
public record ArtifactVersionRecord(
        UUID id,
        UUID artifactId,
        int version,
        String storageKey,
        String contentType,
        long sizeBytes,
        String sha256,
        String summary) {}
