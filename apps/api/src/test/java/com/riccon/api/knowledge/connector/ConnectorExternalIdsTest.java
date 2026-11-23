package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.knowledge.connector.domain.ConnectorExternalIds;
import org.junit.jupiter.api.Test;

/**
 * externalId 展示规则：最后两段路径或最后 32 字符。
 */
class ConnectorExternalIdsTest {

    @Test
    void display_usesLastTwoSegmentsAndTruncatesTo32() {
        assertThat(ConnectorExternalIds.display(null)).isNull();
        assertThat(ConnectorExternalIds.display("")).isEmpty();
        assertThat(ConnectorExternalIds.display("plain.pdf")).isEqualTo("plain.pdf");
        assertThat(ConnectorExternalIds.display("a/b")).isEqualTo("a/b");
        assertThat(ConnectorExternalIds.display("a/b/c.pdf")).isEqualTo("b/c.pdf");
        String longSegment = "x".repeat(80) + ".pdf";
        String display = ConnectorExternalIds.display("a/" + longSegment);
        assertThat(display).hasSize(32).endsWith(".pdf").isNotEqualTo(longSegment);
    }
}
