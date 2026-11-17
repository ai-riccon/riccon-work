package com.riccon.platform.domain.research;

/**
 * 报告任务阶段。
 *
 * <p>顺序固定为检索 → 工具取数 → 审批 → 生成；审批通过后从生成阶段继续，不重跑检索与工具阶段，
 * 除非员工显式重试失败阶段。
 */
public enum RunStage {
    /** 知识库检索与来源筛选。 */
    RETRIEVAL,
    /** 调用已授权的只读 MCP 工具。 */
    TOOL,
    /** 等待员工审批。 */
    APPROVAL,
    /** 生成并保存报告产物。 */
    GENERATION
}
