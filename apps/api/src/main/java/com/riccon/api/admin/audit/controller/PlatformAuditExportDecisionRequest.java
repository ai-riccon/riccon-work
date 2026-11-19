package com.riccon.api.admin.audit.controller;

/**
 * 平台管理员审批导出请求体（OpenAPI {@code AuditExportDecisionRequest}）。
 *
 * <p>与租户端点同构但单独声明，避免平台管理包依赖租户审计 controller 的类型；
 * 审批意见只存导出记录，不进入审计 details。
 *
 * @param decision 决定原文（{@code APPROVE}/{@code REJECT}）
 * @param comment 审批意见，可为空，最长 500
 */
public record PlatformAuditExportDecisionRequest(String decision, String comment) {}
