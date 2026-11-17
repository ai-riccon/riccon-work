package com.riccon.api.chat.dto;

/**
 * 新建会话请求，对应 OpenAPI {@code ConversationCreateRequest}。
 *
 * @param title 可选标题；为空时由首条消息自动生成
 */
public record ConversationCreateRequest(String title) {}
