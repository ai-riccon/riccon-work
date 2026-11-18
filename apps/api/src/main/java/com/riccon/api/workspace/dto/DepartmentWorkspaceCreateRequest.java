package com.riccon.api.workspace.dto;

import com.riccon.platform.domain.workspace.WorkspaceVisibility;

/**
 * 在部门下创建空间请求，对应 OpenAPI {@code DepartmentWorkspaceCreateRequest}。
 *
 * <p>部门来自路径参数，请求体不携带 {@code departmentId}，避免负责人伪造归属；可见性只允许
 * {@code DEPT}/{@code CUSTOM}（ADR-0018 第 7.2 节）。
 *
 * @param name 名称，1–200 字符
 * @param description 说明，可为空，最长 1000 字符
 * @param visibility 可见性；为空时默认 {@code DEPT}
 */
public record DepartmentWorkspaceCreateRequest(String name, String description, WorkspaceVisibility visibility) {}
