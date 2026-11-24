package com.riccon.api.workflow.publish.domain;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布命令。
 *
 * <p>{@code runScope} 来自 {@code WorkflowPublishRequest}；{@code departmentId} 只在
 * {@code DEPARTMENT} 范围必填。角色与部门负责人限制由服务层校验，解析器只负责形状与枚举
 * （ADR-0024 §6.2/§7.3）。
 *
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID；其他范围为空
 */
public record WorkflowPublishCommand(WorkflowRunScope runScope, UUID departmentId) {

    /** 校验运行范围。 */
    public WorkflowPublishCommand {
        Objects.requireNonNull(runScope, "runScope must not be null");
    }
}
