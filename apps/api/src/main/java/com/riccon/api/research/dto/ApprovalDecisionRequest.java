package com.riccon.api.research.dto;

import com.riccon.platform.domain.research.ApprovalDecision;

/**
 * 审批决定请求，对应 OpenAPI {@code ApprovalDecisionRequest}。
 *
 * @param decision 批准或拒绝
 * @param comment 审批备注，最长 500 字符
 */
public record ApprovalDecisionRequest(ApprovalDecision decision, String comment) {}
