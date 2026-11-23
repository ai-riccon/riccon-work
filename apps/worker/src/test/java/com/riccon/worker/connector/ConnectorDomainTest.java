package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.connector.domain.ConnectorGlobMatcher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 连接器领域辅助：扩展名/MIME/对象 key、glob 与展示规则。
 */
class ConnectorDomainTest {

    @Test
    void contentTypes_whitelistMimeAndObjectKey() {
        assertThat(ConnectorContentTypes.extension("a/b/C.PDF")).isEqualTo("pdf");
        assertThat(ConnectorContentTypes.extension("no-extension")).isEmpty();
        assertThat(ConnectorContentTypes.supported("pdf", List.of())).isTrue();
        assertThat(ConnectorContentTypes.supported("zip", List.of())).isFalse();
        assertThat(ConnectorContentTypes.supported("txt", List.of("pdf", "txt")))
                .isTrue();
        assertThat(ConnectorContentTypes.mimeType("pdf")).isEqualTo("application/pdf");
        assertThat(ConnectorContentTypes.mimeType("md")).isEqualTo("text/markdown");
        assertThat(ConnectorContentTypes.mimeType("zip")).isEqualTo("application/octet-stream");
        assertThat(ConnectorContentTypes.title("a/b/c.pdf")).isEqualTo("c.pdf");
        UUID documentId = UUID.randomUUID();
        assertThat(ConnectorContentTypes.objectKey("tenant-a", documentId, Instant.parse("2026-01-02T03:04:05Z")))
                .isEqualTo("tenants/tenant-a/connector/2026/" + documentId);
    }

    @Test
    void globMatcher_supportsDoubleStarAndSingleStar() {
        assertThat(ConnectorGlobMatcher.matches("*.tmp", "a.tmp")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("*.tmp", "dir/a.tmp")).isFalse();
        assertThat(ConnectorGlobMatcher.matches("**/skip/**", "a/skip/b.pdf")).isTrue();
        assertThat(ConnectorGlobMatcher.matches("docs/?.pdf", "docs/a.pdf")).isTrue();
        assertThat(ConnectorGlobMatcher.excluded(List.of("**/skip/**"), "a/skip/b.pdf"))
                .isTrue();
        assertThat(ConnectorGlobMatcher.excluded(List.of("**/skip/**"), "a/keep/b.pdf"))
                .isFalse();
    }
}
