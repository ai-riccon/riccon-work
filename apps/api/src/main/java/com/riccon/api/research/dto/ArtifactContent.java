package com.riccon.api.research.dto;

import org.springframework.http.MediaType;

/**
 * 产物正文本体。
 *
 * <p>服务端只在内存中短暂持有正文，控制器直接写入响应；不包含对象 key、存储 URL 或任何凭据。
 *
 * @param body 正文字节
 * @param contentType 已规范化的响应 MIME 类型，仅允许 Markdown 或纯文本
 */
public record ArtifactContent(byte[] body, MediaType contentType) {}
