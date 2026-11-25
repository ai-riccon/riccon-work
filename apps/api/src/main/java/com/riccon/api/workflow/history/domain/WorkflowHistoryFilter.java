package com.riccon.api.workflow.history.domain;

import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 运行历史过滤条件。
 *
 * <p>过滤只作用于调用者可见的运行集合；不可见的流程/版本/发起人不会因为命中过滤条件而泄露存在性。
 * {@code from}/{@code to} 按运行创建时间过滤（左闭右闭），由服务层统一转换为 UTC {@link Instant}。
 *
 * @param workflowId 流程 ID；可空
 * @param versionNo 版本号；可空
 * @param status 运行状态；可空
 * @param requesterUserId 发起人账号；可空
 * @param from 创建时间起点（含）；可空
 * @param to 创建时间终点（含）；可空
 */
public record WorkflowHistoryFilter(
        UUID workflowId, Integer versionNo, WorkflowRunStatus status, UUID requesterUserId, Instant from, Instant to) {

    /** 校验版本号与时间范围。 */
    public WorkflowHistoryFilter {
        if (versionNo != null && versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be >= 1");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    /**
     * 判断是否存在创建时间过滤。
     *
     * @return 起点或终点任一存在时返回 {@code true}
     */
    public boolean hasTimeRange() {
        return from != null || to != null;
    }
}
