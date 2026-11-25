package com.riccon.api.workflow.history.dto;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.UUID;

/**
 * 审批数据范围摘要（契约 {@code WorkflowDataScopeSummary}）。
 *
 * <p>只包含运行范围、部门与去重文档引用计数；不返回文档标题、片段、定位或正文，审批人按 M05 ACL
 * 自行访问（ADR-0024 §3.3.5、AC-05/18）。
 *
 * @param runScope 运行绑定版本的范围
 * @param departmentId 部门范围 ID；非 DEPARTMENT 为空
 * @param documentReferenceCount 去重引用计数
 */
public record WorkflowDataScopeSummaryResponse(
        WorkflowRunScope runScope, UUID departmentId, int documentReferenceCount) {

    /** 校验计数非负。 */
    public WorkflowDataScopeSummaryResponse {
        if (documentReferenceCount < 0) {
            throw new IllegalArgumentException("documentReferenceCount must be non-negative");
        }
    }
}
