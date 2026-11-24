package com.riccon.api.workflow.run.persistence;

import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import java.util.UUID;

/**
 * 节点运行最新尝试行。
 *
 * @param id 节点运行 ID
 * @param nodeKey 节点 key
 * @param nodeType 节点类型
 * @param nodeName 节点名
 * @param attempt 尝试号
 * @param status 节点状态
 */
public record WorkflowRunNodeRow(
        UUID id, String nodeKey, WorkflowNodeType nodeType, String nodeName, int attempt, WorkflowNodeStatus status) {}
