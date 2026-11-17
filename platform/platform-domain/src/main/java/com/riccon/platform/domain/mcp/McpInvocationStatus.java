package com.riccon.platform.domain.mcp;

/**
 * 一次 MCP 工具调用的状态。
 *
 * <p>调用失败必须保留工具名、参数/结果 hash、耗时和错误码；不得把结果原文写入日志或审计。
 */
public enum McpInvocationStatus {
    /** 已发起，等待结果。 */
    STARTED,
    /** 正常返回。 */
    SUCCEEDED,
    /** 超时、连接失败或工具返回错误。 */
    FAILED,
    /** 审批拒绝或租户未授权。 */
    DENIED
}
