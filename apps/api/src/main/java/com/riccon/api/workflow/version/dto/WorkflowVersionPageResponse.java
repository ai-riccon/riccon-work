package com.riccon.api.workflow.version.dto;

import com.riccon.api.workflow.definition.dto.WorkflowVersionSummaryResponse;
import java.util.List;

/**
 * 版本分页（契约 {@code WorkflowVersionPage}）。
 *
 * <p>分页字段与全局规则一致：{@code page} 从 1 开始，{@code size} 最大 100；版本按版本号倒序，
 * 旧版本仍可运行，除非流程被停用（ADR-0024 §7.2/§7.4）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 版本列表
 */
public record WorkflowVersionPageResponse(
        int page, int size, long total, int totalPages, List<WorkflowVersionSummaryResponse> items) {}
