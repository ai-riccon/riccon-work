package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.knowledge.ConnectorItemStatus;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncTrigger;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeIndexMode;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M05 权限码、审计动作/目标、领域枚举与 details 白名单的纯单元测试（B1 词汇预置）。
 *
 * <p>不启动 Spring 或数据库：迁移测试验证数据库行与约束，本测试验证后续 B2–B7 依赖的稳定常量与
 * fail-closed 校验语义，避免“迁移有行但代码常量缺失”或“数组/nullable 放宽为任意 JSON”。
 */
class V12VocabularyTest {

    /** M05 权限码值必须与迁移/OpenAPI 的 x-riccon-permission 一致，且只授予租户管理员。 */
    @Test
    @DisplayName("M05 权限码与角色定义预置完整")
    void permissionCodesAndRoles_includeM05() {
        assertThat(PermissionCodes.KNOWLEDGE_ACL_MANAGE).isEqualTo("knowledge.acl.manage");
        assertThat(PermissionCodes.KNOWLEDGE_CONNECTOR_READ).isEqualTo("knowledge.connector.read");
        assertThat(PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE).isEqualTo("knowledge.connector.manage");

        RoleDefinition tenantAdmin = RoleDefinitions.tenantRole(RoleCodes.TENANT_ADMIN);
        assertThat(tenantAdmin.permissions())
                .contains(
                        PermissionCodes.KNOWLEDGE_ACL_MANAGE,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_READ,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        RoleDefinition member = RoleDefinitions.tenantRole(RoleCodes.MEMBER);
        assertThat(member.permissions())
                .doesNotContain(
                        PermissionCodes.KNOWLEDGE_ACL_MANAGE,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_READ,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
        assertThat(RoleDefinitions.platformAdminRole().permissions())
                .doesNotContain(
                        PermissionCodes.KNOWLEDGE_ACL_MANAGE,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_READ,
                        PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE);
    }

    /** M05 审计动作、目标类型与事件类型必须覆盖 ADR-0023 的生命周期。 */
    @Test
    @DisplayName("M05 审计动作、目标与事件类型完整")
    void auditActionsAndTargets_includeM05() {
        assertThat(AuditActions.KNOWLEDGE_DOCUMENT_ADDED).isEqualTo("knowledge.document.added");
        assertThat(AuditActions.KNOWLEDGE_DOCUMENT_DELETED).isEqualTo("knowledge.document.deleted");
        assertThat(AuditActions.KNOWLEDGE_ACL_CHANGED).isEqualTo("knowledge.acl.changed");
        assertThat(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ).isEqualTo("knowledge.document.admin_read");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_CREATED).isEqualTo("knowledge.connector.created");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_UPDATED).isEqualTo("knowledge.connector.updated");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_DELETED).isEqualTo("knowledge.connector.deleted");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_STATUS_CHANGED).isEqualTo("knowledge.connector.status.changed");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED)
                .isEqualTo("knowledge.connector.credential.changed");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED).isEqualTo("knowledge.connector.sync.started");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED).isEqualTo("knowledge.connector.sync.completed");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_FAILED).isEqualTo("knowledge.connector.sync.failed");
        assertThat(AuditActions.KNOWLEDGE_CONNECTOR_SYNC_RETRIED).isEqualTo("knowledge.connector.sync.retried");

        assertThat(AuditTargetType.valueOf("KNOWLEDGE_CONNECTOR")).isNotNull();
        assertThat(EventTypes.KNOWLEDGE_CONNECTOR_SYNC_REQUESTED)
                .isEqualTo("riccon.knowledge.connector.sync.requested.v1");
    }

    /** 知识库领域枚举与数据库 CHECK 取值一致，避免 B3–B7 写字符串字面量。 */
    @Test
    @DisplayName("M05 知识库领域枚举取值稳定")
    void knowledgeEnums_haveStableValues() {
        assertThat(KnowledgeVisibility.values()).extracting(Enum::name).containsExactly("TENANT", "DEPT", "USERS");
        assertThat(ParseEngine.values())
                .extracting(Enum::name)
                .containsExactly("PDFBOX", "POI", "TESSERACT", "RAPIDOCR", "NONE");
        assertThat(KnowledgeIndexMode.values()).extracting(Enum::name).containsExactly("FULL", "ACL_ONLY", "DELETE");
        assertThat(ConnectorType.values()).extracting(Enum::name).containsExactly("S3");
        assertThat(ConnectorStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "PAUSED", "ERROR");
        assertThat(ConnectorSyncStatus.values()).extracting(Enum::name).containsExactly("SUCCESS", "PARTIAL", "ERROR");
        assertThat(ConnectorItemStatus.values())
                .extracting(Enum::name)
                .containsExactly("PENDING", "SYNCED", "SKIPPED", "FAILED", "DELETED");
        assertThat(ConnectorSyncTrigger.values()).extracting(Enum::name).containsExactly("MANUAL", "SCHEDULE", "RETRY");
    }

    /** ACL 变更允许最多 50 个 UUID 的数组，允许空数组；超过上限 fail-closed。 */
    @Test
    @DisplayName("ACL 审计 details 支持最多 50 个 UUID 数组")
    void aclDetails_allowFiftyUuidArrays() {
        List<String> fifty = uuids(50);
        Map<String, Object> details = new HashMap<>();
        details.put("visibility", KnowledgeVisibility.USERS.name());
        details.put("previousVisibility", KnowledgeVisibility.TENANT.name());
        details.put("departmentId", null);
        details.put("addedUserCount", 50);
        details.put("removedUserCount", 0);
        details.put("addedUserIds", fifty);
        details.put("removedUserIds", List.of());
        details.put("aclUserCount", 51);
        details.put("truncated", false);
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_ACL_CHANGED, details))
                .doesNotThrowAnyException();

        details.put("addedUserIds", uuids(51));
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_ACL_CHANGED, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many");

        details.put("addedUserIds", List.of("not-a-uuid"));
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_ACL_CHANGED, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");

        Map<String, Object> badVisibility = new HashMap<>(details);
        badVisibility.put("addedUserIds", List.of());
        badVisibility.put("visibility", "PUBLIC");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_ACL_CHANGED, badVisibility))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** admin_read 的 LIST 允许空 documentId 且 resultCount 必填；其他作用域必须带 documentId。 */
    @Test
    @DisplayName("admin_read 审计 details 校验作用域与必填键")
    void adminReadDetails_enforceScopeAndRequiredKeys() {
        Map<String, Object> list = new HashMap<>();
        list.put("scope", "LIST");
        list.put("documentId", null);
        list.put("visibility", null);
        list.put("aclUserCount", 0);
        list.put("resultCount", 12);
        list.put("reason", "MANAGEMENT_READ");
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, list))
                .doesNotThrowAnyException();

        Map<String, Object> listWithoutCount = new HashMap<>(list);
        listWithoutCount.remove("resultCount");
        assertThatThrownBy(
                        () -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, listWithoutCount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resultCount");

        Map<String, Object> detail = new HashMap<>();
        detail.put("scope", "DETAIL");
        detail.put("documentId", UUID.randomUUID().toString());
        detail.put("visibility", KnowledgeVisibility.USERS.name());
        detail.put("aclUserCount", 2);
        detail.put("reason", "MANAGEMENT_READ");
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, detail))
                .doesNotThrowAnyException();

        detail.put("documentId", null);
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, detail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");

        Map<String, Object> badReason = new HashMap<>(list);
        badReason.put("reason", "CURIOSITY");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, badReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        Map<String, Object> badScope = new HashMap<>(list);
        badScope.put("scope", "EVERYTHING");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, badScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        // 不含标题/片段/正文：未登记键与敏感键都 fail-closed。
        Map<String, Object> withTitle = new HashMap<>(list);
        withTitle.put("title", "机密标题");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, withTitle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        Map<String, Object> withContent = new HashMap<>(list);
        withContent.put("content", "原文");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, withContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    /** 文档删除 reason 只允许 USER/RETENTION/CONNECTOR；新增文档允许来源相关键为空。 */
    @Test
    @DisplayName("文档新增/删除审计 details 使用封闭枚举")
    void documentDetails_useClosedEnums() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                        Map.of("sourceType", "UPLOAD", "visibility", "TENANT", "reason", "RETENTION")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_DOCUMENT_DELETED,
                        Map.of("sourceType", "UPLOAD", "visibility", "TENANT", "reason", "CLEANUP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        Map<String, Object> added = new HashMap<>();
        added.put("sourceType", "CONNECTOR");
        added.put("fileId", null);
        added.put("visibility", "DEPT");
        added.put("departmentId", UUID.randomUUID().toString());
        added.put("connectorId", UUID.randomUUID().toString());
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADDED, added))
                .doesNotThrowAnyException();

        added.put("sourceType", "SCRAPE");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADDED, added))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    /** 连接器生命周期 details 只允许配置定位、状态与计数，不含凭据/桶内容/对象 key。 */
    @Test
    @DisplayName("连接器审计 details 白名单可用且拒绝敏感键")
    void connectorDetails_useWhitelists() {
        Map<String, Object> created = new HashMap<>();
        created.put("connectorId", UUID.randomUUID().toString());
        created.put("type", "S3");
        created.put("endpointId", "demo");
        created.put("bucket", "docs");
        created.put("prefix", "kb/");
        created.put("status", "PAUSED");
        created.put("previousStatus", null);
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_CONNECTOR_CREATED, created))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                        Map.of(
                                "connectorId", UUID.randomUUID().toString(),
                                "syncId", UUID.randomUUID().toString(),
                                "trigger", "MANUAL")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED,
                        Map.of(
                                "connectorId", UUID.randomUUID().toString(),
                                "syncId", UUID.randomUUID().toString(),
                                "scanned", 10,
                                "created", 3,
                                "updated", 2,
                                "skipped", 4,
                                "failed", 1,
                                "deleted", 0,
                                "durationMs", 1234,
                                "complete", true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED,
                        Map.of(
                                "connectorId", UUID.randomUUID().toString(),
                                "type", "S3",
                                "credentialKeyId", "key-1",
                                "maskedHint", "****a1b2")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_CREATED,
                        Map.of(
                                "connectorId", UUID.randomUUID().toString(),
                                "type", "GCS",
                                "status", "PAUSED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                        Map.of(
                                "connectorId", UUID.randomUUID().toString(),
                                "syncId", UUID.randomUUID().toString(),
                                "trigger", "CRON")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_UPDATED,
                        Map.of("connectorId", UUID.randomUUID().toString(), "externalId", "docs/a.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_CREDENTIAL_CHANGED,
                        Map.of("connectorId", UUID.randomUUID().toString(), "credential", "sk-live")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    /** 生成指定数量的随机 UUID 字符串。 */
    private static List<String> uuids(int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(UUID.randomUUID().toString());
        }
        return values;
    }
}
