package com.riccon.api.workflow.run.domain;

import java.util.Objects;

/**
 * 重试失败节点命令。
 *
 * @param nodeId 失败节点 key（图内唯一）
 */
public record WorkflowRunRetryCommand(String nodeId) {

    /** 校验必填字段。 */
    public WorkflowRunRetryCommand {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
    }
}
