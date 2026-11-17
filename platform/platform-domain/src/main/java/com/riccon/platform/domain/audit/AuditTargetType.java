package com.riccon.platform.domain.audit;

/**
 * 审计目标类型。
 *
 * <p>M02 只覆盖 ADR-0007 列出的目标；新增目标必须同步迁移注释、白名单测试与查询索引评估。
 */
public enum AuditTargetType {
    /** 登录会话。 */
    SESSION,
    /** 工作空间。 */
    WORKSPACE,
    /** 文件。 */
    FILE,
    /** 会话。 */
    CONVERSATION,
    /** 知识库文档。 */
    KNOWLEDGE_DOCUMENT,
    /** 报告任务。 */
    RESEARCH_TASK,
    /** 执行运行。 */
    RUN,
    /** 产物。 */
    ARTIFACT,
    /** 模型。 */
    MODEL,
    /** MCP 工具。 */
    MCP_TOOL,
    /** 账号。 */
    USER,
    /** 角色。 */
    ROLE,
    /** 租户。 */
    TENANT,
    /** 审计自身（M03 查询）。 */
    AUDIT
}
