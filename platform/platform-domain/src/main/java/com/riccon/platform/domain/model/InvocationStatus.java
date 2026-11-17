package com.riccon.platform.domain.model;

/**
 * 一次模型调用的状态。
 *
 * <p>审计固定写 started + completed/failed 两条；调用记录用于会话运行详情，不替代审计。
 */
public enum InvocationStatus {
    /** 已开始，尚未返回终止事件。 */
    STARTED,
    /** 正常结束。 */
    SUCCEEDED,
    /** 失败；错误码区分可重试与不可重试。 */
    FAILED,
    /** 用户停止或客户端断开。 */
    CANCELLED
}
