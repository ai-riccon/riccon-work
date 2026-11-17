package com.riccon.api.chat.service;

/**
 * 单个 SSE 帧。
 *
 * @param id 可选的 {@code id:} 字段
 * @param event 事件类型
 * @param data 拼接后的 {@code data:} 内容
 */
public record SseFrame(String id, String event, String data) {}
