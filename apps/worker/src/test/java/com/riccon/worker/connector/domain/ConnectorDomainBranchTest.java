package com.riccon.worker.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 连接器领域辅助分支矩阵：空值、截断、glob/预算边界、记录访问器与注册表查询。
 */
class ConnectorDomainBranchTest {

    @Test
    void contentTypes_handlesNullBlankTrailingDotAndLongTitles() {
        assertThat(ConnectorContentTypes.extension(null)).isEmpty();
        assertThat(ConnectorContentTypes.extension("a/b.")).isEmpty();
        assertThat(ConnectorContentTypes.extension("a/b")).isEmpty();
        assertThat(ConnectorContentTypes.extension("a/b/c.MD")).isEqualTo("md");
        assertThat(ConnectorContentTypes.supported("pdf", null)).isTrue();
        assertThat(ConnectorContentTypes.supported("pdf", List.of()))
                .as("空配置使用部署白名单")
                .isTrue();
        assertThat(ConnectorContentTypes.supported("txt", List.of("pdf", "txt")))
                .isTrue();
        assertThat(ConnectorContentTypes.supported("pdf", java.util.Arrays.asList(null, " pdf ", "zip")))
                .isTrue();
        assertThat(ConnectorContentTypes.supported("zip", java.util.Arrays.asList(null, "pdf")))
                .isFalse();
        assertThat(ConnectorContentTypes.title(null)).isEqualTo("connector-document");
        assertThat(ConnectorContentTypes.title("a/   ")).isEqualTo("connector-document");
        assertThat(ConnectorContentTypes.title("a/b.pdf")).isEqualTo("b.pdf");
        String longName = "x".repeat(400);
        assertThat(ConnectorContentTypes.title("a/" + longName))
                .hasSize(300)
                .isEqualTo(longName.substring(longName.length() - 300));
        UUID documentId = UUID.randomUUID();
        assertThat(ConnectorContentTypes.objectKey("tenant-a", documentId, Instant.parse("2025-12-31T23:30:00Z")))
                .contains("/connector/2025/");
    }

    @Test
    void globMatcher_handlesNullBlankAndRegexEscaping() {
        assertThat(ConnectorGlobMatcher.excluded(null, "a.pdf")).isFalse();
        assertThat(ConnectorGlobMatcher.excluded(List.of(), "a.pdf")).isFalse();
        assertThat(ConnectorGlobMatcher.excluded(List.of("**/skip/**"), null)).isFalse();
        assertThat(ConnectorGlobMatcher.excluded(java.util.Arrays.asList(null, " ", "**/skip/**"), "a/skip/b.pdf"))
                .isTrue();
        assertThat(ConnectorGlobMatcher.excluded(List.of("**/skip/**"), "a/keep/b.pdf"))
                .isFalse();
        assertThat(ConnectorGlobMatcher.matches("docs/a.b", "docs/a.b")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("docs/?.pdf", "docs/a.pdf")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("docs/*.pdf", "docs/a.pdf")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("docs/**.pdf", "docs/a/b.pdf")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("docs/a+b.pdf", "docs/a+b.pdf")).isTrue();
    }

    @Test
    void syncBudget_coversTimeoutObjectAndByteBoundaries() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ConnectorSyncBudget budget = new ConnectorSyncBudget(start, Duration.ofSeconds(10), 1, 5);
        assertThat(budget.expired(start)).isFalse();
        assertThat(budget.expired(start.plusSeconds(10))).isTrue();
        assertThat(budget.remaining(start.plusSeconds(10))).isEqualTo(Duration.ZERO);
        assertThat(budget.remaining(start.plusSeconds(11))).isEqualTo(Duration.ZERO);
        assertThat(budget.addObject()).isTrue();
        assertThat(budget.addObject()).isFalse();
        assertThat(budget.truncated()).isTrue();
        assertThat(budget.addBytes(-1)).isFalse();
        ConnectorSyncBudget byteBudget = new ConnectorSyncBudget(start, Duration.ofSeconds(10), 10, 5);
        assertThat(byteBudget.addBytes(5)).isTrue();
        assertThat(byteBudget.addBytes(1)).isFalse();
        assertThat(byteBudget.truncated()).isTrue();
    }

    @Test
    void syncResult_failedAndRecordAccessors() {
        Instant now = Instant.now();
        UUID connectorId = UUID.randomUUID();
        ConnectorRecord noCredential = new ConnectorRecord(
                connectorId,
                "name",
                ConnectorType.S3,
                ConnectorStatus.PAUSED,
                "endpoint",
                new ConnectorConfig("bucket", "prefix", "us-east-1", null, null),
                KnowledgeVisibility.TENANT,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                now,
                null);
        ConnectorRecord emptyCredential = new ConnectorRecord(
                connectorId,
                "name",
                ConnectorType.S3,
                ConnectorStatus.PAUSED,
                "endpoint",
                new ConnectorConfig(null, null, null, List.of(), List.of()),
                KnowledgeVisibility.TENANT,
                null,
                new byte[0],
                "key",
                false,
                null,
                null,
                null,
                now,
                null);
        ConnectorRecord withCredential = new ConnectorRecord(
                connectorId,
                "name",
                ConnectorType.S3,
                ConnectorStatus.PAUSED,
                "endpoint",
                new ConnectorConfig("bucket", "prefix", null, null, null),
                KnowledgeVisibility.TENANT,
                null,
                "cipher".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "key",
                false,
                null,
                null,
                null,
                now,
                null);
        assertThat(noCredential.hasCredential()).isFalse();
        assertThat(emptyCredential.hasCredential()).isFalse();
        assertThat(withCredential.hasCredential()).isTrue();
        assertThat(withCredential.config().effectiveExtensions())
                .containsExactlyElementsOf(ConnectorConfig.DEFAULT_EXTENSIONS);

        ConnectorSyncResult failed = new ConnectorSyncResult(
                ConnectorSyncStatus.ERROR,
                false,
                new ConnectorSyncSummary(1, 0, 0, 0, 1, 0, false),
                "E",
                ConnectorFailureKind.AUTH,
                true,
                1);
        ConnectorSyncResult success = new ConnectorSyncResult(
                ConnectorSyncStatus.SUCCESS,
                true,
                new ConnectorSyncSummary(1, 1, 0, 0, 0, 0, true),
                null,
                null,
                false,
                1);
        assertThat(failed.failed()).isTrue();
        assertThat(success.failed()).isFalse();
    }

    @Test
    void documentRowAndRegistry_queryBranches() {
        Instant now = Instant.now();
        UUID documentId = UUID.randomUUID();
        ConnectorDocumentRow active = new ConnectorDocumentRow(
                documentId, "checksum", KnowledgeDocumentStatus.PENDING, KnowledgeVisibility.TENANT, 1, now, null);
        ConnectorDocumentRow deleted = new ConnectorDocumentRow(
                documentId, "checksum", KnowledgeDocumentStatus.PENDING, KnowledgeVisibility.TENANT, 1, now, now);
        ConnectorDocumentRow deletedStatus = new ConnectorDocumentRow(
                documentId, "checksum", KnowledgeDocumentStatus.DELETED, KnowledgeVisibility.TENANT, 1, now, null);
        assertThat(active.active()).isTrue();
        assertThat(deleted.active()).isFalse();
        assertThat(deletedStatus.active()).isFalse();

        ConnectorDataKeyRow nullKey = new ConnectorDataKeyRow(null, null, "AES-256-GCM", "mk");
        assertThat(nullKey.wrappedDek()).isNull();
        assertThat(nullKey.wrapNonce()).isNull();

        ConnectorEndpointRegistry empty = new ConnectorEndpointRegistry(List.of());
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.findById(null)).isEmpty();
        assertThat(empty.findById("missing")).isEmpty();
        assertThat(empty.containsHost(null)).isFalse();
        assertThat(empty.containsHost("missing")).isFalse();
    }
}
