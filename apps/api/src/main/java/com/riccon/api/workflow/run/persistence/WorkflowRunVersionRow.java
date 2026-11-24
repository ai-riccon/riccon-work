package com.riccon.api.workflow.run.persistence;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.UUID;

/**
 * 用户所选发布版本行（含图快照）。
 *
 * @param id 版本 ID
 * @param workflowId 所属流程 ID
 * @param versionNo 版本号
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID
 * @param nodeCount 节点数
 * @param graphJson 规范化图 JSON
 */
public record WorkflowRunVersionRow(
        UUID id,
        UUID workflowId,
        int versionNo,
        WorkflowRunScope runScope,
        UUID departmentId,
        int nodeCount,
        String graphJson) {}
