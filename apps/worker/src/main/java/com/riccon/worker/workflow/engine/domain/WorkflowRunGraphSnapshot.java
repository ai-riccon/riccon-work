package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.UUID;

/**
 * 运行与发布图快照（引擎启动读取）。
 *
 * <p>查询用 {@code (tenant_id, workflow_version_id, workflow_id)} 复合绑定校验版本属于运行；
 * MySQL JSON 列读取会规范化键序/空白，因此不重算 {@code graph_sha256}，改为校验
 * {@code node_count} 与解析后的节点数一致，防止截断或错绑（ADR-0024 §4.1）。
 *
 * @param runId 运行 ID
 * @param workflowId 流程 ID
 * @param workflowVersionId 版本 ID
 * @param requesterUserId 发起人（检索 ACL 主体）
 * @param status 运行状态
 * @param inputsJson 运行输入 JSON
 * @param currentNodeKey 当前节点 key
 * @param executionMs 累计执行时间（不含审批等待）
 * @param versionNodeCount 发布版本节点数
 * @param graphJson 发布图 JSON
 */
public record WorkflowRunGraphSnapshot(
        UUID runId,
        UUID workflowId,
        UUID workflowVersionId,
        UUID requesterUserId,
        WorkflowRunStatus status,
        String inputsJson,
        String currentNodeKey,
        long executionMs,
        int versionNodeCount,
        String graphJson) {}
