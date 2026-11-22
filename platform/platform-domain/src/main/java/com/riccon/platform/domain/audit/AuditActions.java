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

    /** 平台管理员保存某个用途的完整模型路由链（M04，ADR-0021 §4.4）。 */
    String MODEL_ROUTING_UPDATED = "model.routing.updated";
    /** 平台管理员启停模型或修改模型目录字段（M04，ADR-0021 §7.4）。 */
    String MODEL_UPDATED = "model.updated";
    /** 首字前失败后切换到下一个 key/模型；成功降级对员工不可见（M04，ADR-0021 §5.2）。 */
    String MODEL_FAILOVER_TRIGGERED = "model.failover.triggered";
    /** 平台管理员新增模型凭据；details 只允许掩码，不含明文（M04，ADR-0021 §6.3）。 */
    String MODEL_CREDENTIAL_CREATED = "model.credential.created";
    /** 平台管理员轮换模型凭据；单事务插入新 key 并停用旧 key（M04）。 */
    String MODEL_CREDENTIAL_ROTATED = "model.credential.rotated";
    /** 平台管理员停用模型凭据（M04）。 */
    String MODEL_CREDENTIAL_DISABLED = "model.credential.disabled";
    /** 平台管理员恢复模型凭据（来自 DISABLED，M04）。 */
    String MODEL_CREDENTIAL_ENABLED = "model.credential.enabled";
    /** Worker 收到 401/403 后自动使凭据失效；actor=SYSTEM（M04，ADR-0021 §6.3）。 */
    String MODEL_CREDENTIAL_INVALIDATED = "model.credential.invalidated";
    /** 平台管理员恢复自动失效的凭据（来自 INVALID，M04）。 */
    String MODEL_CREDENTIAL_RECOVERED = "model.credential.recovered";
    /** 平台管理员修改网关运行时开关（routingMode/failoverEnabled/quotaEnforcementEnabled，M04）。 */
    String MODEL_GATEWAY_SWITCH_CHANGED = "model.gateway.switch.changed";
    /** 平台管理员整体替换配额上限；首次设置时 previousLimit 允许 JSON null（M04，ADR-0022 §3.4）。 */
    String QUOTA_LIMIT_CHANGED = "quota.limit.changed";
    /** 新 run 因配额超限被拒绝；独立事务写入，失败则返回 500 而不是 429（M04，ADR-0022 §1.3）。 */
    String QUOTA_EXCEEDED = "quota.exceeded";

    /** 管理员把账号绑定到 OIDC 身份（M10-B2 写入，B1 定义词汇）。 */
    String USER_OIDC_BOUND = "user.oidc.bound";
    /** 管理员解除 OIDC 绑定并恢复本地密码（M10-B2 写入，B1 定义词汇）。 */
    String USER_OIDC_UNBOUND = "user.oidc.unbound";

    /** 添加知识库文档（M05；details 只写来源、可见范围与对象 ID，不写标题/原文）。 */
    String KNOWLEDGE_DOCUMENT_ADDED = "knowledge.document.added";
    /** 软删除知识库文档（M05；reason 只允许 USER/RETENTION/CONNECTOR）。 */
    String KNOWLEDGE_DOCUMENT_DELETED = "knowledge.document.deleted";
    /** 修改文档级 ACL（M05；数组最多 50 个 UUID，超出置 truncated）。 */
    String KNOWLEDGE_ACL_CHANGED = "knowledge.acl.changed";
    /** 管理员绕过文档 ACL 的管理性读取（M05 U8；不含标题、片段与正文）。 */
    String KNOWLEDGE_DOCUMENT_ADMIN_READ = "knowledge.document.admin_read";

    /** 创建知识库连接器（M05b）。 */
    String KNOWLEDGE_CONNECTOR_CREATED = "knowledge.connector.created";
    /** 修改连接器配置或可见范围（M05b）。 */
    String KNOWLEDGE_CONNECTOR_UPDATED = "knowledge.connector.updated";
    /** 软删除连接器（M05b）。 */
    String KNOWLEDGE_CONNECTOR_DELETED = "knowledge.connector.deleted";
    /** 连接器状态变化（M05b；ACTIVE/PAUSED/ERROR）。 */
    String KNOWLEDGE_CONNECTOR_STATUS_CHANGED = "knowledge.connector.status.changed";
    /** 连接器凭据写入或轮换（M05b；只写 key ID 与掩码）。 */
    String KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED = "knowledge.connector.credential.changed";
    /** 连接器同步开始（M05b）。 */
    String KNOWLEDGE_CONNECTOR_SYNC_STARTED = "knowledge.connector.sync.started";
    /** 连接器同步完成（M05b；只写计数、耗时与 complete）。 */
    String KNOWLEDGE_CONNECTOR_SYNC_COMPLETED = "knowledge.connector.sync.completed";
    /** 连接器同步失败（M05b；只写稳定错误码与可重试标记）。 */
    String KNOWLEDGE_CONNECTOR_SYNC_FAILED = "knowledge.connector.sync.failed";
    /** 连接器同步重试（M05b；attempt 从 1 开始）。 */
    String KNOWLEDGE_CONNECTOR_SYNC_RETRIED = "knowledge.connector.sync.retried";

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
