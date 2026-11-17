package com.riccon.platform.domain.identity;

/**
 * 权限码。
 *
 * <p>权限码是 RBAC 判定的唯一依据，格式 {@code <domain>.<action>}。新增权限码必须同时更新
 * {@code V2__identity_and_workspace.sql} 的权限字典、角色映射与 OpenAPI 的 {@code x-riccon-permission}。
 * M02 不提供自定义权限；M03 的部门数据范围通过额外的 scope 过滤实现，不改变这里的码值。
 */
public interface PermissionCodes {

    /** 读取工作空间列表与详情。 */
    String WORKSPACE_READ = "workspace.read";
    /** 创建/修改团队工作空间。 */
    String WORKSPACE_WRITE = "workspace.write";

    /** 读取文件元数据与下载地址。 */
    String FILE_READ = "file.read";
    /** 上传文件。 */
    String FILE_UPLOAD = "file.upload";
    /** 删除文件。 */
    String FILE_DELETE = "file.delete";

    /** 读取会话与消息。 */
    String CONVERSATION_READ = "conversation.read";
    /** 创建会话、发送消息、停止生成。 */
    String CONVERSATION_WRITE = "conversation.write";

    /** 读取知识库文档。 */
    String KNOWLEDGE_READ = "knowledge.read";
    /** 添加、删除、重建知识库文档。 */
    String KNOWLEDGE_MANAGE = "knowledge.manage";

    /** 读取报告任务与产物。 */
    String REPORT_READ = "report.read";
    /** 创建报告任务。 */
    String REPORT_CREATE = "report.create";
    /** 审批报告任务中的外部工具调用。 */
    String REPORT_APPROVE = "report.approve";

    /** 读取产物与版本。 */
    String ARTIFACT_READ = "artifact.read";

    /** 查看可用模型目录。 */
    String MODEL_READ = "model.read";
    /** 查看本租户已授权的 MCP 工具。 */
    String MCP_READ = "mcp.read";

    /** 租户内账号管理。 */
    String TENANT_USER_MANAGE = "tenant.user.manage";
    /** 租户内角色授予/撤销。 */
    String TENANT_ROLE_MANAGE = "tenant.role.manage";

    /** 平台租户管理（仅保留租户 platform）。 */
    String PLATFORM_TENANT_MANAGE = "platform.tenant.manage";
    /** 平台模型目录只读（M04 扩展写能力）。 */
    String PLATFORM_MODEL_READ = "platform.model.read";
    /** 平台 MCP 目录只读（M07 扩展注册与凭据托管）。 */
    String PLATFORM_MCP_READ = "platform.mcp.read";
    /** 审计查询（M03 启用；M02 只写不读，权限码先保留）。 */
    String AUDIT_READ = "audit.read";
}
