package com.riccon.worker.run.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * Outbox 租约续期端口（ADR-0023 第 7.3 节）。
 *
 * <p>续期必须绑定单个 {@code eventId} 与 {@code leaseOwner}，不得用共享定时器批量续期；只有当前
 * 租约持有者且状态仍为 {@code DISPATCHED} 时更新才成功。返回值 0 表示租约已丢失，调用方必须中断
 * 处理器并放弃结果。
 */
public interface OutboxLeaseRenewer {

    /**
     * 续期单条事件租约。
     *
     * @param tenantId 事件租户
     * @param eventId 事件 ID
     * @param leaseOwner 当前租约持有者
     * @param leaseDuration 新的租约时长
     * @return 受影响行数；0 表示租约已丢失
     */
    int renew(String tenantId, UUID eventId, String leaseOwner, Duration leaseDuration);
}
