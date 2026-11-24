package com.riccon.platform.domain.identity;

/**
 * 权限码。
 *
 * <p>权限码是 RBAC 判定的唯一依据，格式 {@code <domain>.<action>}。新增权限码必须同时更新对应迁移的权限字典
 * （V2 定义 M02 码，V9 定义 M03 部门码，V11 定义 M04 模型网关/配额/成本码）、角色映射与 OpenAPI 的
 * {@code x-riccon-permission}。M02 不提供自定义权限；M03 新增 {@value #DEPARTMENT_READ} 与
 * {@value #PLATFORM_DEPARTMENT_MANAGE}，部门数据范围由空间谓词执行，既有码值语义不变。
 * M04 新增 {@value #PLATFORM_MODEL_MANAGE}、{@value #QUOTA_READ}、{@value #PLATFORM_QUOTA_MANAGE}、
 * {@value #COST_READ}、{@value #PLATFORM_COST_READ}；员工无配额/成本入口。
 * M05 新增 {@value #KNOWLEDGE_ACL_MANAGE}、{@value #KNOWLEDGE_CONNECTOR_READ} 与
 * {@value #KNOWLEDGE_CONNECTOR_MANAGE}，只授予 {@code TENANT_ADMIN}；创建人对本人文档的 ACL 修改
 * 由服务层例外授权，不新增角色（ADR-0023 第 5.6 节）。
 *
 * <p>M06 新增 {@code workflow.read/edit/publish/run/approve/manage}：平台管理员不持有任何 workflow
 * 权限，`/workflows/**`、`/workflow-runs/**`、`/workflow-approvals/**`、`/workflow-templates/**`
 * 对 {@code tenantId=platform} 统一 404；{@code WORKFLOW_EDITOR} 是唯一新增系统角色（ADR-0024 §6.1）。
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
    /** 管理任意知识库文档的 ACL 并做管理性读取（M05，仅 TENANT_ADMIN；ADR-0023 第 5.6 节）。 */
    String KNOWLEDGE_ACL_MANAGE = "knowledge.acl.manage";
    /** 查看知识库连接器配置与同步状态（M05b，仅 TENANT_ADMIN；ADR-0023 第 5.6 节）。 */
    String KNOWLEDGE_CONNECTOR_READ = "knowledge.connector.read";
    /** 创建、修改、删除连接器与触发同步（M05b，仅 TENANT_ADMIN；ADR-0023 第 5.6 节）。 */
    String KNOWLEDGE_CONNECTOR_MANAGE = "knowledge.connector.manage";

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

    /** 读取本人所属部门；租户管理员可读本租户全部部门（M03 新增，V9 写入字典）。 */
    String DEPARTMENT_READ = "department.read";

    /** 平台租户管理（仅保留租户 platform）。 */
    String PLATFORM_TENANT_MANAGE = "platform.tenant.manage";
    /** 跨租户管理租户部门元数据，不含业务内容（M03 新增，V9 写入字典；仅 PLATFORM_ADMIN）。 */
    String PLATFORM_DEPARTMENT_MANAGE = "platform.department.manage";
    /** 平台模型目录只读（M04 扩展写能力）。 */
    String PLATFORM_MODEL_READ = "platform.model.read";
    /** 平台模型路由、模型启停、key 池与网关开关管理（M04，仅 PLATFORM_ADMIN；ADR-0021 §7.1）。 */
    String PLATFORM_MODEL_MANAGE = "platform.model.manage";
    /** 读取本租户配额与用量（M04，仅 TENANT_ADMIN；ADR-0022 §1.5）。 */
    String QUOTA_READ = "quota.read";
    /** 平台管理员跨租户配额查看与整体替换（M04，仅 PLATFORM_ADMIN）。 */
    String PLATFORM_QUOTA_MANAGE = "platform.quota.manage";
    /** 读取本租户成本聚合与调用明细（M04，仅 TENANT_ADMIN；ADR-0022 §3.1）。 */
    String COST_READ = "cost.read";
    /** 平台管理员跨租户成本聚合，不返回用户明细（M04，仅 PLATFORM_ADMIN）。 */
    String PLATFORM_COST_READ = "platform.cost.read";
    /** 平台 MCP 目录只读（M07 扩展注册与凭据托管）。 */
    String PLATFORM_MCP_READ = "platform.mcp.read";
    /** 审计查询（M10 启用；M02/M03 只写不读，权限码先保留）。 */
    String AUDIT_READ = "audit.read";
    /** 审批同租户审计导出申请（M10，仅租户管理员；ADR-0020 第 2 节）。 */
    String AUDIT_EXPORT_APPROVE = "audit.export.approve";
    /** 平台管理员审批单管理员租户的审计导出申请（M10，仅元数据；ADR-0020 第 2 节）。 */
    String PLATFORM_AUDIT_EXPORT_APPROVE = "platform.audit.export.approve";

    /** 读取可运行流程、自己的草稿/运行、模板列表与运行限制（M06；ADR-0024 §6.1）。 */
    String WORKFLOW_READ = "workflow.read";
    /** 创建/编辑草稿、校验、复制模板与另存副本（M06）。 */
    String WORKFLOW_EDIT = "workflow.edit";
    /** 发布版本、停用/启用流程（M06；DEPARTMENT 范围还要求部门负责人关系，关系本身不授权）。 */
    String WORKFLOW_PUBLISH = "workflow.publish";
    /** 创建运行、取消/重试自己的运行（M06）。 */
    String WORKFLOW_RUN = "workflow.run";
    /** 审批待办/详情/决定（M06；只对指派给本人的审批生效）。 */
    String WORKFLOW_APPROVE = "workflow.approve";
    /** 移交流程所有权（M06，仅 TENANT_ADMIN）。 */
    String WORKFLOW_MANAGE = "workflow.manage";
}
