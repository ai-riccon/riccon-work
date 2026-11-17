package com.riccon.api.knowledge.dto;

import java.util.List;

/**
 * 知识库文档分页响应，对应 OpenAPI {@code KnowledgeDocumentPage}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页文档
 */
public record KnowledgeDocumentPageResponse(
        int page, int size, long total, int totalPages, List<KnowledgeDocumentResponse> items) {}
