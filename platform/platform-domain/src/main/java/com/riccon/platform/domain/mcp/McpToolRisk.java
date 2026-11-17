package com.riccon.platform.domain.mcp;

/**
 * 工具风险等级。
 *
 * <p>M02 只允许 {@link #READ}；{@link #WRITE} 预留给 M07，即使出现也必须走更严格的审批与幂等设计。
 */
public enum McpToolRisk {
    /** 只读取数，仍需全量人工审批。 */
    READ,
    /** 有外部副作用，M02 不可用。 */
    WRITE
}
