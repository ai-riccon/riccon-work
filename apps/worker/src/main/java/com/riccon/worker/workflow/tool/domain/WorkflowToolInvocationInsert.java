package com.riccon.worker.workflow.tool.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作流工具调用 STARTED 插入参数。
 *
 * <p>唯一键 {@code (tenant_id, workflow_approval_id)} 是“一个审批最多执行一次”的数据库兜底；
 * 插入成功后外部调用才允许开始。只存哈希、脱敏预览与时间，不存参数/结果原文。
 *
 * @param id 调用 ID（UUIDv7）
 * @param workflowRunId 运行 ID
 * @param approvalId 绑定的审批 ID
 * @param nodeKey 工具节点 key
 * @param attempt 节点尝试号
 * @param toolId 工具 ID 快照
 * @param toolName 工具名快照
 * @param paramsSha256 规范化参数摘要
 * @param paramsPreview 脱敏参数摘要 JSON
 * @param startedAt 开始时间（UTC）
 */
public record WorkflowToolInvocationInsert(
        UUID id,
        UUID workflowRunId,
        UUID approvalId,
        String nodeKey,
        int attempt,
        UUID toolId,
        String toolName,
        String paramsSha256,
        String paramsPreview,
        Instant startedAt) {

    /** 校验必填字段。 */
    public WorkflowToolInvocationInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(paramsSha256, "paramsSha256 must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
