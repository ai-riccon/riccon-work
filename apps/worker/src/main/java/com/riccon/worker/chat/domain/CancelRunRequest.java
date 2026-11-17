package com.riccon.worker.chat.domain;

/**
 * 取消运行请求体。
 *
 * @param reason 取消原因；为空时按 USER_CANCELLED 处理
 */
public record CancelRunRequest(String reason) {}
