package com.riccon.worker.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;

/**
 * 一次同步的终态结果。
 *
 * @param status 同步状态
 * @param complete list 是否完整
 * @param summary 计数
 * @param errorCode 同步级失败码；成功/部分成功时为空
 * @param failureKind 失败分类
 * @param retryable 是否可重试
 * @param durationMs 耗时
 */
public record ConnectorSyncResult(
        ConnectorSyncStatus status,
        boolean complete,
        ConnectorSyncSummary summary,
        String errorCode,
        ConnectorFailureKind failureKind,
        boolean retryable,
        long durationMs) {

    /**
     * 判断是否同步级失败。
     *
     * @return ERROR 时返回 true
     */
    public boolean failed() {
        return status == ConnectorSyncStatus.ERROR;
    }
}
