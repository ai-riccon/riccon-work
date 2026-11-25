package com.riccon.api.workflow.approval.dto;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.UUID;

/**
 * 审批数据范围摘要（契约 {@code WorkflowDataScopeSummary}）。
 *
 * <p>只含运行范围、部门与引用计数，不含文档标题、片段、正文或对象 key；审批人需要正文时按 M05 文档 ACL
 * 自行访问（ADR-0024 第 5.3 节，AC-05/18）。
 *
 * @param runScope 运行绑定版本的发布范围
 * @param departmentId 部门范围 ID；非 DEPARTMENT 为空
 * @param documentReferenceCount 审批节点上游已执行节点引用的去重文档数
 */
public record WorkflowDataScopeSummaryResponse(
        WorkflowRunScope runScope, UUID departmentId, int documentReferenceCount) {}
