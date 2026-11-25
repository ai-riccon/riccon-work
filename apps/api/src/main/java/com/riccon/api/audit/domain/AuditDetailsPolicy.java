package com.riccon.api.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 审计 details 白名单。
 *
 * <p>ADR-0007 第 5 节与 ADR-0018 第 5 节要求 details 只写入白名单字段，禁止密码、token、Cookie、密钥、
 * 提示词、原文、部门成员名单与完整 PII。本类在写入数据库前执行校验；未列出的动作只允许空 details，
 * 新增动作必须显式扩展白名单。
 *
 * <p>M10：本类同时是审计读取（B3）的 action-aware 白名单，因此必须包含 worker 写入的模型/MCP/产物动作，
 * 并允许 {@code promptTokens}/{@code completionTokens} 等白名单指标。判断顺序必须是“白名单优先”，
 * 不能再用 {@code token}/{@code secret}/{@code content} 子串规则误杀合法键（ADR-0020 F-003）。
 *
 * <p>M05：知识库文档/ACL/管理性读取/连接器动作必须 action-specific 校验：{@code addedUserIds}/
 * {@code removedUserIds} 为 UUID 数组、各自最多 50 个、允许空数组；{@code admin_read} 的
 * {@code documentId} 在 {@code scope=LIST} 时可为空且 {@code resultCount} 必填；封闭枚举字段与
 * 未知键/超限/类型错误一律 fail-closed（ADR-0023 第 5.6/5.7 节，IT-KB-110）。
 *
 * <p>M06：{@code workflow.*} 动作只允许 ID、枚举、计数与脱敏摘要（ADR-0024 第 8.1/8.2 节）；
 * {@code workflow.feature.changed} 强制 {@code enabled}/{@code source=DEPLOYMENT_CONFIG}/
 * {@code instanceId}/{@code observedAt} 四键，供关闭态启动观测审计（OPS-M06-019）。
 * 未登记动作仍只允许空 details，保持 M10 读取端 fail-closed。
 */
public final class AuditDetailsPolicy {

    /** 每个动作允许的 details 键。 */
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.ofEntries(
            Map.entry(AuditActions.AUTH_LOGIN_SUCCEEDED, Set.of("method", "sessionIdHash")),
            Map.entry(AuditActions.AUTH_LOGIN_FAILED, Set.of("reasonCode", "attemptCount")),
            Map.entry(AuditActions.AUTH_LOGOUT, Set.of()),
            Map.entry(AuditActions.TENANT_CREATED, Set.of("plan")),
            Map.entry(AdditionalAuditActions.TENANT_UPDATED, Set.of("status", "retentionDays")),
            Map.entry(AuditActions.USER_CREATED, Set.of("role", "targetTenantId")),
            Map.entry(AuditActions.USER_DISABLED, Set.of("enabled", "targetTenantId")),
            Map.entry(AuditActions.PERMISSION_CHANGED, Set.of("targetUserId", "role", "change", "targetTenantId")),
            Map.entry(AuditActions.FILE_UPLOADED, Set.of("fileId", "sizeBytes", "contentType", "sha256")),
            Map.entry(AuditActions.FILE_DELETED, Set.of("fileId", "objectKeyHash")),
            // ADR-0007 第 2 节规定新建会话的 details 只写 sessionId。
            Map.entry(AuditActions.SESSION_CREATED, Set.of("sessionId")),
            Map.entry(AdditionalAuditActions.SESSION_DELETED, Set.of("sessionId")),
            Map.entry(
                    AdditionalAuditActions.MESSAGE_SENT,
                    Set.of("userMessageId", "assistantMessageId", "runId", "attachmentCount")),
            Map.entry(AdditionalAuditActions.MESSAGE_STOPPED, Set.of("messageId", "runId", "reason")),
            Map.entry(
                    AdditionalAuditActions.RESEARCH_TASK_CREATED,
                    Set.of("researchTaskId", "workspaceId", "runId", "mcpToolId")),
            // ADR-0007 第 2 节：审批审计只允许审批 ID、决定、审批人与工具名。
            Map.entry(AuditActions.APPROVAL_DECIDED, Set.of("approvalId", "decision", "approverUserId", "toolName")),
            Map.entry(AdditionalAuditActions.ARTIFACT_DOWNLOADED, Set.of("artifactId", "version")),
            Map.entry(AdditionalAuditActions.TENANT_ACCESS_DENIED, Set.of("attemptedTenant", "reason")),
            // M03 部门元数据：只记录 UUID、枚举与部门名；targetTenantId 只用于平台跨租户事件。
            Map.entry(AuditActions.DEPARTMENT_CREATED, Set.of("targetTenantId", "departmentId", "name", "status")),
            Map.entry(
                    AuditActions.DEPARTMENT_RENAMED, Set.of("targetTenantId", "departmentId", "name", "previousName")),
            Map.entry(
                    AuditActions.DEPARTMENT_DISABLED,
                    Set.of("targetTenantId", "departmentId", "status", "memberCount")),
            Map.entry(
                    AuditActions.DEPARTMENT_ENABLED, Set.of("targetTenantId", "departmentId", "status", "memberCount")),
            Map.entry(
                    AuditActions.DEPARTMENT_MEMBER_ADDED,
                    Set.of("targetTenantId", "departmentId", "userId", "previousDepartmentId")),
            Map.entry(
                    AuditActions.DEPARTMENT_MEMBER_REMOVED,
                    Set.of("targetTenantId", "departmentId", "userId", "removedWorkspaceMembers")),
            Map.entry(
                    AuditActions.DEPARTMENT_MANAGER_CHANGED,
                    Set.of("targetTenantId", "departmentId", "managerUserId", "previousManagerUserId")),
            // M03 租户内空间管理：不写成员名单、空间内容或文件名，只写 ID、名称与可见性枚举。
            Map.entry(AuditActions.WORKSPACE_CREATED, Set.of("workspaceId", "departmentId", "visibility")),
            Map.entry(AuditActions.WORKSPACE_UPDATED, Set.of("workspaceId", "name", "previousName")),
            Map.entry(
                    AuditActions.WORKSPACE_VISIBILITY_CHANGED,
                    Set.of("workspaceId", "visibility", "previousVisibility", "departmentId")),
            Map.entry(AuditActions.WORKSPACE_MEMBER_ADDED, Set.of("workspaceId", "userId")),
            Map.entry(AuditActions.WORKSPACE_MEMBER_REMOVED, Set.of("workspaceId", "userId")),
            // M10 审计查询/导出（ADR-0020 第 6.1 节）：只写哈希、计数与 ID，不写 filters 原文或导出内容。
            Map.entry(AuditActions.AUDIT_QUERIED, Set.of("filtersHash", "rowCount")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_REQUESTED,
                    Set.of(
                            "exportId",
                            "filtersHash",
                            "estimatedRowCount",
                            "status",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_APPROVED,
                    Set.of(
                            "exportId",
                            "decision",
                            "estimatedRowCount",
                            "filtersHash",
                            "targetTenantId",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(
                    AuditActions.AUDIT_EXPORT_REJECTED,
                    Set.of(
                            "exportId",
                            "decision",
                            "estimatedRowCount",
                            "filtersHash",
                            "targetTenantId",
                            "occurredFrom",
                            "occurredTo",
                            "action",
                            "actorId",
                            "targetType",
                            "targetId",
                            "outcome")),
            Map.entry(AuditActions.DATA_EXPORTED, Set.of("exportId", "scope", "rowCount", "filtersHash")),
            // M10 OIDC 绑定（ADR-0019 第 3 节）：只存用户 ID 与 identity 哈希，不存 subject 原文或邮箱。
            Map.entry(
                    AuditActions.USER_OIDC_BOUND, Set.of("userId", "issuerHash", "subjectHash", "previousAuthSource")),
            Map.entry(AuditActions.USER_OIDC_UNBOUND, Set.of("userId", "issuerHash")),
            // Worker 侧已写入的动作；API 读取策略必须识别同一白名单（ADR-0020 F-003/F-005）。
            Map.entry(AdditionalAuditActions.USER_ACCESS_DENIED, Set.of("userId", "reason")),
            Map.entry(AuditActions.MODEL_INVOCATION_STARTED, Set.of("modelId", "purpose")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_COMPLETED,
                    Set.of("modelId", "durationMs", "promptTokens", "completionTokens")),
            Map.entry(
                    AuditActions.MODEL_INVOCATION_FAILED,
                    // reason 仅用于 Worker 链空（CHAIN_EMPTY）等稳定原因码；与 Worker 策略对称（ADR-0021 §4.1/§7.4）。
                    Set.of("modelId", "durationMs", "errorCode", "retryable", "emitted", "reason")),
            Map.entry(
                    AuditActions.MCP_TOOL_INVOKED,
                    // M06：工作流工具调用复用同一动作，追加可选 workflowRunId/workflowNodeId（ADR-0024 §8.1）。
                    Set.of(
                            "toolName",
                            "paramsSha256",
                            "resultSha256",
                            "durationMs",
                            "approvalId",
                            "workflowRunId",
                            "workflowNodeId")),
            Map.entry(AuditActions.ARTIFACT_SAVED, Set.of("artifactId", "reportRunId", "version")),
            // M04 模型路由/模型/降级/key 池/开关（ADR-0021 §7.4）：只写 ID、枚举、版本与掩码，不写
            // baseUrl、provider code、key、Authorization、提示词或原文。
            Map.entry(
                    AuditActions.MODEL_ROUTING_UPDATED,
                    Set.of("purpose", "modelIds", "version", "previousVersion", "result")),
            Map.entry(AuditActions.MODEL_UPDATED, Set.of("modelId", "status", "providerId")),
            Map.entry(
                    AuditActions.MODEL_FAILOVER_TRIGGERED,
                    Set.of("purpose", "fromModelId", "toModelId", "reason", "attempt")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_CREATED,
                    Set.of("providerId", "credentialId", "maskedHint", "status")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_ROTATED,
                    Set.of("providerId", "credentialId", "previousCredentialId", "maskedHint", "status")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_DISABLED,
                    Set.of("providerId", "credentialId", "maskedHint", "status", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_ENABLED,
                    Set.of("providerId", "credentialId", "maskedHint", "status", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_INVALIDATED,
                    Set.of("providerId", "credentialId", "maskedHint", "reason")),
            Map.entry(
                    AuditActions.MODEL_CREDENTIAL_RECOVERED,
                    Set.of("providerId", "credentialId", "maskedHint", "reason")),
            Map.entry(AuditActions.MODEL_GATEWAY_SWITCH_CHANGED, Set.of("switch", "value", "previousValue", "source")),
            // M04 配额（ADR-0022 §3.4）：scopeId 是租户代码或用户 UUID；不接受用户名单、金额或提示词。
            Map.entry(
                    AuditActions.QUOTA_LIMIT_CHANGED,
                    Set.of("scopeType", "scopeId", "window", "previousLimit", "limit")),
            Map.entry(
                    AuditActions.QUOTA_EXCEEDED, Set.of("scopeType", "scopeId", "window", "limit", "used", "resetsAt")),
            // M05 知识库文档/ACL（ADR-0023 §5.6）：只写来源、可见范围、UUID 集合与计数，
            // 禁止标题、片段、原文、externalId、对象 key 与凭据。
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_ADDED,
                    Set.of("sourceType", "fileId", "visibility", "departmentId", "connectorId")),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, Set.of("sourceType", "visibility", "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_ACL_CHANGED,
                    Set.of(
                            "visibility",
                            "previousVisibility",
                            "departmentId",
                            "addedUserCount",
                            "removedUserCount",
                            "addedUserIds",
                            "removedUserIds",
                            "aclUserCount",
                            "truncated")),
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ,
                    Set.of("scope", "documentId", "visibility", "aclUserCount", "resultCount", "reason")),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, Set.of("documentId", "force", "status")),
            // M05b 连接器生命周期：只写配置定位、状态与计数；bucket/prefix 是管理员配置，
            // 不是对象内容；凭据动作只写 key ID 与掩码。
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_CREATED,
                    Set.of(
                            "connectorId",
                            "type",
                            "endpointId",
                            "bucket",
                            "prefix",
                            "status",
                            "previousStatus",
                            "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_UPDATED,
                    Set.of(
                            "connectorId",
                            "type",
                            "endpointId",
                            "bucket",
                            "prefix",
                            "status",
                            "previousStatus",
                            "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_DELETED,
                    Set.of(
                            "connectorId",
                            "type",
                            "endpointId",
                            "bucket",
                            "prefix",
                            "status",
                            "previousStatus",
                            "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED,
                    Set.of(
                            "connectorId",
                            "type",
                            "endpointId",
                            "bucket",
                            "prefix",
                            "status",
                            "previousStatus",
                            "reason")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED,
                    Set.of("connectorId", "type", "credentialKeyId", "maskedHint")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED, Set.of("connectorId", "syncId", "trigger")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED,
                    Set.of(
                            "connectorId",
                            "syncId",
                            "scanned",
                            "created",
                            "updated",
                            "skipped",
                            "failed",
                            "deleted",
                            "durationMs",
                            "complete")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_FAILED,
                    Set.of("connectorId", "syncId", "errorCode", "retryable", "complete", "durationMs")),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_SYNC_RETRIED,
                    Set.of("connectorId", "syncId", "attempt", "reason")),
            // M06 编排 Studio（ADR-0024 §8.1）：details 只写 ID、枚举、计数与脱敏摘要；禁止图 JSON、
            // prompt/query 模板、节点输出、工具参数、凭据与文档正文。M10 读取端复用同一白名单。
            Map.entry(AuditActions.WORKFLOW_CREATED, Set.of("workflowId", "sourceTemplateId", "sourceTemplateType")),
            Map.entry(AuditActions.WORKFLOW_DRAFT_SAVED, Set.of("workflowId", "version", "nodeCount")),
            Map.entry(
                    AuditActions.WORKFLOW_PUBLISHED,
                    Set.of("workflowId", "workflowVersionId", "versionNo", "runScope", "departmentId", "nodeCount")),
            Map.entry(AuditActions.WORKFLOW_DISABLED, Set.of("workflowId", "previousStatus", "status")),
            Map.entry(AuditActions.WORKFLOW_ENABLED, Set.of("workflowId", "previousStatus", "status")),
            Map.entry(AuditActions.WORKFLOW_OWNER_TRANSFERRED, Set.of("workflowId", "fromUserId", "toUserId")),
            Map.entry(
                    AuditActions.WORKFLOW_RUN_REQUESTED,
                    Set.of("workflowId", "workflowVersionId", "workflowRunId", "versionNo", "nodeCount")),
            Map.entry(AuditActions.WORKFLOW_RUN_STARTED, Set.of("workflowRunId", "workflowId")),
            Map.entry(
                    AuditActions.WORKFLOW_RUN_COMPLETED,
                    Set.of("workflowRunId", "status", "durationMs", "nodeCount", "resultBytes")),
            Map.entry(AuditActions.WORKFLOW_RUN_FAILED, Set.of("workflowRunId", "errorCode", "failedNodeId")),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Set.of("workflowRunId", "previousStatus", "reason")),
            Map.entry(AuditActions.WORKFLOW_RUN_RETRIED, Set.of("workflowRunId", "nodeId", "attempt")),
            Map.entry(AuditActions.WORKFLOW_RUN_ADMIN_READ, Set.of("workflowRunId", "scope", "reason")),
            Map.entry(
                    AuditActions.WORKFLOW_APPROVAL_REQUESTED,
                    Set.of(
                            "workflowRunId",
                            "workflowApprovalId",
                            "nodeId",
                            "assigneeCount",
                            "actionKind",
                            "targetToolName")),
            Map.entry(
                    AuditActions.WORKFLOW_APPROVAL_DECIDED,
                    Set.of(
                            "workflowRunId",
                            "workflowApprovalId",
                            "nodeId",
                            "decision",
                            "approverUserId",
                            "targetToolName",
                            "decisionComment")),
            // 启动观测（不是变更日志）：ADR-0024 §4.8/§8.1 要求 instanceId 与 observedAt 必须可写入。
            Map.entry(
                    AuditActions.WORKFLOW_FEATURE_CHANGED,
                    Set.of("enabled", "source", "reason", "instanceId", "observedAt")));

    /**
     * 允许 JSON null 的动作级键。
     *
     * <p>只用于“确实不存在该值”的语义：{@code quota.limit.changed.previousLimit} 首次设置；
     * {@code knowledge.document.added} 的 fileId/connectorId/departmentId 按来源与可见范围可有可无；
     * {@code knowledge.acl.changed.departmentId} 从 DEPT 改为其他范围时为空；
     * {@code knowledge.document.admin_read.documentId} 只在 {@code scope=LIST} 时为空（见下方必填校验）；
     * 连接器生命周期动作的 {@code previousStatus} 在首次创建时为空。
     * 其他键继续 fail-closed，不得放宽为任意 nullable。
     */
    private static final Map<String, Set<String>> NULLABLE_KEYS = Map.ofEntries(
            Map.entry(AuditActions.QUOTA_LIMIT_CHANGED, Set.of("previousLimit")),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_ADDED, Set.of("fileId", "departmentId", "connectorId")),
            Map.entry(AuditActions.KNOWLEDGE_ACL_CHANGED, Set.of("departmentId")),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, Set.of("documentId", "visibility")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_CREATED, Set.of("previousStatus")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_UPDATED, Set.of("previousStatus")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_DELETED, Set.of("previousStatus")),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED, Set.of("previousStatus")),
            // M06：未从模板创建时来源为空；非 DEPARTMENT 范围时部门为空；可选失败节点/原因/工具名/
            // 拒绝理由按动作语义允许 JSON null，其他键继续 fail-closed（ADR-0024 §8.1 的 “?” 字段）。
            Map.entry(AuditActions.WORKFLOW_CREATED, Set.of("sourceTemplateId", "sourceTemplateType")),
            Map.entry(AuditActions.WORKFLOW_PUBLISHED, Set.of("departmentId")),
            Map.entry(AuditActions.WORKFLOW_RUN_FAILED, Set.of("failedNodeId")),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Set.of("reason")),
            Map.entry(AuditActions.WORKFLOW_APPROVAL_REQUESTED, Set.of("targetToolName")),
            Map.entry(AuditActions.WORKFLOW_APPROVAL_DECIDED, Set.of("targetToolName", "decisionComment")),
            Map.entry(AuditActions.WORKFLOW_FEATURE_CHANGED, Set.of("reason")),
            // M06：J2 工具调用不带工作流引用；显式 null 与省略等价，避免写入端为空值失败。
            Map.entry(AuditActions.MCP_TOOL_INVOKED, Set.of("workflowRunId", "workflowNodeId")));

    /**
     * 允许受限数组的动作级键。
     *
     * <p>{@code model.routing.updated.modelIds} 与 {@code knowledge.acl.changed.addedUserIds}/
     * {@code removedUserIds} 是仅有的数组；元素必须是 UUID 字符串。数组之外的类型仍按基本类型校验，
     * 禁止任意 JSON。
     */
    private static final Map<String, Set<String>> ARRAY_KEYS = Map.of(
            AuditActions.MODEL_ROUTING_UPDATED,
            Set.of("modelIds"),
            AuditActions.KNOWLEDGE_ACL_CHANGED,
            Set.of("addedUserIds", "removedUserIds"));

    /**
     * 动作级数组元素上限；未登记时使用 {@link #DEFAULT_MAX_ARRAY_ELEMENTS}。
     *
     * <p>M04 路由链最多 20 个模型；M05 ACL 变更最多 50 个用户 UUID，超过由服务层置
     * {@code truncated=true} 并只保留计数，策略层直接拒绝超限数组（不会静默截断）。
     */
    private static final Map<String, Map<String, Integer>> ARRAY_LIMITS = Map.of(
            AuditActions.MODEL_ROUTING_UPDATED,
            Map.of("modelIds", 20),
            AuditActions.KNOWLEDGE_ACL_CHANGED,
            Map.of("addedUserIds", 50, "removedUserIds", 50));

    /**
     * 封闭枚举值的动作级校验。
     *
     * <p>只约束 ADR 明确的封闭集合；{@code knowledge.connector.*.reason} 与
     * {@code knowledge.connector.sync.retried.reason} 是稳定原因码而非上述枚举，由写入方保证。
     */
    private static final Map<String, Map<String, Set<String>>> ENUM_VALUES = Map.ofEntries(
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_ADDED,
                    Map.of("sourceType", Set.of("UPLOAD", "SEED", "CONNECTOR"), "visibility", visibilityValues())),
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                    Map.of(
                            "sourceType", Set.of("UPLOAD", "SEED", "CONNECTOR"),
                            "reason", Set.of("USER", "RETENTION", "CONNECTOR"),
                            "visibility", visibilityValues())),
            Map.entry(
                    AuditActions.KNOWLEDGE_ACL_CHANGED,
                    Map.of("visibility", visibilityValues(), "previousVisibility", visibilityValues())),
            Map.entry(
                    AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ,
                    Map.of(
                            "scope",
                            Set.of("LIST", "DETAIL", "SEARCH", "DOWNLOAD"),
                            "reason",
                            Set.of("MANAGEMENT_READ"),
                            "visibility",
                            visibilityValues())),
            Map.entry(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, Map.of("status", documentStatusValues())),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_CREATED, connectorEnums()),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_UPDATED, connectorEnums()),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_DELETED, connectorEnums()),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED, connectorEnums()),
            Map.entry(
                    AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED,
                    Map.of("type", Set.of(ConnectorType.S3.name()))),
            Map.entry(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED, Map.of("trigger", triggerValues())),
            // M06 编排 Studio：封闭枚举与角色/状态/动作类型白名单；不属于枚举的原因码由写入方保证。
            Map.entry(AuditActions.WORKFLOW_CREATED, Map.of("sourceTemplateType", Set.of("BUILT_IN", "USER"))),
            Map.entry(
                    AuditActions.WORKFLOW_PUBLISHED,
                    Map.of(
                            "runScope",
                            Set.of(
                                    WorkflowRunScope.ONLY_SELF.name(),
                                    WorkflowRunScope.DEPARTMENT.name(),
                                    WorkflowRunScope.TENANT.name()))),
            Map.entry(
                    AuditActions.WORKFLOW_DISABLED,
                    Map.of(
                            "status",
                            Set.of(WorkflowDefinitionStatus.ACTIVE.name(), WorkflowDefinitionStatus.DISABLED.name()),
                            "previousStatus",
                            Set.of(WorkflowDefinitionStatus.ACTIVE.name(), WorkflowDefinitionStatus.DISABLED.name()))),
            Map.entry(
                    AuditActions.WORKFLOW_ENABLED,
                    Map.of(
                            "status",
                            Set.of(WorkflowDefinitionStatus.ACTIVE.name(), WorkflowDefinitionStatus.DISABLED.name()),
                            "previousStatus",
                            Set.of(WorkflowDefinitionStatus.ACTIVE.name(), WorkflowDefinitionStatus.DISABLED.name()))),
            Map.entry(
                    AuditActions.WORKFLOW_RUN_COMPLETED,
                    Map.of(
                            "status",
                            Set.of(
                                    WorkflowRunStatus.SUCCEEDED.name(),
                                    WorkflowRunStatus.FAILED.name(),
                                    WorkflowRunStatus.CANCELLED.name()))),
            Map.entry(AuditActions.WORKFLOW_RUN_CANCELLED, Map.of("previousStatus", runStatusValues())),
            Map.entry(
                    AuditActions.WORKFLOW_RUN_ADMIN_READ,
                    Map.of("scope", Set.of("LIST", "DETAIL"), "reason", Set.of("MANAGEMENT_READ"))),
            Map.entry(AuditActions.WORKFLOW_APPROVAL_REQUESTED, Map.of("actionKind", approvalActionKindValues())),
            Map.entry(
                    AuditActions.WORKFLOW_APPROVAL_DECIDED,
                    Map.of(
                            "decision",
                            Set.of(WorkflowApprovalDecision.APPROVE.name(), WorkflowApprovalDecision.REJECT.name()))),
            Map.entry(AuditActions.WORKFLOW_FEATURE_CHANGED, Map.of("source", Set.of("DEPLOYMENT_CONFIG"))));

    /** 任何情况下都不允许出现的敏感键（大小写不敏感）。 */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "password",
            "token",
            "cookie",
            "secret",
            "credential",
            "prompt",
            "content",
            "email",
            "phone",
            "apikey",
            "ciphertext",
            "authorization",
            "baseurl");

    /** details 单值默认最大长度，避免把原文写入审计。 */
    private static final int MAX_VALUE_LENGTH = 200;

    /**
     * 动作级字符串长度覆盖；未登记的键使用 {@link #MAX_VALUE_LENGTH}。
     *
     * <p>M06 审批理由 {@code workflow.approval.decided.decisionComment} 按契约、ADR-0024 §8.1/§8.3 与
     * {@code workflow_approval.decision_comment VARCHAR(500)} 允许 1–500 字符；读取端复用同一策略，
     * 避免 201–500 字符出现“写得进、读不出”（REV-TASK-0219-001 F-001、REV-TASK-0226-001 F-002）。
     */
    private static final Map<String, Map<String, Integer>> VALUE_LENGTH_LIMITS =
            Map.of(AuditActions.WORKFLOW_APPROVAL_DECIDED, Map.of("decisionComment", 500));

    /** 未在 {@link #ARRAY_LIMITS} 登记时的数组元素上限；ADR-0021 §7.4 规定路由链最多 20 个 UUID。 */
    private static final int DEFAULT_MAX_ARRAY_ELEMENTS = 20;

    /** 单个数组元素总长度上限；20 个 UUID 与 50 个 UUID 都低于该上限（ADR-0021 §7.4）。 */
    private static final int MAX_ARRAY_TOTAL_LENGTH = 2048;

    /** 工具类禁止实例化。 */
    private AuditDetailsPolicy() {}

    /**
     * 校验动作与 details 是否符合白名单。
     *
     * @param action 审计动作名
     * @param details 待写入字段
     * @throws IllegalArgumentException 出现未授权键、敏感键或非法值类型
     */
    public static void validate(String action, Map<String, Object> details) {
        Map<String, Object> safeDetails = details == null ? Map.of() : details;
        Set<String> allowed = ALLOWED_KEYS.getOrDefault(action, Set.of());
        Set<String> nullable = NULLABLE_KEYS.getOrDefault(action, Set.of());
        Set<String> arrays = ARRAY_KEYS.getOrDefault(action, Set.of());
        for (Map.Entry<String, Object> entry : safeDetails.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("audit detail key must not be blank");
            }
            // 白名单优先：promptTokens/completionTokens/sessionIdHash/contentType 等合法指标不能被
            // "token"/"content" 子串规则误杀；只有白名单外的键才执行敏感词检查（ADR-0020 F-003）。
            if (allowed.contains(key)) {
                validateValue(
                        action,
                        key,
                        entry.getValue(),
                        nullable.contains(key),
                        arrays.contains(key),
                        arrays.contains(key) ? arrayLimit(action, key) : 0);
                validateEnumValue(action, key, entry.getValue());
                continue;
            }
            String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_KEYS.stream().anyMatch(normalizedKey::contains)) {
                throw new IllegalArgumentException("audit detail key is forbidden: " + key);
            }
            throw new IllegalArgumentException("audit detail key is not allowed for " + action + ": " + key);
        }
        validateRequiredDetails(action, safeDetails);
    }

    /**
     * 校验动作级必填键与条件 nullable。
     *
     * <p>{@code knowledge.document.admin_read}：{@code scope} 与 {@code reason} 必填；
     * {@code documentId} 在非 LIST 作用域必填（LIST 是管理视图，不指向单个文档）；
     * {@code resultCount} 在 LIST/SEARCH 必填且为非负数。其他动作只依赖白名单，不额外要求键。
     *
     * @param action 审计动作名
     * @param details 已通过白名单类型校验的字段
     */
    private static void validateRequiredDetails(String action, Map<String, Object> details) {
        if (AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED.equals(action)) {
            if (!(details.get("documentId") instanceof String documentId) || documentId.isBlank()) {
                throw new IllegalArgumentException("audit detail key is required: documentId");
            }
            if (!(details.get("force") instanceof Boolean)) {
                throw new IllegalArgumentException("audit detail value must be a boolean: force");
            }
            if (!(details.get("status") instanceof String status) || status.isBlank()) {
                throw new IllegalArgumentException("audit detail key is required: status");
            }
            return;
        }
        // M06 启动观测：四个键缺一不可，避免“关闭编排”没有可追溯的实例与时间（ADR-0024 §4.8、OPS-M06-019）。
        if (AuditActions.WORKFLOW_FEATURE_CHANGED.equals(action)) {
            if (!(details.get("enabled") instanceof Boolean)) {
                throw new IllegalArgumentException("audit detail value must be a boolean: enabled");
            }
            if (!"DEPLOYMENT_CONFIG".equals(details.get("source"))) {
                throw new IllegalArgumentException("audit detail source must be DEPLOYMENT_CONFIG");
            }
            if (!(details.get("instanceId") instanceof String instanceId) || instanceId.isBlank()) {
                throw new IllegalArgumentException("audit detail key is required: instanceId");
            }
            if (!(details.get("observedAt") instanceof String observedAt) || observedAt.isBlank()) {
                throw new IllegalArgumentException("audit detail key is required: observedAt");
            }
            return;
        }
        if (!AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ.equals(action)) {
            return;
        }
        Object scopeValue = details.get("scope");
        if (!(scopeValue instanceof String scope)) {
            throw new IllegalArgumentException("audit detail value must be a string: scope");
        }
        if (!details.containsKey("reason")) {
            throw new IllegalArgumentException("audit detail key is required: reason");
        }
        if ("LIST".equals(scope) || "SEARCH".equals(scope)) {
            Object resultCount = details.get("resultCount");
            if (!(resultCount instanceof Number number) || number.longValue() < 0) {
                throw new IllegalArgumentException("audit detail value must be a non-negative number: resultCount");
            }
        }
        if (!"LIST".equals(scope) && details.get("documentId") == null) {
            throw new IllegalArgumentException("audit detail key is required for scope=" + scope + ": documentId");
        }
        if (!"LIST".equals(scope) && details.get("visibility") == null) {
            throw new IllegalArgumentException("audit detail key is required for scope=" + scope + ": visibility");
        }
    }

    /**
     * 校验封闭枚举字段。
     *
     * @param action 审计动作名
     * @param key 字段名
     * @param value 字段值；null 已由 nullable 白名单处理
     */
    private static void validateEnumValue(String action, String key, Object value) {
        if (value == null) {
            return;
        }
        Set<String> allowedValues = ENUM_VALUES.getOrDefault(action, Map.of()).get(key);
        if (allowedValues == null) {
            return;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
        }
        if (!allowedValues.contains(text)) {
            throw new IllegalArgumentException("audit detail value is not allowed for " + action + ": " + key);
        }
    }

    /**
     * 返回动作级数组元素上限。
     *
     * @param action 审计动作名
     * @param key 字段名
     * @return 元素上限
     */
    private static int arrayLimit(String action, String key) {
        return ARRAY_LIMITS.getOrDefault(action, Map.of()).getOrDefault(key, DEFAULT_MAX_ARRAY_ELEMENTS);
    }

    /**
     * 返回动作级字符串长度上限。
     *
     * @param action 审计动作名
     * @param key 字段名
     * @return 长度上限；未登记时为 {@link #MAX_VALUE_LENGTH}
     */
    private static int valueLimit(String action, String key) {
        return VALUE_LENGTH_LIMITS.getOrDefault(action, Map.of()).getOrDefault(key, MAX_VALUE_LENGTH);
    }

    /** 返回知识库文档状态枚举名集合，供重索引审计校验使用。 */
    private static Set<String> documentStatusValues() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (KnowledgeDocumentStatus status : KnowledgeDocumentStatus.values()) {
            values.add(status.name());
        }
        return Set.copyOf(values);
    }

    /** 返回知识库可见范围枚举名集合，供封闭枚举校验使用。 */
    private static Set<String> visibilityValues() {
        return Set.of(
                KnowledgeVisibility.TENANT.name(), KnowledgeVisibility.DEPT.name(), KnowledgeVisibility.USERS.name());
    }

    /** 返回连接器类型/状态枚举名集合，供连接器生命周期动作复用。 */
    private static Map<String, Set<String>> connectorEnums() {
        return Map.of(
                "type", Set.of(ConnectorType.S3.name()),
                "status",
                        Set.of(
                                ConnectorStatus.ACTIVE.name(),
                                ConnectorStatus.PAUSED.name(),
                                ConnectorStatus.ERROR.name()),
                "previousStatus",
                        Set.of(
                                ConnectorStatus.ACTIVE.name(),
                                ConnectorStatus.PAUSED.name(),
                                ConnectorStatus.ERROR.name()));
    }

    /** 返回连接器同步触发方式枚举名集合。 */
    private static Set<String> triggerValues() {
        return Set.of(
                ConnectorSyncTrigger.MANUAL.name(),
                ConnectorSyncTrigger.SCHEDULE.name(),
                ConnectorSyncTrigger.RETRY.name());
    }

    /** 返回运行状态枚举名集合，供取消动作的 previousStatus 校验使用。 */
    private static Set<String> runStatusValues() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (WorkflowRunStatus status : WorkflowRunStatus.values()) {
            values.add(status.name());
        }
        return Set.copyOf(values);
    }

    /** 返回审批动作类型枚举名集合，供 request 动作的 actionKind 校验使用。 */
    private static Set<String> approvalActionKindValues() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (WorkflowApprovalActionKind kind : WorkflowApprovalActionKind.values()) {
            values.add(kind.name());
        }
        return Set.copyOf(values);
    }

    /**
     * 校验 details 值只包含可安全落库的基本类型或动作级受限数组。
     *
     * @param action 审计动作名（用于数组上限日志与排障）
     * @param key 字段名
     * @param value 字段值
     * @param allowNull 是否允许 JSON null（仅动作级 nullable 白名单）
     * @param allowArray 是否允许受限 UUID 数组（仅动作级数组白名单）
     * @param maxArrayElements 数组元素上限（动作级）
     */
    private static void validateValue(
            String action, String key, Object value, boolean allowNull, boolean allowArray, int maxArrayElements) {
        if (value == null) {
            if (allowNull) {
                return;
            }
            throw new IllegalArgumentException("audit detail value must not be null: " + key);
        }
        if (value instanceof String text) {
            if (text.codePointCount(0, text.length()) > valueLimit(action, key)) {
                throw new IllegalArgumentException("audit detail value is too long: " + key);
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> list) {
            if (!allowArray) {
                throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
            }
            validateUuidArray(action, key, list, maxArrayElements);
            return;
        }
        throw new IllegalArgumentException("audit detail value type is not allowed: " + key);
    }

    /**
     * 校验受限 UUID 数组：元素上限按动作登记（默认 20，ACL 变更 50），元素总长度不超过 2 KiB。
     *
     * <p>只允许 {@link List}，不接受 Map/嵌套数组或任意 JSON；非法类型继续 fail-closed。
     *
     * @param action 审计动作名
     * @param key 字段名
     * @param values 数组值
     * @param maxElements 元素上限
     */
    private static void validateUuidArray(String action, String key, List<?> values, int maxElements) {
        if (values.size() > maxElements) {
            throw new IllegalArgumentException("audit detail array has too many elements for " + action + ": " + key);
        }
        int totalLength = 0;
        for (Object element : values) {
            if (!(element instanceof String text)) {
                throw new IllegalArgumentException("audit detail array element type is not allowed: " + key);
            }
            try {
                UUID.fromString(text);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("audit detail array element must be a UUID: " + key, exception);
            }
            totalLength += text.length();
        }
        if (totalLength > MAX_ARRAY_TOTAL_LENGTH) {
            throw new IllegalArgumentException("audit detail array is too long: " + key);
        }
    }
}
