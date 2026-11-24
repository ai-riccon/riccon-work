package com.riccon.api.workflow.run.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 运行准入并发超限，HTTP 429 {@code RICCON-WF-429}。
 *
 * <p>租户并发（默认 10）与单流程并发（默认 5）都包含 {@code PENDING/RUNNING/WAITING_APPROVAL}；
 * 说明只暴露限制值、当前值与等待建议，不泄露其他运行的内容（ADR-0024 §4.8/§10.1，AC-17）。
 */
public class WorkflowRunRateLimitedException extends RicconException {

    /**
     * 创建并发超限异常。
     *
     * @param scope {@code tenant} 或 {@code workflow}
     * @param limit 当前生效上限
     * @param current 当前活跃运行数
     */
    public WorkflowRunRateLimitedException(String scope, int limit, long current) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "RICCON-WF-429",
                "Workflow run limit exceeded",
                scope + " active run limit reached (limit=" + limit + ", current=" + current
                        + "); wait for an active run to finish before retrying");
    }
}
