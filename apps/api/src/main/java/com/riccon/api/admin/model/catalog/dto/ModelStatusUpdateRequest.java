package com.riccon.api.admin.model.catalog.dto;

/**
 * 模型启停请求（OpenAPI {@code ModelStatusUpdateRequest}）。
 *
 * <p>保持字符串类型，由控制器校验 {@code ACTIVE|DISABLED}，以便返回稳定 {@code RICCON-MODEL-400} 与
 * {@code field=status}，而不是通用反序列化错误。
 *
 * @param status 目标状态
 */
public record ModelStatusUpdateRequest(String status) {}
