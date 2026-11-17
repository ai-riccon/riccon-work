package com.riccon.platform.domain.chat;

/**
 * 消息状态。
 *
 * <p>流式回答在首字前失败时不应留下半截消息；首字后失败或用户停止时保留已生成内容，状态分别记为
 * {@link #FAILED} 或 {@link #STOPPED}。
 */
public enum MessageStatus {
    /** 已持久化，尚未开始生成。 */
    PENDING,
    /** 正在流式生成。 */
    STREAMING,
    /** 正常结束。 */
    COMPLETED,
    /** 用户停止或客户端断开，已生成内容保留。 */
    STOPPED,
    /** 生成失败；错误码用于展示与重试。 */
    FAILED
}
