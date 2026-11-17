package com.riccon.api.chat.dto;

import java.util.List;

/**
 * 会话分页响应，对应 OpenAPI {@code ConversationPage}。
 *
 * @param page 当前页，从 1 开始
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页会话
 */
public record ConversationPageResponse(
        int page, int size, long total, int totalPages, List<ConversationResponse> items) {}
