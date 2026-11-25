package com.riccon.api.workflow.approval.dto;

import java.util.List;

/**
 * 审批待办分页（契约 {@code WorkflowApprovalPage}）。
 *
 * <p>分页字段与全局规则一致：{@code page} 从 1 开始，{@code size} 默认 20、最大 100；排序固定为
 * {@code createdAt ASC}（最早等待优先），同一时间按 ID 升序保证稳定翻页（AC-05）。空态文案由前端
 * 固定展示，接口不返回额外提示。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 审批列表
 */
public record WorkflowApprovalPageResponse(
        int page, int size, long total, int totalPages, List<WorkflowApprovalResponse> items) {}
