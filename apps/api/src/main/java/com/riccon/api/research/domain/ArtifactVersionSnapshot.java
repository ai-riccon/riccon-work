package com.riccon.api.research.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产物版本快照。
 *
 * <p>正文在对象存储，本记录保存可校验的元数据与摘要；{@code storageKey} 只在服务端用于签发下载地址，
 * 不进入公开响应。
 *
 * @param id 版本 ID
 * @param artifactId 所属产物
 * @param version 版本号，从 1 开始
 * @param storageKey 对象存储 key
 * @param contentType 正文 MIME
 * @param sizeBytes 正文字节数
 * @param sha256 正文 SHA-256
 * @param summary 摘要
 * @param createdAt 创建时间
 * @param citations 引用清单
 */
public record ArtifactVersionSnapshot(
        UUID id,
        UUID artifactId,
        int version,
        String storageKey,
        String contentType,
        long sizeBytes,
        String sha256,
        String summary,
        Instant createdAt,
        List<ArtifactCitationSnapshot> citations) {

    /** 校验必填字段并复制引用列表。 */
    public ArtifactVersionSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(artifactId, "artifactId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("sizeBytes must be >= 1");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
