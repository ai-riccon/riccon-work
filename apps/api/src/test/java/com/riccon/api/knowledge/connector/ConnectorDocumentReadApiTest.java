package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.knowledge.connector.ConnectorApiTestSupport.ConnectorFixture;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 知识库文档读路径：CONNECTOR 来源、connectorId/externalId 与截图值。
 *
 * <p>连接器导入文档必须能被既有 {@code GET /knowledge/documents} 列表/详情正常读取，不能因
 * {@code KnowledgeSourceType} 缺值抛异常；UPLOAD 文档两字段保持 null。
 */
class ConnectorDocumentReadApiTest extends ConnectorApiTestSupport {

    @Test
    @DisplayName("连接器文档出现在列表/详情：sourceType=CONNECTOR、connectorId 与截断 externalId")
    void connectorDocument_isReadableWithTruncatedExternalId() {
        ConnectorFixture fixture = connectorFixture();
        JsonNode connector = json(
                createConnector(
                        fixture.admin(),
                        "conn-" + UUID.randomUUID().toString().substring(0, 8),
                        "TENANT",
                        null,
                        false,
                        null),
                201);
        String connectorId = connector.path("id").asText();
        String longSegment = "x".repeat(80) + ".pdf";
        String fullExternalId = "folder/sub/" + longSegment;
        UUID connectorDocument = insertConnectorDocument(fixture.tenant(), connectorId, fullExternalId, "c".repeat(64));
        UUID uploadDocument = insertUploadDocument(fixture.tenant());

        JsonNode list = json(
                client.get(
                        "/api/v1/knowledge/documents?page=1&size=100",
                        fixture.admin().sessionCookie(),
                        Map.of()),
                200);
        JsonNode connectorItem = findById(list.path("items"), connectorDocument.toString());
        assertThat(connectorItem.path("sourceType").asText()).isEqualTo("CONNECTOR");
        assertThat(connectorItem.path("connectorId").asText()).isEqualTo(connectorId);
        assertThat(connectorItem.path("externalId").asText())
                .isNotEqualTo(fullExternalId)
                .hasSizeLessThanOrEqualTo(32)
                .endsWith(".pdf");
        assertThat(list.toString()).doesNotContain(fullExternalId);

        JsonNode uploadItem = findById(list.path("items"), uploadDocument.toString());
        assertThat(uploadItem.path("sourceType").asText()).isEqualTo("UPLOAD");
        assertThat(uploadItem.path("connectorId").isNull()).isTrue();
        assertThat(uploadItem.path("externalId").isNull()).isTrue();

        JsonNode detail = json(
                client.get(
                        "/api/v1/knowledge/documents/" + connectorDocument,
                        fixture.admin().sessionCookie(),
                        Map.of()),
                200);
        assertThat(detail.path("sourceType").asText()).isEqualTo("CONNECTOR");
        assertThat(detail.path("connectorId").asText()).isEqualTo(connectorId);
        assertThat(detail.path("externalId").asText()).hasSizeLessThanOrEqualTo(32);
        assertThat(detail.toString()).doesNotContain(fullExternalId);
    }

    /**
     * 按文档 ID 查找列表项。
     *
     * @param items 列表
     * @param documentId 文档 ID
     * @return 列表项
     */
    private static JsonNode findById(JsonNode items, String documentId) {
        for (JsonNode item : items) {
            if (documentId.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("document not found in list: " + documentId);
    }
}
