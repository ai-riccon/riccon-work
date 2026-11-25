package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.history.domain.WorkflowResultFormat;
import java.time.OffsetDateTime;

/**
 * 运行最终结果（契约 {@code WorkflowResult}）。
 *
 * <p>只在运行进入终态且 {@code resultStatus=SAVED} 时返回；超过部署上限的运行以
 * {@code WF_RESULT_LIMIT} 失败、{@code text} 为空，不做静默截断（ADR-0024 §3.4）。
 *
 * @param format 结果格式
 * @param text 结果文本
 * @param sizeBytes 结果文本 UTF-8 字节数
 * @param generatedAt 生成时间（带偏移）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowResultResponse(
        WorkflowResultFormat format, String text, long sizeBytes, OffsetDateTime generatedAt) {}
