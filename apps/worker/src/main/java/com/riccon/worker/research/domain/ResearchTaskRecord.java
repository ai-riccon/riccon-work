package com.riccon.worker.research.domain;

import com.riccon.platform.domain.research.ResearchTaskStatus;
import java.util.UUID;

/**
 * 报告任务快照。
 *
 * @param id 任务 ID
 * @param workspaceId 所属工作空间
 * @param topic 调研主题
 * @param status 状态
 * @param mcpToolId 选择的工具 ID
 * @param createdBy 创建人
 */
public record ResearchTaskRecord(
        UUID id, UUID workspaceId, String topic, ResearchTaskStatus status, UUID mcpToolId, UUID createdBy) {}
