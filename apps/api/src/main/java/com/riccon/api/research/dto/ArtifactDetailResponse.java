package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.api.research.domain.ArtifactVersionSnapshot;
import com.riccon.platform.domain.research.ArtifactStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 产物详情响应，对应 OpenAPI {@code ArtifactDetail}。
 *
 * @param id 产物 ID
 * @param workspaceId 工作空间 ID
 * @param researchTaskId 报告任务 ID
 * @param kind 产物类别
 * @param title 标题
 * @param status 状态
 * @param currentVersion 当前版本号
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param currentVersionDetail 当前版本与引用；尚无正文时为 {@code null}
 */
public record ArtifactDetailResponse(
        UUID id,
        UUID workspaceId,
        UUID researchTaskId,
        String kind,
        String title,
        ArtifactStatus status,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt,
        ArtifactVersionResponse currentVersionDetail) {

    /**
     * 将产物与当前版本映射为详情响应。
     *
     * @param artifact 产物快照
     * @param currentVersion 当前版本；没有正文时为 {@code null}
     * @return 响应
     */
    public static ArtifactDetailResponse from(ArtifactSnapshot artifact, ArtifactVersionSnapshot currentVersion) {
        return new ArtifactDetailResponse(
                artifact.id(),
                artifact.workspaceId(),
                artifact.researchTaskId(),
                artifact.kind(),
                artifact.title(),
                artifact.status(),
                artifact.currentVersion(),
                artifact.createdAt(),
                artifact.updatedAt(),
                currentVersion == null ? null : ArtifactVersionResponse.from(currentVersion));
    }
}
