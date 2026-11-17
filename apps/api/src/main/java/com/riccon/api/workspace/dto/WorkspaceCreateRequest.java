package com.riccon.api.workspace.dto;

/**
 * 创建团队工作空间请求，对应 OpenAPI {@code WorkspaceCreateRequest}。
 *
 * @param name 名称，1–200 字符
 * @param description 说明，可为空，最长 1000 字符
 */
public record WorkspaceCreateRequest(String name, String description) {}
