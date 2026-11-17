package com.riccon.api.research.domain;

import com.riccon.platform.domain.research.ArtifactStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告产物快照。
 *
 * <p>一个报告任务最多一个产物；重试沿用同一产物并递增版本，避免重复产物（V4 迁移注释、M02-AC-11）。
 *
 * @param id 产物 ID
 * @param workspaceId 所属工作空间
 * @param researchTaskId 来源报告任务
 * @param kind 产物类别，M02 固定 REPORT
 * @param title 标题
 * @param status 状态
 * @param currentVersion 当前版本号；0 表示尚无正文
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ArtifactSnapshot(
        UUID id,
        UUID workspaceId,
        UUID researchTaskId,
        String kind,
        String title,
        ArtifactStatus status,
        int currentVersion,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验必填字段。 */
    public ArtifactSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must be >= 0");
        }
    }
}
