package com.riccon.api.knowledge.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库审计事件构造。
 *
 * <p>本类在调用 {@link AuditEvent#create} 前显式检查 ADR-0023 第 5.6 节要求的必填键
 * （REV-TASK-0164-001 F-009）：删除必须带 {@code reason}、新增必须带 {@code sourceType}/{@code visibility}、
 * ACL 变更必须带 {@code previousVisibility}；缺字段直接失败，不写入不完整审计。
 *
 * <p>details 只放 ID、枚举与计数，不含标题、片段、正文、名单展示名、对象 key 或凭据。
 */
public final class KnowledgeAuditEvents {

    /** ACL 变更审计中 UUID 数组上限（ADR-0023 第 5.6 节）。 */
    private static final int MAX_ACL_USER_IDS = 50;

    /** 工具类禁止实例化。 */
    private KnowledgeAuditEvents() {}

    /**
     * 构造文档新增审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param documentId 文档 ID
     * @param sourceType 来源类型
     * @param visibility 可见范围
     * @param departmentId 部门，可空
     * @param fileId 来源文件，可空
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可空
     * @return 审计事件
     */
    public static AuditEvent added(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID documentId,
            String sourceType,
            KnowledgeVisibility visibility,
            UUID departmentId,
            UUID fileId,
            String traceId,
            String requestId) {
        requireNonBlank(sourceType, "sourceType");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceType", sourceType);
        details.put("fileId", fileId == null ? null : fileId.toString());
        details.put("visibility", visibility.name());
        details.put("departmentId", departmentId == null ? null : departmentId.toString());
        return event(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.KNOWLEDGE_DOCUMENT_ADDED,
                documentId,
                traceId,
                requestId,
                details);
    }

    /**
     * 构造文档删除审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者
     * @param document 删除前文档快照
     * @param reason 删除原因（{@code USER}/{@code RETENTION}/{@code CONNECTOR}）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可空
     * @return 审计事件
     */
    public static AuditEvent deleted(
            Instant now,
            String tenantId,
            AuditActorType actorType,
            String actorId,
            KnowledgeDocumentRecord document,
            String reason,
            String traceId,
            String requestId) {
        Objects.requireNonNull(document, "document must not be null");
        requireNonBlank(reason, "reason");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceType", document.sourceType().name());
        details.put("visibility", document.visibility().name());
        details.put("reason", reason);
        return event(
                now,
                tenantId,
                actorType,
                actorId,
                AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                document.id(),
                traceId,
                requestId,
                details);
    }

    /**
     * 构造 ACL 变更审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param documentId 文档 ID
     * @param previousVisibility 变更前可见范围
     * @param visibility 变更后可见范围
     * @param departmentId 变更后部门，可空
     * @param addedUserIds 新增用户
     * @param removedUserIds 移除用户
     * @param aclUserCount 变更后显式人数
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可空
     * @return 审计事件
     */
    public static AuditEvent aclChanged(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID documentId,
            KnowledgeVisibility previousVisibility,
            KnowledgeVisibility visibility,
            UUID departmentId,
            Set<UUID> addedUserIds,
            Set<UUID> removedUserIds,
            long aclUserCount,
            String traceId,
            String requestId) {
        Objects.requireNonNull(previousVisibility, "previousVisibility must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("visibility", visibility.name());
        details.put("previousVisibility", previousVisibility.name());
        details.put("departmentId", departmentId == null ? null : departmentId.toString());
        details.put("addedUserCount", addedUserIds.size());
        details.put("removedUserCount", removedUserIds.size());
        details.put("aclUserCount", aclUserCount);
        if (addedUserIds.size() <= MAX_ACL_USER_IDS && removedUserIds.size() <= MAX_ACL_USER_IDS) {
            details.put("addedUserIds", uuidStrings(addedUserIds));
            details.put("removedUserIds", uuidStrings(removedUserIds));
        } else {
            // 超过 50 个 UUID 时只保留计数，避免把大名单写入审计（ADR-0023 第 5.6 节）。
            details.put("truncated", true);
        }
        return event(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.KNOWLEDGE_ACL_CHANGED,
                documentId,
                traceId,
                requestId,
                details);
    }

    /**
     * 构造重索引审计。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param documentId 文档 ID
     * @param force 是否整本重做
     * @param status 受理时文档状态
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可空
     * @return 审计事件
     */
    public static AuditEvent reindexed(
            Instant now,
            String tenantId,
            UUID actorId,
            UUID documentId,
            boolean force,
            KnowledgeDocumentStatus status,
            String traceId,
            String requestId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", documentId.toString());
        details.put("force", force);
        details.put("status", status.name());
        return event(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED,
                documentId,
                traceId,
                requestId,
                details);
    }

    /**
     * 构造管理性读取审计。
     *
     * <p>{@code scope=LIST} 允许多个文档、没有单一 documentId/visibility；其他作用域必须带目标文档与
     * 当时可见范围。{@code resultCount} 只在 LIST/SEARCH 必填。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorId 操作者
     * @param scope 作用域
     * @param documentId 文档 ID，LIST 可空
     * @param visibility 可见范围，LIST 可空
     * @param aclUserCount 显式人数，可空
     * @param resultCount 结果数，LIST/SEARCH 必填
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可空
     * @return 审计事件
     */
    public static AuditEvent adminRead(
            Instant now,
            String tenantId,
            UUID actorId,
            String scope,
            UUID documentId,
            KnowledgeVisibility visibility,
            Long aclUserCount,
            Long resultCount,
            String traceId,
            String requestId) {
        requireNonBlank(scope, "scope");
        boolean list = "LIST".equals(scope);
        if (!list && documentId == null) {
            throw new IllegalArgumentException("documentId is required for admin_read scope=" + scope);
        }
        if (!list && visibility == null) {
            throw new IllegalArgumentException("visibility is required for admin_read scope=" + scope);
        }
        if (("LIST".equals(scope) || "SEARCH".equals(scope)) && resultCount == null) {
            throw new IllegalArgumentException("resultCount is required for admin_read scope=" + scope);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", scope);
        details.put("documentId", documentId == null ? null : documentId.toString());
        details.put("visibility", visibility == null ? null : visibility.name());
        if (aclUserCount != null) {
            details.put("aclUserCount", aclUserCount);
        }
        if (resultCount != null) {
            details.put("resultCount", resultCount);
        }
        details.put("reason", "MANAGEMENT_READ");
        return event(
                now,
                tenantId,
                AuditActorType.USER,
                actorId.toString(),
                AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ,
                documentId,
                traceId,
                requestId,
                details);
    }

    /**
     * 统一构造审计事件。
     *
     * @param now 事件时间
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者
     * @param action 动作
     * @param documentId 目标文档
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     * @param details details
     * @return 审计事件
     */
    private static AuditEvent event(
            Instant now,
            String tenantId,
            AuditActorType actorType,
            String actorId,
            String action,
            UUID documentId,
            String traceId,
            String requestId,
            Map<String, Object> details) {
        return AuditEvent.create(
                now,
                tenantId,
                actorType,
                actorId,
                action,
                AuditTargetType.KNOWLEDGE_DOCUMENT,
                documentId == null ? null : documentId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details);
    }

    /**
     * 把 UUID 集合转为稳定顺序的字符串列表。
     *
     * @param values UUID 集合
     * @return 字符串列表
     */
    private static List<String> uuidStrings(Set<UUID> values) {
        List<String> result = new ArrayList<>(values.size());
        values.stream().map(UUID::toString).sorted().forEach(result::add);
        return result;
    }

    /**
     * 校验必填文本。
     *
     * @param value 值
     * @param field 字段名
     */
    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
