package com.riccon.api.workspace.dto;

import com.riccon.platform.domain.workspace.WorkspaceVisibility;

/**
 * 修改工作空间请求，对应 OpenAPI {@code WorkspaceUpdateRequest}。
 *
 * <p>字段为空表示不修改。可见性只允许在 {@code DEPT}/{@code CUSTOM} 之间切换；历史 {@code TENANT} 空间
 * 不可转换（ADR-0018 第 7.2 节）。说明需要清空时传空字符串，统一归一为 {@code null}。
 *
 * @param name 新名称，可为空
 * @param description 新说明，可为空
 * @param visibility 新可见性，可为空
 */
public record WorkspaceUpdateRequest(String name, String description, WorkspaceVisibility visibility) {}
