package com.riccon.platform.domain.knowledge;

/**
 * 连接器同步触发方式（ADR-0023 第 6.3/5.6 节）。
 *
 * <p>写入同步审计 details 的 {@code trigger} 字段，便于区分人工、定时与失败重试。
 */
public enum ConnectorSyncTrigger {
    /** 管理员手动触发。 */
    MANUAL,
    /** 按部署固定周期自动触发。 */
    SCHEDULE,
    /** 同步级失败后的退避重试。 */
    RETRY
}
