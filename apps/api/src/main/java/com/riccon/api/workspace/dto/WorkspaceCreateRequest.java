package com.riccon.api.workspace.dto;

import com.riccon.platform.domain.workspace.WorkspaceVisibility;
import java.util.UUID;

/**
 * 创建团队工作空间请求，对应 OpenAPI {@code WorkspaceCreateRequest}。
 *
 * <p>M03 起必须提供 {@code departmentId}，可见性只允许 {@code DEPT}/{@code CUSTOM}（默认 {@code DEPT}）；
 * {@code TENANT} 只用于 M02 历史空间，禁止新建（ADR-0018 第 2.1/7.2 节）。
 *
 * @param name 名称，1–200 字符
 * @param description 说明，可为空，最长 1000 字符
 * @param departmentId 所属部门 ID
 * @param visibility 可见性；为空时默认 {@code DEPT}
 */
public record WorkspaceCreateRequest(
        String name, String description, UUID departmentId, WorkspaceVisibility visibility) {}
