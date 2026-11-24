package com.riccon.api.workflow.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.workflow.WorkflowValidationCode;
import java.util.List;
import java.util.Objects;

/**
 * 图校验结果（契约 {@code WorkflowValidationResult}）。
 *
 * <p>{@code valid} 与 {@code issues} 必须一致：无问题即可保存/发布，存在问题则逐项返回。契约未定义独立的
 * {@code canPublish} 字段（{@code additionalProperties: false}），因此 {@code valid=true} 就是当前阶段可保存/
 * 可发布的判据；画布与列表/表单读取同一份结果（AC-02、ADR-0024 §11.1）。
 *
 * @param valid 是否通过
 * @param issues 逐项问题；通过时为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowValidationResult(boolean valid, List<WorkflowValidationIssue> issues) {

    /** 校验一致性并复制问题列表。 */
    public WorkflowValidationResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
        if (valid != issues.isEmpty()) {
            throw new IllegalArgumentException("valid must equal issues.isEmpty()");
        }
    }

    /**
     * 构造通过结果。
     *
     * @return 通过结果
     */
    public static WorkflowValidationResult passed() {
        return new WorkflowValidationResult(true, List.of());
    }

    /**
     * 构造有问题结果。
     *
     * @param issues 逐项问题
     * @return 未通过结果
     */
    public static WorkflowValidationResult invalid(List<WorkflowValidationIssue> issues) {
        Objects.requireNonNull(issues, "issues must not be null");
        if (issues.isEmpty()) {
            throw new IllegalArgumentException("issues must not be empty for an invalid result");
        }
        return new WorkflowValidationResult(false, issues);
    }

    /**
     * 判断是否存在指定问题码。
     *
     * @param code 问题码
     * @return 存在时返回 {@code true}
     */
    @JsonIgnore
    public boolean hasIssue(WorkflowValidationCode code) {
        return issues.stream().anyMatch(issue -> issue.code() == code);
    }
}
