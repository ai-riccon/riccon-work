package com.riccon.worker.run.domain;

import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import java.util.UUID;

/**
 * 运行详情快照。
 *
 * <p>包含服务令牌校验与 Agent 恢复所需的关联 ID；所有字段均来自 {@code agent_run}，不读取业务正文。
 *
 * @param id 运行 ID
 * @param kind 运行类别
 * @param status 状态
 * @param attempt 当前尝试号
 * @param maxAttempts 最大尝试号
 * @param conversationId 会话 ID
 * @param researchTaskId 报告任务 ID
 * @param userId 发起人
 * @param traceId 追踪 ID
 */
public record RunDetail(
        UUID id,
        RunKind kind,
        RunStatus status,
        int attempt,
        int maxAttempts,
        UUID conversationId,
        UUID researchTaskId,
        UUID userId,
        String traceId) {

    /**
     * 是否终态。
     *
     * @return 是否终态
     */
    public boolean terminal() {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }
}
