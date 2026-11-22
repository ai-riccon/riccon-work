package com.riccon.platform.domain.knowledge;

/**
 * 连接器状态（ADR-0023 第 6.2 节）。
 *
 * <p>新建默认 {@link #PAUSED}，管理员完成连接测试后显式启用。状态只控制定时同步：
 * {@link #PAUSED} 仍允许手动同步，{@link #ERROR} 表示最近一次同步失败但可手动重试。
 */
public enum ConnectorStatus {
    /** 参与定时同步。 */
    ACTIVE,
    /** 只停定时同步，仍允许手动同步；新建连接器的默认状态。 */
    PAUSED,
    /** 最近一次同步失败，仍允许手动重试。 */
    ERROR
}
