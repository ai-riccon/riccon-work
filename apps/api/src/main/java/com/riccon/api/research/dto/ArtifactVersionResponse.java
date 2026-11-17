package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 产物版本响应，对应 OpenAPI {@code ArtifactVersion}。
 *
 * @param id 版本 ID
 * @param version 版本号
 * @param contentType 正文 MIME
 * @param sizeBytes 正文字节数
 * @param sha256 正文 SHA-256
 * @param summary 摘要
 * @param createdAt 创建时间
 * @param citations 引用清单
 */
public record ArtifactVersionResponse(
        UUID id,
        int version,
        String contentType,
        long sizeBytes,
        String sha256,
        String summary,
        Instant createdAt,
        List<ArtifactCitationResponse> citations) {

    /**
     * 将版本快照映射为响应。
     *
     * @param version 版本快照
     * @return 响应
     */
    public static ArtifactVersionResponse from(ArtifactVersionSnapshot version) {
        return new ArtifactVersionResponse(
                version.id(),
                version.version(),
                version.contentType(),
                version.sizeBytes(),
                version.sha256(),
                version.summary(),
                version.createdAt(),
                version.citations().stream().map(ArtifactCitationResponse::from).toList());
    }
}
