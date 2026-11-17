package com.riccon.api.identity.dto;

/**
 * CSRF 令牌响应。
 *
 * @param headerName 固定为 {@code X-CSRF-TOKEN}
 * @param token 令牌值，至少 16 位
 */
public record CsrfTokenResponse(String headerName, String token) {}
