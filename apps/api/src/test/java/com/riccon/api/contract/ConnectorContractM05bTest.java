package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M05b 知识库连接器契约（{@code 1.5.0-m05b}）。
 *
 * <p>读取 {@code contracts/openapi.yaml} 断言：版本为 {@code 1.5.0-m05b}；8 个连接器 operationId 全量存在，
 * 权限（`knowledge.connector.read`/`knowledge.connector.manage`）、CSRF、幂等键与错误码正确；
 * {@code Connector}/{@code ConnectorCreate}/{@code ConnectorUpdate}/{@code ConnectorCredential}/{@code ConnectorItemPage}
 * 等 schema 的必填字段、{@code additionalProperties} 与 deferred 标记齐全；M05 与既有模块端点保持回归面。
 * 测试只读契约，不调用真实端点（实现由 M05b-B6 交付）。
 */
class ConnectorContractM05bTest {

    /** 契约根。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** 版本、8 个连接器 operationId、非 deferred 与既有模块回归面。 */
    @Test
    @DisplayName("#contractVersionAndScope")
    void contractVersionAndScope() {
        assertThat(OpenApiContract.map(document.get("info")).get("version")).isEqualTo("1.5.0-m05b");

        for (String[] expected : List.of(
                new String[] {"get", "/api/v1/knowledge/connectors"},
                new String[] {"post", "/api/v1/knowledge/connectors"},
                new String[] {"patch", "/api/v1/knowledge/connectors/{connectorId}"},
                new String[] {"delete", "/api/v1/knowledge/connectors/{connectorId}"},
                new String[] {"put", "/api/v1/knowledge/connectors/{connectorId}/credential"},
                new String[] {"post", "/api/v1/knowledge/connectors/{connectorId}/test"},
                new String[] {"post", "/api/v1/knowledge/connectors/{connectorId}/sync"},
                new String[] {"get", "/api/v1/knowledge/connectors/{connectorId}/items"})) {
            Map<String, Object> operation = OpenApiContract.operation(document, expected[1], expected[0]);
            assertThat(operation)
                    .as("M05b operation must exist: " + expected[0] + " " + expected[1])
                    .isNotEmpty();
            assertThat(OpenApiContract.deferred(operation))
                    .as("M05b operation must not be deferred: " + expected[0] + " " + expected[1])
                    .isNull();
        }

        for (String path : List.of(
                "/api/v1/knowledge/documents",
                "/api/v1/knowledge/documents/{documentId}/acl",
                "/api/v1/knowledge/documents/{documentId}/download",
                "/api/v1/knowledge/documents/{documentId}/reindex",
                "/api/v1/auth/csrf",
                "/api/v1/departments",
                "/api/v1/audit-events",
                "/api/v1/admin/model-routing",
                "/api/v1/quotas")) {
            assertThat(OpenApiContract.pathItem(document, path))
                    .as("regression surface: " + path)
                    .isNotEmpty();
        }

        String deferred = String.valueOf(document.get("x-riccon-deferred"));
        assertThat(deferred).doesNotContain("knowledge-connectors");
    }

    /** {@code listConnectors}：读权限、分页/过滤参数与 ConnectorPage 结构。 */
    @Test
    @DisplayName("#listConnectors")
    void listConnectors() {
        Map<String, Object> operation = OpenApiContract.operation(document, "/api/v1/knowledge/connectors", "get");
        assertThat(operation.get("operationId")).isEqualTo("listConnectors");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.read");
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("ConnectorPage");
        assertThat(OpenApiContract.referencesParameter(operation, "Page")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "PageSize")).isTrue();

        Map<String, Object> status = OpenApiContract.parameter(operation, "status");
        assertThat(status.get("in")).isEqualTo("query");
        assertThat(status.get("required")).isEqualTo(Boolean.FALSE);
        assertThat(OpenApiContract.map(status.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorStatus");
        Map<String, Object> type = OpenApiContract.parameter(operation, "type");
        assertThat(type.get("in")).isEqualTo("query");
        assertThat(OpenApiContract.map(type.get("schema")).get("$ref")).isEqualTo("#/components/schemas/ConnectorType");

        assertThat(OpenApiContract.response(operation, "401")).isNotEmpty();
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertThat(String.valueOf(operation.get("description"))).contains("不含凭据", "TENANT_ADMIN", "RICCON-AUTH-403");

        Map<String, Object> page = OpenApiContract.schema(document, "ConnectorPage");
        assertThat(OpenApiContract.referencesSchema(page, "PageMeta")).isTrue();
        Map<String, Object> merged = OpenApiContract.mergedSchema(document, page);
        assertThat(OpenApiContract.required(merged)).contains("items");
        Map<String, Object> items = OpenApiContract.map(
                OpenApiContract.map(merged.get("properties")).get("items"));
        assertThat(OpenApiContract.map(items.get("items")).get("$ref")).isEqualTo("#/components/schemas/Connector");
    }

    /** {@code createConnector}：manage 权限、CSRF/幂等、必填可见范围、无 cron 与 201 响应。 */
    @Test
    @DisplayName("#createConnector")
    void createConnector() {
        Map<String, Object> operation = OpenApiContract.operation(document, "/api/v1/knowledge/connectors", "post");
        assertThat(operation.get("operationId")).isEqualTo("createConnector");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "IdempotencyKey"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation, "201")).isEqualTo("Connector");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "409", "RICCON-KB-409");

        Map<String, Object> requestBody = OpenApiContract.map(operation.get("requestBody"));
        assertThat(requestBody.get("required")).isEqualTo(Boolean.TRUE);
        Map<String, Object> json = OpenApiContract.map(
                OpenApiContract.map(requestBody.get("content")).get("application/json"));
        assertThat(OpenApiContract.map(json.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorCreate");

        Map<String, Object> create = OpenApiContract.schema(document, "ConnectorCreate");
        assertThat(OpenApiContract.additionalPropertiesFalse(create)).isTrue();
        assertThat(OpenApiContract.required(create))
                .containsExactlyInAnyOrder("name", "type", "endpointId", "config", "defaultVisibility");
        assertThat(OpenApiContract.propertyNames(create))
                .contains("defaultVisibility", "scheduleEnabled", "defaultDepartmentId", "credential")
                .doesNotContain("cron", "scheduleCron", "syncCron");

        Map<String, Object> properties = OpenApiContract.map(create.get("properties"));
        Map<String, Object> visibility = OpenApiContract.map(properties.get("defaultVisibility"));
        assertThat(visibility.get("$ref")).isEqualTo("#/components/schemas/ConnectorVisibility");
        assertThat(visibility.get("default")).isNull();
        assertThat(String.valueOf(visibility.get("description"))).contains("必须显式");
        assertThat(OpenApiContract.map(properties.get("scheduleEnabled")).get("type"))
                .isEqualTo("boolean");
        assertThat(OpenApiContract.oneOfReferences(
                        OpenApiContract.map(properties.get("credential")), "ConnectorCredential"))
                .isTrue();
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "ConnectorVisibility").get("enum")))
                .containsExactlyInAnyOrder("TENANT", "DEPT");
        assertThat(String.valueOf(operation.get("description")))
                .contains("defaultVisibility", "PAUSED", "RICCON-KB-400", "RICCON-KB-409", "cron");
    }

    /** {@code updateConnector}：PATCH 部分更新、状态可写集合与不可变字段。 */
    @Test
    @DisplayName("#updateConnector")
    void updateConnector() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}", "patch");
        assertThat(operation.get("operationId")).isEqualTo("updateConnector");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("Connector");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertErrorCode(operation, "409", "RICCON-KB-409");

        Map<String, Object> requestBody = OpenApiContract.map(operation.get("requestBody"));
        assertThat(requestBody.get("required")).isEqualTo(Boolean.TRUE);
        Map<String, Object> json = OpenApiContract.map(
                OpenApiContract.map(requestBody.get("content")).get("application/json"));
        assertThat(OpenApiContract.map(json.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorUpdate");

        Map<String, Object> update = OpenApiContract.schema(document, "ConnectorUpdate");
        assertThat(OpenApiContract.additionalPropertiesFalse(update)).isTrue();
        assertThat(update.get("minProperties")).isEqualTo(1);
        assertThat(OpenApiContract.propertyNames(update))
                .contains(
                        "name",
                        "endpointId",
                        "config",
                        "defaultVisibility",
                        "defaultDepartmentId",
                        "scheduleEnabled",
                        "status")
                .doesNotContain("type", "cron", "scheduleCron", "syncCron");
        Map<String, Object> status = OpenApiContract.map(
                OpenApiContract.map(update.get("properties")).get("status"));
        assertThat(status.get("$ref")).isEqualTo("#/components/schemas/ConnectorTargetStatus");
        assertThat(OpenApiContract.stringList(OpenApiContract.schema(document, "ConnectorTargetStatus")
                        .get("enum")))
                .containsExactlyInAnyOrder("ACTIVE", "PAUSED");
        assertThat(String.valueOf(operation.get("description")))
                .contains("部分更新", "type", "cron", "ERROR", "RICCON-KB-400", "RICCON-KB-409");
    }

    /** {@code deleteConnector}：软删除、文档处置参数与凭据保留口径。 */
    @Test
    @DisplayName("#deleteConnector")
    void deleteConnector() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}", "delete");
        assertThat(operation.get("operationId")).isEqualTo("deleteConnector");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(OpenApiContract.response(operation, "204")).isNotEmpty();
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> documents = OpenApiContract.parameter(operation, "documents");
        assertThat(documents.get("in")).isEqualTo("query");
        assertThat(documents.get("required")).isEqualTo(Boolean.FALSE);
        Map<String, Object> documentsSchema = OpenApiContract.map(documents.get("schema"));
        assertThat(OpenApiContract.stringList(documentsSchema.get("enum")))
                .containsExactlyInAnyOrder("KEEP", "SOFT_DELETE");
        assertThat(documentsSchema.get("default")).isEqualTo("KEEP");
        assertThat(String.valueOf(operation.get("description")))
                .contains("软删除", "KEEP", "SOFT_DELETE", "30 天", "M09", "凭据");
    }

    /** {@code putConnectorCredential}：只写不读、writeOnly、掩码响应与轮换审计。 */
    @Test
    @DisplayName("#putConnectorCredential")
    void putConnectorCredential() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}/credential", "put");
        assertThat(operation.get("operationId")).isEqualTo("putConnectorCredential");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("Connector");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> requestBody = OpenApiContract.map(operation.get("requestBody"));
        assertThat(requestBody.get("required")).isEqualTo(Boolean.TRUE);
        Map<String, Object> json = OpenApiContract.map(
                OpenApiContract.map(requestBody.get("content")).get("application/json"));
        assertThat(OpenApiContract.map(json.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorCredential");

        Map<String, Object> credential = OpenApiContract.schema(document, "ConnectorCredential");
        assertThat(OpenApiContract.additionalPropertiesFalse(credential)).isTrue();
        assertThat(OpenApiContract.required(credential)).containsExactlyInAnyOrder("accessKeyId", "secretAccessKey");
        Map<String, Object> properties = OpenApiContract.map(credential.get("properties"));
        assertThat(OpenApiContract.map(properties.get("accessKeyId")).get("writeOnly"))
                .isEqualTo(Boolean.TRUE);
        assertThat(OpenApiContract.map(properties.get("secretAccessKey")).get("writeOnly"))
                .isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(operation.get("description"))).contains("只写不读", "credentialMaskedHint", "轮换", "审计");
    }

    /** {@code testConnector}：只读连接测试、无请求体、中文分类与 503 语义。 */
    @Test
    @DisplayName("#testConnector")
    void testConnector() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}/test", "post");
        assertThat(operation.get("operationId")).isEqualTo("testConnector");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(operation.get("requestBody")).isNull();
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("ConnectorTestResult");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertErrorCode(operation, "503", "RICCON-KB-503");

        Map<String, Object> result = OpenApiContract.schema(document, "ConnectorTestResult");
        assertThat(OpenApiContract.additionalPropertiesFalse(result)).isTrue();
        assertThat(OpenApiContract.required(result)).containsExactlyInAnyOrder("success", "message", "testedAt");
        assertThat(OpenApiContract.map(
                                OpenApiContract.map(result.get("properties")).get("category"))
                        .get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorTestCategory");
        assertThat(OpenApiContract.stringList(OpenApiContract.schema(document, "ConnectorTestCategory")
                        .get("enum")))
                .contains("AUTH", "NETWORK", "ENDPOINT", "CONFIG");
        assertThat(OpenApiContract.required(result)).doesNotContain("category");

        Map<String, Object> condition = OpenApiContract.map(result.get("if"));
        assertThat(OpenApiContract.required(condition)).contains("success");
        assertThat(OpenApiContract.map(
                                OpenApiContract.map(condition.get("properties")).get("success"))
                        .get("const"))
                .isEqualTo(Boolean.FALSE);
        Map<String, Object> thenBranch = OpenApiContract.map(result.get("then"));
        assertThat(OpenApiContract.required(thenBranch)).contains("category");
        assertThat(OpenApiContract.map(OpenApiContract.map(thenBranch.get("properties"))
                                .get("category"))
                        .get("type"))
                .isEqualTo("string");
        Map<String, Object> elseBranch = OpenApiContract.map(result.get("else"));
        assertThat(OpenApiContract.map(OpenApiContract.map(elseBranch.get("properties"))
                                .get("category"))
                        .get("type"))
                .isEqualTo("null");
        assertThat(String.valueOf(result.get("description"))).contains("成功时", "失败时", "必填");
        assertThat(String.valueOf(operation.get("description")))
                .contains("ListObjectsV2", "max-keys=1", "空 bucket", "不返回凭据", "200");
    }

    /** {@code syncConnector}：202 受理、并发 409、幂等键与 heavy 通道语义。 */
    @Test
    @DisplayName("#syncConnector")
    void syncConnector() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}/sync", "post");
        assertThat(operation.get("operationId")).isEqualTo("syncConnector");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "IdempotencyKey"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation, "202")).isEqualTo("ConnectorSyncAccepted");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertErrorCode(operation, "409", "RICCON-KB-409");
        assertErrorCode(operation, "503", "RICCON-KB-503");

        Map<String, Object> accepted = OpenApiContract.schema(document, "ConnectorSyncAccepted");
        assertThat(OpenApiContract.additionalPropertiesFalse(accepted)).isTrue();
        assertThat(OpenApiContract.required(accepted))
                .containsExactlyInAnyOrder("syncId", "connectorId", "status", "acceptedAt");
        assertThat(OpenApiContract.stringList(OpenApiContract.map(
                                OpenApiContract.map(accepted.get("properties")).get("status"))
                        .get("enum")))
                .containsExactlyInAnyOrder("PENDING", "RUNNING");
        assertThat(String.valueOf(operation.get("description"))).contains("202", "409", "并发", "heavy", "固定周期");
    }

    /** {@code listConnectorItems}：读权限、状态过滤、分页条目与 externalId 截断。 */
    @Test
    @DisplayName("#listConnectorItems")
    void listConnectorItems() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/connectors/{connectorId}/items", "get");
        assertThat(operation.get("operationId")).isEqualTo("listConnectorItems");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.connector.read");
        assertThat(OpenApiContract.referencesParameter(operation, "ConnectorId"))
                .isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "Page")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "PageSize")).isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("ConnectorItemPage");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> status = OpenApiContract.parameter(operation, "status");
        assertThat(OpenApiContract.map(status.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorItemStatus");

        Map<String, Object> page = OpenApiContract.schema(document, "ConnectorItemPage");
        assertThat(OpenApiContract.referencesSchema(page, "PageMeta")).isTrue();
        Map<String, Object> merged = OpenApiContract.mergedSchema(document, page);
        assertThat(OpenApiContract.required(merged)).contains("items");
        Map<String, Object> items = OpenApiContract.map(
                OpenApiContract.map(merged.get("properties")).get("items"));
        assertThat(OpenApiContract.map(items.get("items")).get("$ref")).isEqualTo("#/components/schemas/ConnectorItem");

        Map<String, Object> item = OpenApiContract.schema(document, "ConnectorItem");
        assertThat(OpenApiContract.additionalPropertiesFalse(item)).isTrue();
        assertThat(OpenApiContract.required(item))
                .containsExactlyInAnyOrder("id", "externalId", "status", "generation", "createdAt", "updatedAt");
        Map<String, Object> externalId =
                OpenApiContract.map(OpenApiContract.map(item.get("properties")).get("externalId"));
        assertThat(String.valueOf(externalId.get("description"))).contains("末段", "32", "完整值");
        assertThat(OpenApiContract.map(
                                OpenApiContract.map(item.get("properties")).get("generation"))
                        .get("minimum"))
                .isEqualTo(1);
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "ConnectorItemStatus").get("enum")))
                .containsExactlyInAnyOrder("PENDING", "SYNCED", "SKIPPED", "FAILED", "DELETED");
        assertThat(String.valueOf(operation.get("description"))).contains("分页", "对象原文", "externalId", "末段");
    }

    /** 连接器核心 schema：必填、附加属性、枚举与无明文凭据字段。 */
    @Test
    @DisplayName("#connectorSchemas")
    void connectorSchemas() {
        Map<String, Object> connector = OpenApiContract.schema(document, "Connector");
        assertThat(OpenApiContract.additionalPropertiesFalse(connector)).isTrue();
        assertThat(OpenApiContract.required(connector))
                .containsExactlyInAnyOrder(
                        "id",
                        "name",
                        "type",
                        "status",
                        "endpointId",
                        "config",
                        "defaultVisibility",
                        "scheduleEnabled",
                        "lastSyncComplete",
                        "createdAt",
                        "updatedAt");
        assertThat(OpenApiContract.propertyNames(connector))
                .contains(
                        "credentialMaskedHint",
                        "nextSyncAt",
                        "lastSyncStartedAt",
                        "lastSyncFinishedAt",
                        "lastSyncStatus",
                        "lastSuccessAt",
                        "lastErrorCode",
                        "lastSyncSummary")
                .doesNotContain(
                        "credential",
                        "accessKeyId",
                        "secretAccessKey",
                        "credentialKeyId",
                        "credentialCiphertext",
                        "cron");

        Map<String, Object> properties = OpenApiContract.map(connector.get("properties"));
        assertThat(OpenApiContract.map(properties.get("type")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorType");
        assertThat(OpenApiContract.map(properties.get("status")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorStatus");
        assertThat(OpenApiContract.map(properties.get("config")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorConfig");
        assertThat(OpenApiContract.map(properties.get("defaultVisibility")).get("$ref"))
                .isEqualTo("#/components/schemas/ConnectorVisibility");
        assertThat(String.valueOf(OpenApiContract.map(properties.get("credentialMaskedHint"))
                        .get("type")))
                .contains("null", "string");
        assertThat(OpenApiContract.oneOfReferences(
                        OpenApiContract.map(properties.get("lastSyncSummary")), "ConnectorSyncSummary"))
                .isTrue();

        Map<String, Object> config = OpenApiContract.schema(document, "ConnectorConfig");
        assertThat(OpenApiContract.additionalPropertiesFalse(config)).isTrue();
        assertThat(OpenApiContract.required(config)).containsExactly("bucket");
        Map<String, Object> configProperties = OpenApiContract.map(config.get("properties"));
        assertThat(configProperties.keySet())
                .contains("bucket", "prefix", "region", "includeExtensions", "excludeGlobs")
                .doesNotContain("endpoint", "url", "cron");
        Map<String, Object> includeExtensions = OpenApiContract.map(configProperties.get("includeExtensions"));
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.map(includeExtensions.get("items")).get("enum")))
                .containsExactlyInAnyOrder("pdf", "docx", "xlsx", "png", "jpg", "jpeg", "txt", "md");

        Map<String, Object> summary = OpenApiContract.schema(document, "ConnectorSyncSummary");
        assertThat(OpenApiContract.additionalPropertiesFalse(summary)).isTrue();
        assertThat(OpenApiContract.required(summary))
                .containsExactlyInAnyOrder("scanned", "created", "updated", "skipped", "failed", "deleted", "complete");
    }

    /** {@code KnowledgeDocument} 的 M05b additive 字段与 sourceType 枚举。 */
    @Test
    @DisplayName("#knowledgeDocumentConnectorFields")
    void knowledgeDocumentConnectorFields() {
        Map<String, Object> documentSchema = OpenApiContract.schema(document, "KnowledgeDocument");
        assertThat(OpenApiContract.propertyNames(documentSchema)).contains("connectorId", "externalId");
        assertThat(OpenApiContract.required(documentSchema)).doesNotContain("connectorId", "externalId");
        Map<String, Object> properties = OpenApiContract.map(documentSchema.get("properties"));
        assertThat(OpenApiContract.map(properties.get("connectorId")).get("format"))
                .isEqualTo("uuid");
        assertThat(String.valueOf(
                        OpenApiContract.map(properties.get("connectorId")).get("type")))
                .contains("null");
        assertThat(String.valueOf(
                        OpenApiContract.map(properties.get("externalId")).get("description")))
                .contains("末段", "完整值");
        Map<String, Object> sourceType = OpenApiContract.map(properties.get("sourceType"));
        assertThat(OpenApiContract.stringList(sourceType.get("enum")))
                .containsExactlyInAnyOrder("UPLOAD", "SEED", "CONNECTOR");
    }

    /** 断言指定状态码响应描述包含错误码。 */
    private void assertErrorCode(Map<String, Object> operation, String status, String code) {
        Object description =
                OpenApiContract.resolvedResponse(document, operation, status).get("description");
        assertThat(String.valueOf(description))
                .as("status " + status + " must document " + code)
                .contains(code);
    }
}
