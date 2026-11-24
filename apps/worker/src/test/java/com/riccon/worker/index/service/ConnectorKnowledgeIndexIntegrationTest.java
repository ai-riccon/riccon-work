package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestDocuments;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;

/**
 * 连接器文档索引分支（M05b-B7b，ADR-0023 §6.3/§7.1）。
 *
 * <p>连接器文档 {@code file_id} 为空、{@code connector_id} 非空，原文在平台桶
 * {@code tenants/{tenantId}/connector/{yyyy}/{documentId}}。本类覆盖：
 *
 * <ul>
 *   <li>FULL 从平台对象读取并解析，不因 fileId 为空跳过、不落进 SEED 的既有分块路径；
 *   <li>重灌幂等、ACL_ONLY 不改正文、DELETE 清理索引；
 *   <li>源端删除-重现后新文档 ID 可索引新内容，旧索引不残留；
 *   <li>对象缺失/损坏按失败语义标记 FAILED，不导致 poller 崩溃。
 * </ul>
 */
class ConnectorKnowledgeIndexIntegrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 索引客户端，用于派生别名与查询索引。 */
    @Autowired
    private OpenSearchIndexClient indexClient;

    /** OpenSearch HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** pollOnce 跨租户领取事件；每个测试先清空残留 outbox，保证精确计数。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** 连接器文档按平台 key 读取并索引，索引字段按 ADR §7.1 投影且不泄露 externalId。 */
    @Test
    void connectorDocument_indexesPlatformObjectWithConnectorFields() {
        String tenant = uniqueTenant("conn-index");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        Seed seed = seedConnectorDocument(tenant, userId, "pipeline.pdf", TestDocuments.pdf(2));

        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
        assertThat(outboxStatus(event.eventId())).isEqualTo("DONE");
        assertThat(runStatus(event.runId())).isEqualTo("SUCCEEDED");
        assertThat(chunks(tenant, seed.documentId())).hasSize(2);
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(2);

        JsonNode source = firstSource(tenant, seed.documentId());
        assertThat(source.path("source_type").asText()).isEqualTo("CONNECTOR");
        assertThat(source.path("connector_id").asText())
                .isEqualTo(seed.connectorId().toString());
        assertThat(source.path("visibility").asText()).isEqualTo("TENANT");
        assertThat(source.path("acl_version").asInt()).isEqualTo(1);
        assertThat(source.path("parse_engine").asText()).isEqualTo("PDFBOX");
        assertThat(source.path("title").asText()).isEqualTo("pipeline.pdf");
        // externalId 与对象 key 都不属于索引字段（mapping dynamic=strict）。
        assertThat(source.has("external_id")).isFalse();
        assertThat(source.toString()).doesNotContain(seed.externalId()).doesNotContain("connector/");
    }

    /** 连接器 txt 文档按 UTF-8 索引：BOM 去除、CRLF 归一、中文段落可分块。 */
    @Test
    void textConnectorDocument_indexesUtf8WithBomAndCrlf() {
        String tenant = uniqueTenant("conn-text");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        byte[] body = "\uFEFF第一段：市场调研数据\r\n\r\n第二段：中文内容与结论".getBytes(StandardCharsets.UTF_8);
        Seed seed = seedConnectorDocument(tenant, userId, "market.txt", body);

        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
        List<String> contents = chunks(tenant, seed.documentId());
        assertThat(contents).hasSize(2);
        assertThat(String.join("\n", contents)).contains("第一段：市场调研数据").contains("第二段：中文内容与结论");
        assertThat(contents)
                .allSatisfy(content -> assertThat(content).doesNotContain("\r").doesNotContain("\uFEFF"));
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(2);
        JsonNode source = firstSource(tenant, seed.documentId());
        assertThat(source.path("source_type").asText()).isEqualTo("CONNECTOR");
        assertThat(source.path("parse_engine").asText()).isEqualTo("NONE");
    }

    /** 连接器 md 文档长文本按既有上限切分；重复 FULL 重灌不改变分块内容。 */
    @Test
    void markdownConnectorDocument_indexesLongTextIdempotently() {
        String tenant = uniqueTenant("conn-markdown");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        String body = "# 调研指南\n\n" + "市场调研中文内容 ".repeat(160);
        Seed seed = seedConnectorDocument(tenant, userId, "guide.md", body.getBytes(StandardCharsets.UTF_8));

        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
        List<String> first = chunks(tenant, seed.documentId());
        assertThat(first).hasSizeGreaterThan(1);
        assertThat(first).allSatisfy(content -> assertThat(content.length()).isLessThanOrEqualTo(800));
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(first.size());

        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 2);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunks(tenant, seed.documentId())).containsExactlyElementsOf(first);
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(first.size());
        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
    }

    /** 空文件与仅空白文本按不可解析失败处理，不产生空索引或静默成功。 */
    @Test
    void emptyOrBlankTextConnectorDocument_failsWithoutEmptyIndex() {
        String tenant = uniqueTenant("conn-empty-text");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);

        Seed empty = seedConnectorDocument(tenant, userId, "empty.txt", new byte[0]);
        TestDataFactory.PublishedEvent emptyEvent =
                data.publishKnowledgeIndex(tenant, empty.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(documentStatus(tenant, empty.documentId())).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenant, empty.documentId())).isEqualTo("RICCON-FILE-422");
        assertThat(chunks(tenant, empty.documentId())).isEmpty();
        assertThat(countDocuments(tenant, empty.documentId())).isZero();
        assertThat(outboxStatus(emptyEvent.eventId())).isEqualTo("DEAD");

        Seed blank =
                seedConnectorDocument(tenant, userId, "blank.md", "\uFEFF \r\n\t\n".getBytes(StandardCharsets.UTF_8));
        TestDataFactory.PublishedEvent blankEvent =
                data.publishKnowledgeIndex(tenant, blank.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(documentStatus(tenant, blank.documentId())).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenant, blank.documentId())).isEqualTo("RICCON-FILE-422");
        assertThat(chunks(tenant, blank.documentId())).isEmpty();
        assertThat(countDocuments(tenant, blank.documentId())).isZero();
        assertThat(outboxStatus(blankEvent.eventId())).isEqualTo("DEAD");
    }

    /** 非法 UTF-8 字节用替换字符处理，不抛未捕获异常、不导致索引崩溃。 */
    @Test
    void invalidUtf8TextConnectorDocument_isReplacedNotFatal() {
        String tenant = uniqueTenant("conn-invalid-text");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        byte[] invalid = new byte[] {(byte) 0xC3, 0x28, 'a', 'b'};
        Seed seed = seedConnectorDocument(tenant, userId, "legacy.txt", invalid);

        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
        assertThat(String.join("", chunks(tenant, seed.documentId()))).contains("\uFFFD");
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(1);
    }

    /** 同一文档重复 FULL 事件（重灌）不产生重复分块或重复索引文档。 */
    @Test
    void fullEventReplay_doesNotDuplicateChunks() {
        String tenant = uniqueTenant("conn-reindex");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        Seed seed = seedConnectorDocument(tenant, userId, "repeat.pdf", TestDocuments.pdf(2));
        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        long chunkCount = chunks(tenant, seed.documentId()).size();
        long documentCount = countDocuments(tenant, seed.documentId());

        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 2);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunks(tenant, seed.documentId())).hasSize((int) chunkCount);
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(documentCount);
        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("INDEXED");
        assertThat(uniqueIndexIds(tenant, seed.documentId())).hasSize((int) documentCount);
    }

    /** ACL_ONLY 只刷新索引 ACL 字段，不改正文、不替换 MySQL 分块。 */
    @Test
    void aclOnly_refreshesConnectorAclWithoutTouchingContent() {
        String tenant = uniqueTenant("conn-acl");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        Seed seed = seedConnectorDocument(tenant, userId, "acl.pdf", TestDocuments.pdf(2));
        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        List<String> chunkIds = chunkIds(tenant, seed.documentId());
        List<String> contents = chunks(tenant, seed.documentId());
        assertThat(chunkIds).hasSize(2);

        setAcl(tenant, seed.documentId(), "USERS", 2);
        UUID aclEvent = publish(tenant, userId, seed.documentId(), 2, "ACL_ONLY", 2, "ACL_CHANGED");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunkIds(tenant, seed.documentId())).containsExactlyElementsOf(chunkIds);
        assertThat(chunks(tenant, seed.documentId())).containsExactlyElementsOf(contents);
        assertThat(countDocuments(tenant, seed.documentId())).isEqualTo(2);
        JsonNode source = firstSource(tenant, seed.documentId());
        assertThat(source.path("visibility").asText()).isEqualTo("USERS");
        assertThat(source.path("acl_version").asInt()).isEqualTo(2);
        assertThat(source.path("source_type").asText()).isEqualTo("CONNECTOR");
        assertThat(outboxStatus(aclEvent)).isEqualTo("DONE");
    }

    /** DELETE 清理连接器文档的分块与索引，重复投递幂等。 */
    @Test
    void delete_clearsConnectorChunksAndIndex() {
        String tenant = uniqueTenant("conn-delete");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        Seed seed = seedConnectorDocument(tenant, userId, "delete.pdf", TestDocuments.pdf(2));
        data.publishKnowledgeIndex(tenant, seed.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(chunkIds(tenant, seed.documentId())).hasSize(2);

        softDelete(tenant, seed.documentId());
        UUID firstDelete = publish(tenant, userId, seed.documentId(), 2, "DELETE", 1, "CONNECTOR");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, seed.documentId())).isEqualTo("DELETED");
        assertThat(chunkIds(tenant, seed.documentId())).isEmpty();
        assertThat(countDocuments(tenant, seed.documentId())).isZero();
        assertThat(outboxStatus(firstDelete)).isEqualTo("DONE");

        UUID secondDelete = publish(tenant, userId, seed.documentId(), 3, "DELETE", 2, "CONNECTOR");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunkIds(tenant, seed.documentId())).isEmpty();
        assertThat(countDocuments(tenant, seed.documentId())).isZero();
        assertThat(outboxStatus(secondDelete)).isEqualTo("DONE");
    }

    /** 源端删除-重现后新文档 ID 索引新内容，旧文档索引与分块不残留。 */
    @Test
    void sourceReappears_indexesNewDocumentAndRemovesOldIndex() {
        String tenant = uniqueTenant("conn-reappear");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID connectorId = UuidBinary.newV7();
        String externalId = "source/private/a.pdf";
        Seed first = seedConnectorDocument(
                tenant, userId, connectorId, externalId, "a.pdf", TestDocuments.pdf(1), Instant.now());
        data.publishKnowledgeIndex(tenant, first.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(chunks(tenant, first.documentId())).hasSize(1);
        assertThat(countDocuments(tenant, first.documentId())).isEqualTo(1);

        softDelete(tenant, first.documentId());
        publish(tenant, userId, first.documentId(), 2, "DELETE", 1, "CONNECTOR");
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(countDocuments(tenant, first.documentId())).isZero();

        // 模拟 B6 tombstone 复用：同一 connector/externalId 以新文档 ID 重现，旧文档保留软删除。
        Seed second = seedConnectorDocument(
                tenant,
                userId,
                connectorId,
                externalId,
                "a.pdf",
                TestDocuments.pdf(2),
                Instant.now().plusSeconds(5));
        data.publishKnowledgeIndex(tenant, second.documentId(), null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, second.documentId())).isEqualTo("INDEXED");
        assertThat(documentStatus(tenant, first.documentId())).isEqualTo("DELETED");
        assertThat(chunks(tenant, second.documentId())).hasSize(2);
        assertThat(chunks(tenant, first.documentId())).isEmpty();
        assertThat(countDocuments(tenant, second.documentId())).isEqualTo(2);
        assertThat(countDocuments(tenant, first.documentId())).isZero();
    }

    /** 平台对象缺失时标记 FAILED，不回退到 SEED 的既有分块，也不让 poller 崩溃。 */
    @Test
    void missingConnectorObject_failsWithoutSeedFallback() {
        String tenant = uniqueTenant("conn-missing");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        // 预先放一条分块，证明失败路径不会把 SEED 数据当作索引来源。
        UUID documentId = createConnectorDocument(
                tenant, userId, UuidBinary.newV7(), "missing.pdf", "source/private/missing.pdf", Instant.now());
        data.createKnowledgeChunk(tenant, documentId, 1, "stale seed chunk", 1);
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenant, documentId)).isEqualTo("RICCON-TENANT-404");
        assertThat(countDocuments(tenant, documentId)).isZero();
        assertThat(outboxStatus(event.eventId())).isEqualTo("DEAD");
        assertThat(runStatus(event.runId())).isEqualTo("FAILED");
        // 事件已 DEAD，不会再次领取导致 poller 异常。
        assertThat(poller.pollOnce()).isZero();
    }

    /** 对象存在但内容损坏时标记 FAILED 并保留解析错误码。 */
    @Test
    void corruptConnectorObject_marksFailedWithParseError() {
        String tenant = uniqueTenant("conn-corrupt");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID documentId = createConnectorDocument(
                tenant, userId, UuidBinary.newV7(), "broken.pdf", "source/private/broken.pdf", createdAt);
        putConnectorObject(tenant, documentId, createdAt, "not a pdf".getBytes(StandardCharsets.UTF_8), "bad.pdf");
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenant, documentId)).isEqualTo("RICCON-FILE-422");
        assertThat(chunks(tenant, documentId)).isEmpty();
        assertThat(countDocuments(tenant, documentId)).isZero();
        assertThat(outboxStatus(event.eventId())).isEqualTo("DEAD");
    }

    /** 其他租户平台对象不可被当前租户读取；连接器索引不跨租户。 */
    @Test
    void crossTenantConnectorObject_isNotReadable() {
        String tenantB = uniqueTenant("conn-tenant-b");
        data.createTenant(tenantB);
        UUID userB = data.createUser(tenantB);
        Seed other = seedConnectorDocument(tenantB, userB, "other.pdf", TestDocuments.pdf(1));
        data.publishKnowledgeIndex(tenantB, other.documentId(), null, userB, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(countDocuments(tenantB, other.documentId())).isEqualTo(1);

        String tenantA = uniqueTenant("conn-tenant-a");
        data.createTenant(tenantA);
        UUID userA = data.createUser(tenantA);
        // 复用同一 documentId：key 派生仍带 tenant 前缀，A 租户不得读到 B 的对象。
        UUID documentA = createConnectorDocumentWithId(
                tenantA,
                userA,
                UuidBinary.newV7(),
                other.documentId(),
                "leak.pdf",
                "source/private/leak.pdf",
                Instant.now());
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenantA, documentA, null, userA, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenantA, documentA)).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenantA, documentA)).isEqualTo("RICCON-TENANT-404");
        assertThat(countDocuments(tenantA, documentA)).isZero();
        assertThat(countDocuments(tenantB, other.documentId())).isEqualTo(1);
        assertThat(outboxStatus(event.eventId())).isEqualTo("DEAD");
    }

    /**
     * 创建连接器文档并把合法对象写入平台桶。
     *
     * @param tenant 租户
     * @param userId 创建人
     * @param title 标题（含扩展名）
     * @param bytes 对象内容
     * @return 测试种子
     */
    private Seed seedConnectorDocument(String tenant, UUID userId, String title, byte[] bytes) {
        UUID connectorId = UuidBinary.newV7();
        String externalId = "source/private/" + title;
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID documentId = createConnectorDocument(tenant, userId, connectorId, title, externalId, createdAt);
        putConnectorObject(tenant, documentId, createdAt, bytes, title);
        return new Seed(documentId, connectorId, externalId);
    }

    /**
     * 创建连接器文档并写入指定对象（可指定创建时间，用于删除-重现）。
     *
     * @param tenant 租户
     * @param userId 创建人
     * @param connectorId 连接器 ID
     * @param externalId 源对象标识
     * @param title 标题
     * @param bytes 对象内容
     * @param createdAt 创建时间
     * @return 测试种子
     */
    private Seed seedConnectorDocument(
            String tenant,
            UUID userId,
            UUID connectorId,
            String externalId,
            String title,
            byte[] bytes,
            Instant createdAt) {
        Instant normalized = createdAt.truncatedTo(ChronoUnit.MICROS);
        UUID documentId = createConnectorDocument(tenant, userId, connectorId, title, externalId, normalized);
        putConnectorObject(tenant, documentId, normalized, bytes, title);
        return new Seed(documentId, connectorId, externalId);
    }

    /**
     * 写入 CONNECTOR 知识库文档（file_id 为空、connector_id 非空）。
     *
     * @param tenant 租户
     * @param userId 创建人
     * @param connectorId 连接器 ID
     * @param title 标题
     * @param externalId 源对象标识（仅库内）
     * @param createdAt 创建时间
     * @return 文档 ID
     */
    private UUID createConnectorDocument(
            String tenant, UUID userId, UUID connectorId, String title, String externalId, Instant createdAt) {
        return createConnectorDocumentWithId(
                tenant, userId, connectorId, UuidBinary.newV7(), title, externalId, createdAt);
    }

    /**
     * 以指定文档 ID 写入 CONNECTOR 文档。
     *
     * @param tenant 租户
     * @param userId 创建人
     * @param connectorId 连接器 ID
     * @param documentId 文档 ID
     * @param title 标题
     * @param externalId 源对象标识（仅库内）
     * @param createdAt 创建时间
     * @return 文档 ID
     */
    private UUID createConnectorDocumentWithId(
            String tenant,
            UUID userId,
            UUID connectorId,
            UUID documentId,
            String title,
            String externalId,
            Instant createdAt) {
        String extension = ConnectorContentTypes.extension(title);
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenantId, :id, :title, 'CONNECTOR', NULL, :connectorId, :externalId, :checksum,
                            :mimeType, :sizeBytes, 'PENDING', :createdBy, :createdAt, :createdAt, 'TENANT',
                            NULL, 'NONE', 1
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .param("title", title)
                .param("connectorId", UuidBinary.toBytes(connectorId))
                .param("externalId", externalId)
                .param("checksum", "0".repeat(64))
                .param("mimeType", ConnectorContentTypes.mimeType(extension))
                .param("sizeBytes", 1L)
                .param("createdBy", UuidBinary.toBytes(userId))
                .param("createdAt", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        return documentId;
    }

    /**
     * 把连接器对象写入平台桶（与 B6 相同的 key 派生）。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param createdAt 创建时间
     * @param bytes 对象内容
     * @param title 标题（用于 MIME）
     */
    private void putConnectorObject(String tenant, UUID documentId, Instant createdAt, byte[] bytes, String title) {
        String key = ConnectorContentTypes.objectKey(tenant, documentId, createdAt);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .contentType(ConnectorContentTypes.mimeType(ConnectorContentTypes.extension(title)))
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    /**
     * 写入指定模式的知识库索引事件。
     *
     * @param tenant 租户
     * @param userId 发起人
     * @param documentId 文档 ID
     * @param attempt 尝试号
     * @param mode 模式
     * @param aclVersion ACL 版本
     * @param reason 原因
     * @return 事件 ID
     */
    private UUID publish(
            String tenant, UUID userId, UUID documentId, int attempt, String mode, int aclVersion, String reason) {
        UUID runId = data.createRun(tenant, RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("attempt", attempt);
        payload.put("mode", mode);
        payload.put("aclVersion", aclVersion);
        payload.put("reason", reason);
        String key = switch (mode) {
            case "ACL_ONLY" -> "doc:" + documentId + ":acl:" + aclVersion;
            case "DELETE" -> "doc:" + documentId + ":delete:" + aclVersion;
            default -> "doc:" + documentId + ":index:" + attempt;
        };
        return data.createOutbox(
                tenant, runId, EventTypes.KNOWLEDGE_INDEX_REQUESTED, objectMapper.writeValueAsString(payload), key);
    }

    /**
     * 更新文档 ACL 与版本。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param visibility 可见范围
     * @param aclVersion ACL 版本
     */
    private void setAcl(String tenant, UUID documentId, String visibility, int aclVersion) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET visibility = :visibility, acl_version = :aclVersion
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .param("visibility", visibility)
                .param("aclVersion", aclVersion)
                .update();
    }

    /**
     * 软删除文档。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     */
    private void softDelete(String tenant, UUID documentId) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'DELETED', deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
    }

    /**
     * 查询文档状态。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 状态
     */
    private String documentStatus(String tenant, UUID documentId) {
        return jdbcClient
                .sql("SELECT status FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .query(String.class)
                .single();
    }

    /**
     * 查询文档错误码。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 错误码；为空时返回空串
     */
    private String documentErrorCode(String tenant, UUID documentId) {
        return jdbcClient
                .sql("SELECT error_code FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .query(String.class)
                .optional()
                .orElse("");
    }

    /**
     * 查询分块内容。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 按 ordinal 排序的内容
     */
    private List<String> chunks(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT content FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query(String.class)
                .list();
    }

    /**
     * 查询分块 ID。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 按 ordinal 排序的 UUID 字符串
     */
    private List<String> chunkIds(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT id FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")).toString())
                .list();
    }

    /**
     * 统计文档在租户别名下的索引文档数；别名不存在时视为 0。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 数量
     */
    private long countDocuments(String tenant, UUID documentId) {
        if (indexClient.aliasTargets(TenantId.of(tenant)).isEmpty()) {
            return 0;
        }
        Map<String, Object> query = Map.of(
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("tenant_id", tenant)),
                                        Map.of("term", Map.of("document_id", documentId.toString()))))));
        JsonNode response = postJson("/" + indexClient.aliasName(TenantId.of(tenant)) + "/_count", json(query));
        return response.path("count").asLong();
    }

    /**
     * 返回文档索引文档的 _id 集合。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return _id 集合
     */
    private Set<String> uniqueIndexIds(String tenant, UUID documentId) {
        Map<String, Object> request = Map.of(
                "size",
                100,
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("tenant_id", tenant)),
                                        Map.of("term", Map.of("document_id", documentId.toString()))))));
        JsonNode response = postJson("/" + indexClient.aliasName(TenantId.of(tenant)) + "/_search", json(request));
        Set<String> ids = new HashSet<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            ids.add(hit.path("_id").asText());
        }
        return ids;
    }

    /**
     * 返回文档的第一条索引源。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return _source 节点
     */
    private JsonNode firstSource(String tenant, UUID documentId) {
        Map<String, Object> request = Map.of(
                "size",
                1,
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("tenant_id", tenant)),
                                        Map.of("term", Map.of("document_id", documentId.toString()))))));
        JsonNode response = postJson("/" + indexClient.aliasName(TenantId.of(tenant)) + "/_search", json(request));
        return response.path("hits").path("hits").get(0).path("_source");
    }

    /**
     * 发送 JSON POST 并返回解析结果。
     *
     * @param path 路径
     * @param body JSON 请求体
     * @return JSON 响应
     */
    private JsonNode postJson(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("opensearch query failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch query failed", ex);
        }
    }

    /**
     * 查询 outbox 状态。
     *
     * @param eventId 事件 ID
     * @return 状态
     */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /**
     * 查询运行状态。
     *
     * @param runId 运行 ID
     * @return 状态
     */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 序列化 JSON。
     *
     * @param value 对象
     * @return JSON
     */
    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /**
     * 连接器文档测试种子。
     *
     * @param documentId 文档 ID
     * @param connectorId 连接器 ID
     * @param externalId 源对象标识
     */
    private record Seed(UUID documentId, UUID connectorId, String externalId) {}
}
