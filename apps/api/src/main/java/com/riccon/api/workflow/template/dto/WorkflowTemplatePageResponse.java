package com.riccon.api.workflow.template.dto;

import java.util.List;

/**
 * 模板分页（契约 {@code WorkflowTemplatePage}）。
 *
 * <p>分页字段与全局规则一致：{@code page} 从 1 开始，{@code size} 默认 20、最大 100；排序固定为最近更新
 * 时间倒序、ID 升序（契约未声明排序参数）。M06 只包含内置模板，用户模板与共享在 M06b 追加。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总数
 * @param totalPages 总页数
 * @param items 模板列表
 */
public record WorkflowTemplatePageResponse(
        int page, int size, long total, int totalPages, List<WorkflowTemplateResponse> items) {}
