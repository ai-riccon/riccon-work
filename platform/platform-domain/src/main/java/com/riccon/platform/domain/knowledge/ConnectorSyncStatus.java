package com.riccon.platform.domain.knowledge;

/**
 * 连接器最近一次同步结果（ADR-0023 第 6.2/6.3 节）。
 *
 * <p>写入 {@code kb_connector.last_sync_status}；失败也写结束时间，便于区分“从未同步”和“同步失败”。
 */
public enum ConnectorSyncStatus {
    /** 全部对象处理成功且 list 完整。 */
    SUCCESS,
    /** 存在单项失败或 list 不完整；未完整 list 不传播删除。 */
    PARTIAL,
    /** 同步级失败（认证/网络等），经过退避后仍失败。 */
    ERROR
}
