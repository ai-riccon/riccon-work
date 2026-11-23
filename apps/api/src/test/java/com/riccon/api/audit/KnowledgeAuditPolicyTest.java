package com.riccon.api.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M05 审计策略边界：admin_read 非 LIST 必填 visibility；reindex 白名单与必填键。
 */
class KnowledgeAuditPolicyTest {

    /** admin_read 的 visibility 只在 LIST 可空。 */
    @Test
    @DisplayName("admin_read 非 LIST 必须带 visibility")
    void adminReadVisibilityRequiredOutsideList() {
        Map<String, Object> list = new HashMap<>();
        list.put("scope", "LIST");
        list.put("documentId", null);
        list.put("visibility", null);
        list.put("resultCount", 3);
        list.put("reason", "MANAGEMENT_READ");
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, list))
                .doesNotThrowAnyException();

        Map<String, Object> detail = new HashMap<>();
        detail.put("scope", "DETAIL");
        detail.put("documentId", UUID.randomUUID().toString());
        detail.put("visibility", null);
        detail.put("reason", "MANAGEMENT_READ");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, detail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibility");

        Map<String, Object> search = new HashMap<>();
        search.put("scope", "SEARCH");
        search.put("documentId", UUID.randomUUID().toString());
        search.put("visibility", "DEPT");
        search.put("resultCount", 2);
        search.put("reason", "MANAGEMENT_READ");
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ, search))
                .doesNotThrowAnyException();
    }

    /** reindex 审计白名单与必填键。 */
    @Test
    @DisplayName("reindex 审计 details 白名单")
    void reindexDetailsAreClosed() {
        Map<String, Object> valid =
                Map.of("documentId", UUID.randomUUID().toString(), "force", true, "status", "PENDING");
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, valid))
                .doesNotThrowAnyException();

        Map<String, Object> missingForce = new HashMap<>(valid);
        missingForce.remove("force");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, missingForce))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("force");

        Map<String, Object> badStatus = new HashMap<>(valid);
        badStatus.put("status", "UNKNOWN");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, badStatus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        Map<String, Object> unknown = new HashMap<>(valid);
        unknown.put("title", "标题");
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.KNOWLEDGE_DOCUMENT_REINDEXED, unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}
