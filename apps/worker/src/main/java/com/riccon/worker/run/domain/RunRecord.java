package com.riccon.worker.run.domain;

import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import java.util.UUID;

/**
 * 运行记录快照，用于领取后校验租户、类型与状态。
 *
 * @param id 运行 ID
 * @param kind 运行类别
 * @param status 当前状态
 * @param attempt 当前尝试号
 * @param maxAttempts 最大尝试号
 */
public record RunRecord(UUID id, RunKind kind, RunStatus status, int attempt, int maxAttempts) {

    /**
     * 判断运行是否已到终态；终态不重复执行，重复投递直接标记 outbox 完成。
     *
     * @return 是否终态
     */
    public boolean isTerminal() {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }
}
