package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ArtifactSnapshot;
import com.riccon.platform.domain.research.ArtifactStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 产物响应，对应 OpenAPI {@code Artifact}。
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
 */
public record ArtifactResponse(
        UUID id,
        UUID workspaceId,
        UUID researchTaskId,
        String kind,
        String title,
        ArtifactStatus status,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将产物快照映射为响应。
     *
     * @param artifact 产物快照
     * @return 响应
     */
    public static ArtifactResponse from(ArtifactSnapshot artifact) {
        return new ArtifactResponse(
                artifact.id(),
                artifact.workspaceId(),
                artifact.researchTaskId(),
                artifact.kind(),
                artifact.title(),
                artifact.status(),
                artifact.currentVersion(),
                artifact.createdAt(),
                artifact.updatedAt());
    }
}
