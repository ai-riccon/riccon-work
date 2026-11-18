package com.riccon.platform.domain.audit;

/**
 * 审计动作名。
 *
 * <p>与 ADR-0007 第 2 节及 ADR-0018 第 5 节一一对应；数据库存短名，查询与导出使用同一常量，
 * 禁止在业务代码里写字符串字面量。
 */
public interface AuditActions {

    /** 登录成功。 */
    String AUTH_LOGIN_SUCCEEDED = "auth.login.succeeded";
    /** 登录失败（账号不存在、密码错误、锁定）。 */
    String AUTH_LOGIN_FAILED = "auth.login.failed";
    /** 主动登出。 */
    String AUTH_LOGOUT = "auth.logout";

    /** 平台管理员创建租户。 */
    String TENANT_CREATED = "tenant.created";
    /** 创建账号。 */
    String USER_CREATED = "user.created";
    /** 启用/禁用账号。 */
    String USER_DISABLED = "user.disabled";
    /** 角色授予/撤销。 */
    String PERMISSION_CHANGED = "permission.changed";

    /** 文件上传并登记成功。 */
    String FILE_UPLOADED = "file.uploaded";
    /** 删除文件。 */
    String FILE_DELETED = "file.deleted";
    /** 新建会话。 */
    String SESSION_CREATED = "session.created";

    /** 开始调用模型（流式首字前）。 */
    String MODEL_INVOCATION_STARTED = "model.invocation.started";
    /** 模型调用正常结束。 */
    String MODEL_INVOCATION_COMPLETED = "model.invocation.completed";
    /** 模型调用失败。 */
    String MODEL_INVOCATION_FAILED = "model.invocation.failed";

    /** 一次 MCP 工具调用结束。 */
    String MCP_TOOL_INVOKED = "mcp.tool.invoked";
    /** 员工批准/拒绝审批。 */
    String APPROVAL_DECIDED = "approval.decided";
    /** 报告产物保存。 */
    String ARTIFACT_SAVED = "artifact.saved";

    /** 数据导出（M10）。 */
    String DATA_EXPORTED = "data.exported";
    /** 查询审计（M10）。 */
    String AUDIT_QUERIED = "audit.queried";
    /** 发起审计导出申请（M10，ADR-0020 第 2 节）。 */
    String AUDIT_EXPORT_REQUESTED = "audit.export.requested";
    /** 批准审计导出申请（M10，ADR-0020 第 2 节）。 */
    String AUDIT_EXPORT_APPROVED = "audit.export.approved";
    /** 拒绝审计导出申请（M10，ADR-0020 第 2 节）。 */
    String AUDIT_EXPORT_REJECTED = "audit.export.rejected";

    /**
     * 平台管理员更新租户状态或保留策略（M02 起已写入，M10 提升为共享词汇）。
     *
     * <p>M10 开放审计查询后，任何已写入的动作都必须是稳定词汇，避免查询/导出出现“未知动作”。
     */
    String TENANT_UPDATED = "tenant.updated";
    /** 已认证请求携带与主体不一致的租户标识被拒绝（M02 起已写入，M10 提升为共享词汇）。 */
    String TENANT_ACCESS_DENIED = "tenant.access.denied";
    /** 删除会话（软删除；M02 起已写入，M10 提升为共享词汇）。 */
    String SESSION_DELETED = "session.deleted";
    /** 发送消息并创建对话运行（M02 起已写入，M10 提升为共享词汇）。 */
    String MESSAGE_SENT = "message.sent";
    /** 停止生成或客户端断开取消（M02 起已写入，M10 提升为共享词汇）。 */
    String MESSAGE_STOPPED = "message.stopped";
    /** 创建市场调研报告任务（M02 起已写入，M10 提升为共享词汇）。 */
    String RESEARCH_TASK_CREATED = "research.task.created";
    /** 获取报告产物下载地址（M02 起已写入，M10 提升为共享词汇）。 */
    String ARTIFACT_DOWNLOADED = "artifact.downloaded";
    /** Worker 运行发起人已禁用，运行被拒绝（M10 提升为共享词汇，API 读取策略同样识别）。 */
    String USER_ACCESS_DENIED = "user.access.denied";

    /** 管理员把账号绑定到 OIDC 身份（M10-B2 写入，B1 定义词汇）。 */
    String USER_OIDC_BOUND = "user.oidc.bound";
    /** 管理员解除 OIDC 绑定并恢复本地密码（M10-B2 写入，B1 定义词汇）。 */
    String USER_OIDC_UNBOUND = "user.oidc.unbound";

    /** 平台管理员创建租户部门（M03，ADR-0018 第 5 节）。 */
    String DEPARTMENT_CREATED = "department.created";
    /** 重命名部门；停用部门不可改名（M03）。 */
    String DEPARTMENT_RENAMED = "department.renamed";
    /** 停用部门；只改状态，不清空成员归属（M03）。 */
    String DEPARTMENT_DISABLED = "department.disabled";
    /** 重新启用部门（M03）。 */
    String DEPARTMENT_ENABLED = "department.enabled";
    /** 账号加入部门；同一事务更新单主部门（M03）。 */
    String DEPARTMENT_MEMBER_ADDED = "department.member.added";
    /** 账号移出部门；同时清理本部门 CUSTOM 空间成员行（M03）。 */
    String DEPARTMENT_MEMBER_REMOVED = "department.member.removed";
    /** 任命/移交部门负责人；一次移交只写一条（M03）。 */
    String DEPARTMENT_MANAGER_CHANGED = "department.manager.changed";

    /** 在部门下创建团队空间（M03）。 */
    String WORKSPACE_CREATED = "workspace.created";
    /** 修改空间名称或描述（M03）。 */
    String WORKSPACE_UPDATED = "workspace.updated";
    /** 修改空间可见性（DEPT/CUSTOM），不可转为历史 TENANT（M03）。 */
    String WORKSPACE_VISIBILITY_CHANGED = "workspace.visibility.changed";
    /** 加入 CUSTOM 空间显式成员（M03）。 */
    String WORKSPACE_MEMBER_ADDED = "workspace.member.added";
    /** 移出 CUSTOM 空间显式成员（M03）。 */
    String WORKSPACE_MEMBER_REMOVED = "workspace.member.removed";
}
