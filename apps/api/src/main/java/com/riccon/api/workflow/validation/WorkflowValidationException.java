package com.riccon.api.workflow.validation;

import com.riccon.api.error.RicconException;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.http.HttpStatus;

/**
 * 图校验失败，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>保存与发布在写入前调用校验器；失败时把逐项问题压缩到 {@code detail}（最多 20 项），客户端可调用
 * {@code POST /workflows/{workflowId}/validate} 获取完整结构化列表。错误说明只含稳定码、节点 ID、字段与
 * 规则说明，不回显提示词、工具参数或文档正文（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowValidationException extends RicconException {

    /** detail 中最多列出的问题数，避免错误响应过大。 */
    private static final int MAX_DETAIL_ISSUES = 20;

    /** 完整校验结果；供上层日志/测试读取，不直接序列化到 ProblemDetail。 */
    private final WorkflowValidationResult validationResult;

    /**
     * 创建校验失败异常。
     *
     * @param validationResult 校验结果；必须包含至少一个问题
     */
    public WorkflowValidationException(WorkflowValidationResult validationResult) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Workflow validation failed", detail(validationResult));
        this.validationResult = validationResult;
    }

    /**
     * 返回完整校验结果。
     *
     * @return 校验结果
     */
    public WorkflowValidationResult validationResult() {
        return validationResult;
    }

    /**
     * 构造逐项问题摘要。
     *
     * @param result 校验结果
     * @return 脱敏摘要
     */
    private static String detail(WorkflowValidationResult result) {
        List<WorkflowValidationIssue> issues = result.issues();
        StringJoiner joiner = new StringJoiner("; ");
        int limit = Math.min(issues.size(), MAX_DETAIL_ISSUES);
        for (int index = 0; index < limit; index++) {
            WorkflowValidationIssue issue = issues.get(index);
            StringBuilder part = new StringBuilder();
            part.append(issue.code().name());
            if (issue.nodeId() != null) {
                part.append(" node=").append(issue.nodeId());
            }
            if (issue.field() != null) {
                part.append(" field=").append(issue.field());
            }
            part.append(": ").append(issue.message());
            joiner.add(part);
        }
        if (issues.size() > limit) {
            joiner.add("...");
        }
        return "workflow validation failed with " + issues.size() + " issue(s): " + joiner;
    }
}
