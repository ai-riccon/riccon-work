package com.riccon.api.chat.dto;

import java.util.List;

/**
 * 消息分页响应，对应 OpenAPI {@code MessagePage}。
 *
 * @param page 当前页，固定为 1（消息接口使用 beforeSeq 游标）
 * @param size 每页大小
 * @param total 会话消息总数
 * @param totalPages 总页数
 * @param items 当前页消息，按 seq 升序
 */
public record MessagePageResponse(int page, int size, long total, int totalPages, List<MessageResponse> items) {}
