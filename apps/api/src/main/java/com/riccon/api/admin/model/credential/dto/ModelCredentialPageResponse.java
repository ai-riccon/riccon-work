package com.riccon.api.admin.model.credential.dto;

import java.util.List;

/**
 * 平台模型凭据分页（OpenAPI {@code ModelCredentialPage}）。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 总行数
 * @param totalPages 总页数
 * @param items 当前页的掩码凭据
 */
public record ModelCredentialPageResponse(
        int page, int size, long total, int totalPages, List<ModelCredentialResponse> items) {}
