package com.riccon.api.audit.controller;

/**
 * 审批导出请求体（OpenAPI {@code AuditExportDecisionRequest}）。
 *
 * <p>审批意见只存 {@code audit_export_request.decision_comment}，不进入审计 details。
 *
 * @param decision 决定原文（{@code APPROVE}/{@code REJECT}）
 * @param comment 审批意见，可为空，最长 500
 */
public record AuditExportDecisionRequest(String decision, String comment) {}
