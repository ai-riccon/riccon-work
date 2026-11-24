package com.riccon.api.workflow.definition.dto;

import java.util.List;

/**
 * 流程分页（契约 {@code WorkflowDefinitionPage}）。
 *
 * <p>分页字段与全局规则一致：{@code page} 从 1 开始，{@code size} 最大 100。列表只包含调用者可见的流程，
 * 不泄露不可见对象的名称或数量（ADR-0024 §6.2）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 流程列表
 */
public record WorkflowDefinitionPageResponse(
        int page, int size, long total, int totalPages, List<WorkflowDefinitionResponse> items) {}
