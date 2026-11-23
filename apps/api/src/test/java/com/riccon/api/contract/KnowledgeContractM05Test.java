package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M05 知识库深度契约（{@code 1.4.0-m05}）。
 *
 * <p>读取 {@code contracts/openapi.yaml} 断言：版本为 {@code 1.4.0-m05}；ACL 读取/整体替换、软删除、服务端流式下载与重索引端点
 * 存在且权限/CSRF/错误码正确；{@code KnowledgeDocument} 与创建请求的 M05 字段、ACL schema、解析摘要、
 * {@code Citation}/{@code ArtifactCitation} 的 {@code bbox}/{@code ocrConfidence}/{@code sourceStatus} 齐全；
 * 不引入连接器端点或字段。测试只读契约，不调用真实端点（实现由 M05-B3 交付）。
 */
class KnowledgeContractM05Test {

    /** 契约根。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** 版本、M05 端点集合、非 M05b 范围与既有模块回归面。 */
    @Test
    @DisplayName("#contract 1.4.0-m05 版本、M05 端点与无连接器")
    void contractVersionAndScope() {
        assertThat(OpenApiContract.map(document.get("info")).get("version")).isEqualTo("1.4.0-m05");

        assertThat(OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/acl", "get"))
                .isNotEmpty();
        assertThat(OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/acl", "put"))
                .isNotEmpty();
        assertThat(OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}", "delete"))
                .isNotEmpty();
        assertThat(OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/download", "get"))
                .isNotEmpty();
        assertThat(OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/reindex", "post"))
                .isNotEmpty();

        for (String path : List.of(
                "/api/v1/knowledge/documents/{documentId}/acl",
                "/api/v1/knowledge/documents/{documentId}/download",
                "/api/v1/knowledge/documents/{documentId}/reindex")) {
            Map<String, Object> item = OpenApiContract.pathItem(document, path);
            for (String method : List.of("get", "post", "put", "delete")) {
                Object operation = item.get(method);
                if (operation != null) {
                    assertThat(OpenApiContract.deferred(OpenApiContract.map(operation)))
                            .as("M05 operation must not be deferred: " + method + " " + path)
                            .isNull();
                }
            }
        }

        for (String path : List.of(
                "/api/v1/auth/csrf",
                "/api/v1/conversations/{conversationId}/messages",
                "/api/v1/departments",
                "/api/v1/audit-events",
                "/api/v1/admin/model-routing",
                "/api/v1/quotas")) {
            assertThat(OpenApiContract.pathItem(document, path))
                    .as("existing module regression surface: " + path)
                    .isNotEmpty();
        }

        Map<String, Object> paths = OpenApiContract.map(document.get("paths"));
        assertThat(paths.keySet()).noneMatch(path -> path.contains("/knowledge/connectors"));
        Map<String, Object> knowledgeDocument = OpenApiContract.schema(document, "KnowledgeDocument");
        assertThat(OpenApiContract.propertyNames(knowledgeDocument)).doesNotContain("connectorId", "externalId");
        Map<String, Object> sourceType = OpenApiContract.map(
                OpenApiContract.map(knowledgeDocument.get("properties")).get("sourceType"));
        assertThat(OpenApiContract.stringList(sourceType.get("enum"))).containsExactlyInAnyOrder("UPLOAD", "SEED");

        String deferred = String.valueOf(document.get("x-riccon-deferred"));
        assertThat(deferred).doesNotContain("knowledge-acl-ocr").doesNotContain("knowledge-connectors");
    }

    /** {@code listKnowledgeDocuments}：ACL 语义文案替换 M03 过期声明。 */
    @Test
    @DisplayName("#listKnowledgeDocuments")
    void listKnowledgeDocuments() {
        Map<String, Object> operation = OpenApiContract.operation(document, "/api/v1/knowledge/documents", "get");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.read");
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("KnowledgeDocumentPage");

        String description = String.valueOf(operation.get("description"));
        assertThat(description)
                .contains("ACL", "TENANT", "DEPT", "USERS", "创建人")
                .doesNotContain("租户级共享", "文档级权限在后续版本", "不按部门过滤");
    }

    /** {@code createKnowledgeDocument}：可见范围请求字段与默认值。 */
    @Test
    @DisplayName("#createKnowledgeDocument")
    void createKnowledgeDocument() {
        Map<String, Object> operation = OpenApiContract.operation(document, "/api/v1/knowledge/documents", "post");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "IdempotencyKey"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation, "202")).isEqualTo("KnowledgeDocument");

        Map<String, Object> requestBody = OpenApiContract.map(operation.get("requestBody"));
        Map<String, Object> json = OpenApiContract.map(
                OpenApiContract.map(requestBody.get("content")).get("application/json"));
        assertThat(OpenApiContract.map(json.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/KnowledgeDocumentCreateRequest");

        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertErrorCode(operation, "409", "RICCON-KB-409");

        Map<String, Object> create = OpenApiContract.schema(document, "KnowledgeDocumentCreateRequest");
        assertThat(OpenApiContract.additionalPropertiesFalse(create)).isTrue();
        assertThat(OpenApiContract.required(create)).containsExactlyInAnyOrder("title", "fileId");
        assertThat(OpenApiContract.propertyNames(create)).contains("visibility", "departmentId", "userIds");

        Map<String, Object> properties = OpenApiContract.map(create.get("properties"));
        Map<String, Object> visibility = OpenApiContract.map(properties.get("visibility"));
        assertThat(visibility.get("$ref")).isEqualTo("#/components/schemas/KnowledgeVisibility");
        assertThat(visibility.get("default")).isEqualTo("TENANT");
        Map<String, Object> userIds = OpenApiContract.map(properties.get("userIds"));
        assertThat(userIds.get("maxItems")).isEqualTo(200);
        assertThat(userIds.get("uniqueItems")).isEqualTo(Boolean.TRUE);

        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "KnowledgeVisibility").get("enum")))
                .containsExactlyInAnyOrder("TENANT", "DEPT", "USERS");
    }

    /** {@code getKnowledgeDocument}：响应 schema 的 M05 增量字段与保留策略。 */
    @Test
    @DisplayName("#getKnowledgeDocument")
    void getKnowledgeDocument() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}", "get");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.read");
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("KnowledgeDocument");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> schema = OpenApiContract.schema(document, "KnowledgeDocument");
        assertThat(OpenApiContract.additionalPropertiesFalse(schema)).isTrue();
        assertThat(OpenApiContract.required(schema))
                .containsExactlyInAnyOrder(
                        "id", "title", "sourceType", "status", "checksum", "mimeType", "sizeBytes", "createdAt");
        assertThat(OpenApiContract.propertyNames(schema))
                .contains(
                        "visibility",
                        "departmentId",
                        "aclUserCount",
                        "parseEngine",
                        "warnings",
                        "parseSummary",
                        "sourceFileAccessLimited",
                        "retentionExpiresAt",
                        "retentionDays",
                        "retentionPolicy");

        Map<String, Object> properties = OpenApiContract.map(schema.get("properties"));
        assertThat(OpenApiContract.map(properties.get("visibility")).get("$ref"))
                .isEqualTo("#/components/schemas/KnowledgeVisibility");
        Map<String, Object> departmentId = OpenApiContract.map(properties.get("departmentId"));
        assertThat(String.valueOf(departmentId.get("type"))).contains("null");
        assertThat(OpenApiContract.map(properties.get("aclUserCount")).get("type"))
                .isEqualTo("integer");
        assertThat(OpenApiContract.map(properties.get("parseEngine")).get("$ref"))
                .isEqualTo("#/components/schemas/ParseEngine");
        assertThat(OpenApiContract.map(
                                OpenApiContract.map(properties.get("warnings")).get("items"))
                        .get("type"))
                .isEqualTo("string");
        assertThat(OpenApiContract.oneOfReferences(
                        OpenApiContract.map(properties.get("parseSummary")), "KnowledgeParseSummary"))
                .isTrue();
        assertThat(OpenApiContract.map(properties.get("sourceFileAccessLimited"))
                        .get("type"))
                .isEqualTo("boolean");
        Map<String, Object> retentionExpiresAt = OpenApiContract.map(properties.get("retentionExpiresAt"));
        assertThat(String.valueOf(retentionExpiresAt.get("type"))).contains("null");
        assertThat(retentionExpiresAt.get("format")).isEqualTo("date-time");
        assertThat(OpenApiContract.map(properties.get("retentionDays"))
                        .get("type")
                        .toString())
                .contains("integer");
        assertThat(OpenApiContract.map(properties.get("retentionPolicy")).get("$ref"))
                .isEqualTo("#/components/schemas/KnowledgeRetentionPolicy");

        assertThat(OpenApiContract.stringList(OpenApiContract.schema(document, "KnowledgeRetentionPolicy")
                        .get("enum")))
                .containsExactlyInAnyOrder("DEPLOYMENT_DEFAULT", "TENANT_POLICY", "EXPLICIT");
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "ParseEngine").get("enum")))
                .contains("PDFBOX", "POI", "TESSERACT", "RAPIDOCR", "NONE");
        assertThat(OpenApiContract.schema(document, "KnowledgeParseSummary")).isNotEmpty();
        assertThat(OpenApiContract.schema(document, "KnowledgeParseSheetSummary"))
                .isNotEmpty();
        assertThat(OpenApiContract.schema(document, "KnowledgeParseOcrSummary")).isNotEmpty();
    }

    /** {@code putKnowledgeDocumentAcl}：整体替换、权限、400/403/404 与上限。 */
    @Test
    @DisplayName("#putKnowledgeDocumentAcl")
    void putKnowledgeDocumentAcl() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/acl", "put");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.acl.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("KnowledgeDocument");
        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> requestBody = OpenApiContract.map(operation.get("requestBody"));
        assertThat(requestBody.get("required")).isEqualTo(Boolean.TRUE);
        Map<String, Object> json = OpenApiContract.map(
                OpenApiContract.map(requestBody.get("content")).get("application/json"));
        assertThat(OpenApiContract.map(json.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/KnowledgeDocumentAclUpdate");

        Map<String, Object> update = OpenApiContract.schema(document, "KnowledgeDocumentAclUpdate");
        assertThat(OpenApiContract.additionalPropertiesFalse(update)).isTrue();
        assertThat(OpenApiContract.required(update)).containsExactly("visibility");
        assertThat(OpenApiContract.propertyNames(update))
                .containsExactlyInAnyOrder("visibility", "departmentId", "userIds");
        Map<String, Object> userIds = OpenApiContract.map(
                OpenApiContract.map(update.get("properties")).get("userIds"));
        assertThat(userIds.get("maxItems")).isEqualTo(200);
        assertThat(userIds.get("uniqueItems")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(operation.get("description"))).contains("整体替换", "DEPT", "USERS", "200", "创建人");
    }

    /** {@code getKnowledgeDocumentAcl}：名单结构、创建人标记与权限。 */
    @Test
    @DisplayName("#getKnowledgeDocumentAcl")
    void getKnowledgeDocumentAcl() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/acl", "get");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.acl.manage");
        assertThat(OpenApiContract.responseSchemaName(operation)).isEqualTo("KnowledgeDocumentAcl");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");

        Map<String, Object> acl = OpenApiContract.schema(document, "KnowledgeDocumentAcl");
        assertThat(OpenApiContract.additionalPropertiesFalse(acl)).isTrue();
        assertThat(OpenApiContract.required(acl)).containsExactlyInAnyOrder("visibility", "users");
        assertThat(OpenApiContract.propertyNames(acl)).contains("visibility", "departmentId", "users", "creatorUserId");
        Map<String, Object> usersItems = OpenApiContract.map(
                OpenApiContract.map(OpenApiContract.map(acl.get("properties")).get("users"))
                        .get("items"));
        assertThat(usersItems.get("$ref")).isEqualTo("#/components/schemas/KnowledgeAclUser");

        Map<String, Object> aclUser = OpenApiContract.schema(document, "KnowledgeAclUser");
        assertThat(OpenApiContract.required(aclUser)).containsExactlyInAnyOrder("userId", "displayName");
        assertThat(OpenApiContract.propertyNames(aclUser)).contains("creator");
    }

    /** {@code deleteKnowledgeDocument}：软删除、创建人/管理员、幂等与 reason=USER。 */
    @Test
    @DisplayName("#deleteKnowledgeDocument")
    void deleteKnowledgeDocument() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}", "delete");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.acl.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.response(operation, "204")).isNotEmpty();
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertThat(String.valueOf(operation.get("description"))).contains("软删除", "reason=USER", "幂等", "创建人", "M09");
    }

    /** {@code downloadKnowledgeDocument}：流式代理、动态 Content-Type、RFC 5987 与 404/502 语义。 */
    @Test
    @DisplayName("#downloadKnowledgeDocument")
    void downloadKnowledgeDocument() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/download", "get");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.read");
        assertThat(OpenApiContract.referencesParameter(operation, "KnowledgeDocumentId"))
                .isTrue();

        Map<String, Object> ok = OpenApiContract.response(operation, "200");
        Map<String, Object> headers = OpenApiContract.map(ok.get("headers"));
        assertThat(headers.keySet())
                .contains("Content-Type", "Content-Disposition", "Cache-Control", "X-Content-Type-Options");

        Map<String, Object> contentType = OpenApiContract.map(headers.get("Content-Type"));
        assertThat(String.valueOf(contentType.get("description")))
                .contains("mimeType", "application/pdf", "application/octet-stream");
        assertThat(contentType.get("example")).isEqualTo("application/pdf");

        Map<String, Object> contentDisposition = OpenApiContract.map(headers.get("Content-Disposition"));
        assertThat(String.valueOf(contentDisposition.get("description")))
                .contains("attachment", "filename=", "filename*=", "UTF-8''");

        assertThat(OpenApiContract.map(headers.get("X-Content-Type-Options"))
                        .get("schema")
                        .toString())
                .contains("nosniff");
        Map<String, Object> content = OpenApiContract.map(ok.get("content"));
        assertThat(content.keySet()).contains("application/octet-stream").doesNotContain("application/json");
        Map<String, Object> binary = OpenApiContract.map(content.get("application/octet-stream"));
        assertThat(OpenApiContract.map(binary.get("schema")).get("format")).isEqualTo("binary");
        assertThat(String.valueOf(ok.get("description"))).contains("application/octet-stream", "mimeType", "二进制");

        assertThat(String.valueOf(OpenApiContract.resolvedResponse(document, operation, "404")
                        .get("description")))
                .contains("RICCON-KB-404")
                .contains("RICCON-TENANT-404");

        assertThat(OpenApiContract.response(operation, "502").get("$ref"))
                .isEqualTo("#/components/responses/KnowledgeDownloadFailed");
        Map<String, Object> downloadFailed = OpenApiContract.resolvedResponse(document, operation, "502");
        assertThat(String.valueOf(downloadFailed.get("description")))
                .contains("RICCON-KB-502", "ProblemDetail", "对象 key", "存储端点");
        Map<String, Object> failedJson = OpenApiContract.map(
                OpenApiContract.map(downloadFailed.get("content")).get("application/problem+json"));
        assertThat(OpenApiContract.map(failedJson.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ProblemDetail");

        assertThat(String.valueOf(operation.get("description")))
                .contains("流式", "不返回对象 key", "预签名", "每次请求重判", "RICCON-KB-502", "mimeType", "filename*=UTF-8''");
    }

    /** {@code reindexKnowledgeDocument}：force 参数、缺失页语义与 400/403/404/409/503。 */
    @Test
    @DisplayName("#reindexKnowledgeDocument")
    void reindexKnowledgeDocument() {
        Map<String, Object> operation =
                OpenApiContract.operation(document, "/api/v1/knowledge/documents/{documentId}/reindex", "post");
        assertThat(OpenApiContract.permission(operation)).isEqualTo("knowledge.manage");
        assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken")).isTrue();
        assertThat(OpenApiContract.referencesParameter(operation, "IdempotencyKey"))
                .isTrue();
        assertThat(OpenApiContract.responseSchemaName(operation, "202")).isEqualTo("KnowledgeDocument");

        Map<String, Object> force = OpenApiContract.parameter(operation, "force");
        assertThat(force.get("in")).isEqualTo("query");
        assertThat(force.get("required")).isEqualTo(Boolean.FALSE);
        Map<String, Object> forceSchema = OpenApiContract.map(force.get("schema"));
        assertThat(forceSchema.get("type")).isEqualTo("boolean");
        assertThat(forceSchema.get("default")).isEqualTo(Boolean.FALSE);

        assertErrorCode(operation, "400", "RICCON-KB-400");
        assertErrorCode(operation, "403", "RICCON-AUTH-403");
        assertErrorCode(operation, "404", "RICCON-TENANT-404");
        assertErrorCode(operation, "409", "RICCON-KB-409");
        assertErrorCode(operation, "503", "RICCON-KB-503");
        assertThat(String.valueOf(operation.get("description")))
                .contains("force=false", "force=true", "缺失页", "knowledge.manage");
    }

    /** Citation/ArtifactCitation 的 bbox/ocrConfidence/sourceStatus 与定位枚举冻结。 */
    @Test
    @DisplayName("#citationSchemas")
    void citationSchemas() {
        assertCitationSchema(OpenApiContract.schema(document, "Citation"), false);
        assertCitationSchema(OpenApiContract.schema(document, "ArtifactCitation"), true);

        Map<String, Object> bbox = OpenApiContract.schema(document, "BoundingBox");
        assertThat(OpenApiContract.additionalPropertiesFalse(bbox)).isTrue();
        assertThat(OpenApiContract.required(bbox)).containsExactlyInAnyOrder("x", "y", "w", "h");

        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "CitationSourceStatus").get("enum")))
                .containsExactlyInAnyOrder("AVAILABLE", "NO_ACCESS", "DELETED", "UPDATED");
        assertThat(OpenApiContract.stringList(
                        OpenApiContract.schema(document, "LocatorType").get("enum")))
                .containsExactlyInAnyOrder("PAGE", "SHEET", "PARAGRAPH");
    }

    /** 断言单个引用 schema 的 M05 字段（Artifact 额外要求 Excel 区域）。 */
    private void assertCitationSchema(Map<String, Object> schema, boolean artifact) {
        assertThat(OpenApiContract.additionalPropertiesFalse(schema)).isTrue();
        assertThat(OpenApiContract.propertyNames(schema)).contains("bbox", "ocrConfidence", "sourceStatus");
        if (artifact) {
            assertThat(OpenApiContract.propertyNames(schema)).contains("cellRange");
        }
        Map<String, Object> properties = OpenApiContract.map(schema.get("properties"));
        assertThat(OpenApiContract.oneOfReferences(OpenApiContract.map(properties.get("bbox")), "BoundingBox"))
                .isTrue();
        Map<String, Object> ocrConfidence = OpenApiContract.map(properties.get("ocrConfidence"));
        assertThat(String.valueOf(ocrConfidence.get("type"))).contains("null", "number");
        assertThat(OpenApiContract.map(properties.get("sourceStatus")).get("$ref"))
                .isEqualTo("#/components/schemas/CitationSourceStatus");
        assertThat(String.valueOf(schema.get("description"))).contains("NO_ACCESS", "DELETED", "空串", "null");
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
