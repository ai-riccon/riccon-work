package com.riccon.api.workflow.history.dto;

import java.util.List;

/**
 * 运行历史分页（契约 {@code WorkflowRunPage}）。
 *
 * <p>分页字段与全局规则一致：{@code page} 从 1 开始，{@code size} 最大 100；列表不返回不可见对象的
 * 名称或数量（ADR-0024 §6.2）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 运行列表
 */
public record WorkflowRunHistoryPageResponse(
        int page, int size, long total, int totalPages, List<WorkflowRunHistoryItemResponse> items) {}
