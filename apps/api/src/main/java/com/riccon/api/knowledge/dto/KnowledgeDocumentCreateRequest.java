package com.riccon.api.knowledge.dto;

import java.util.UUID;

/**
 * 将已上传文件登记为知识库文档，对应 OpenAPI {@code KnowledgeDocumentCreateRequest}。
 *
 * @param title 标题，1–300 字符
 * @param fileId 来源文件 ID
 */
public record KnowledgeDocumentCreateRequest(String title, UUID fileId) {}
