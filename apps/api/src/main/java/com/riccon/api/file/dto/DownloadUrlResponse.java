package com.riccon.api.file.dto;

import java.time.Instant;

/**
 * 文件下载地址响应，对应 OpenAPI {@code DownloadUrl}。
 *
 * @param url 预签名 URL
 * @param expiresAt 过期时间
 */
public record DownloadUrlResponse(String url, Instant expiresAt) {}
