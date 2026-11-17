package com.riccon.worker.research.domain;

import java.util.UUID;

/**
 * 报告产物快照。
 *
 * <p>一个报告任务最多一个产物（唯一键 {@code (tenant_id, research_task_id)}）；重试沿用同一产物并递增
 * {@code currentVersion}，避免产生重复产物（M02-AC-11）。
 *
 * @param id 产物 ID
 * @param workspaceId 所属工作空间
 * @param researchTaskId 来源报告任务
 * @param title 产物标题
 * @param currentVersion 当前版本号；0 表示尚未写入正文
 */
public record ArtifactRecord(UUID id, UUID workspaceId, UUID researchTaskId, String title, int currentVersion) {}
