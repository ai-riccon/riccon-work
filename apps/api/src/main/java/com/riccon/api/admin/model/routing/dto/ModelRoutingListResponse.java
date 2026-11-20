package com.riccon.api.admin.model.routing.dto;

import java.util.List;

/**
 * 两条路由链响应（OpenAPI {@code ModelRoutingList}）。
 *
 * <p>固定返回 CHAT、REPORT 两条链；管理端不需要分页。
 *
 * @param items 两条链，顺序为 CHAT、REPORT
 */
public record ModelRoutingListResponse(List<ModelRoutingResponse> items) {}
