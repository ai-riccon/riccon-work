package com.riccon.api.admin.model.routing.dto;

import java.util.List;

/**
 * 模型路由更新请求（OpenAPI {@code ModelRoutingUpdateRequest}）。
 *
 * <p>{@code modelIds} 保持字符串类型，由服务层解析 UUID 并返回字段级 {@code RICCON-MODEL-400}，
 * 避免非法 UUID 被通用反序列化错误吞掉。
 *
 * @param version 客户端读到的乐观锁版本
 * @param modelIds 有序模型 ID（1..20，去重）
 */
public record ModelRoutingUpdateRequest(Integer version, List<String> modelIds) {}
