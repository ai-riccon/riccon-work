package com.riccon.api.chat.dto;

/**
 * 修改会话标题请求，对应 OpenAPI {@code ConversationUpdateRequest}。
 *
 * @param title 新标题，1–200 字符
 */
public record ConversationUpdateRequest(String title) {}
