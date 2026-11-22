package com.riccon.platform.domain.audit;

/**
 * 审计目标类型。
 *
 * <p>覆盖 ADR-0007 与 ADR-0018 第 5 节列出的目标；新增目标必须同步白名单测试与查询索引评估。
 */
public enum AuditTargetType {
    /** 登录会话。 */
    SESSION,
    /** 工作空间。 */
    WORKSPACE,
    /** 租户部门（M03）。 */
    DEPARTMENT,
    /** 文件。 */
    FILE,
    /** 会话。 */
    CONVERSATION,
    /** 知识库文档。 */
    KNOWLEDGE_DOCUMENT,
    /** 知识库外部连接器（M05b，target_id 为连接器 ID）。 */
    KNOWLEDGE_CONNECTOR,
    /** 报告任务。 */
    RESEARCH_TASK,
    /** 执行运行。 */
    RUN,
    /** 产物。 */
    ARTIFACT,
    /** 模型。 */
    MODEL,
    /** 模型路由链（M04，target_id 为 CHAT/REPORT）。 */
    MODEL_ROUTING,
    /** 模型凭据（M04，仅平台托管 key 生命周期）。 */
    MODEL_CREDENTIAL,
    /** 配额（M04，target_id 为 scopeId；平台事件 target_id 可为空）。 */
    QUOTA,
    /** MCP 工具。 */
    MCP_TOOL,
    /** 账号。 */
    USER,
    /** 角色。 */
    ROLE,
    /** 租户。 */
    TENANT,
    /** 审计自身（M10 查询）。 */
    AUDIT,
    /** 审计导出请求（M10，target_id 为导出请求 ID）。 */
    AUDIT_EXPORT
}
