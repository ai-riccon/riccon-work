package com.riccon.api.workflow.run.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 创建运行命令。
 *
 * <p>{@code inputsJson} 是已经服务端校验并规范化后的 JSON 文本（≤64 KiB、最多 16 个顶层键）；
 * 使用文本而不是 Jackson 树，避免请求对象在事务和审计链上被意外修改（ADR-0024 §3.3.3）。
 *
 * @param versionId 用户选择的发布版本；为空时绑定最近发布版本
 * @param inputsJson 规范化运行输入 JSON
 */
public record WorkflowRunCreateCommand(UUID versionId, String inputsJson) {

    /** 校验必填字段。 */
    public WorkflowRunCreateCommand {
        Objects.requireNonNull(inputsJson, "inputsJson must not be null");
    }
}
