package com.riccon.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.persistence.UuidBinary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 知识库与文件检索、租户隔离和引用序号。 */
class KnowledgeRetrieverIntegrationTest extends WorkerIntegrationTestBase {

    /** 检索服务。 */
    @Autowired
    private KnowledgeRetriever retriever;

    /** 知识库命中带分数与标题。 */
    @Test
    void retrieve_knowledge_returnsScoredChunks() {
        String tenant = uniqueTenant("tenant-a");
        UUID user = seedTenant(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "市场报告", null, user);
        data.createKnowledgeChunk(tenant, documentId, 1, "市场增长很快，客户需求旺盛。", 1);
        data.createKnowledgeChunk(tenant, documentId, 2, "无关的财务说明。", 2);
        markIndexed(tenant, documentId);

        List<RetrievedChunk> chunks = TenantContext.callWithTenant(
                TenantId.of(tenant), () -> retriever.retrieve(TenantId.of(tenant), "市场", List.of()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).title()).isEqualTo("市场报告");
        assertThat(chunks.get(0).score()).isGreaterThan(0);
        assertThat(chunks.get(0).ordinal()).isEqualTo(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chunkId()).isNotNull());
    }

    /** 文件分块与知识库结果合并后连续编号。 */
    @Test
    void retrieve_withFile_renumbersSources() {
        String tenant = uniqueTenant("tenant-a");
        UUID user = seedTenant(tenant);
        UUID workspaceId = data.createWorkspace(tenant, user);
        UUID fileId = createParsedFile(tenant, workspaceId, user, "上传资料", "文件中的市场信息");
        UUID documentId = data.createKnowledgeDocument(tenant, "知识库资料", null, user);
        data.createKnowledgeChunk(tenant, documentId, 1, "知识库中的市场信息", 1);
        markIndexed(tenant, documentId);

        List<RetrievedChunk> chunks = TenantContext.callWithTenant(
                TenantId.of(tenant), () -> retriever.retrieve(TenantId.of(tenant), "市场", List.of(fileId)));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).sourceType().name()).isEqualTo("FILE");
        assertThat(chunks.get(0).ordinal()).isEqualTo(1);
        assertThat(chunks.get(chunks.size() - 1).ordinal()).isEqualTo(chunks.size());
    }

    /** 跨租户文件统一返回 404。 */
    @Test
    void retrieve_fileFromOtherTenant_failsNotFound() {
        String tenantAId = uniqueTenant("tenant-a");
        String tenantBId = uniqueTenant("tenant-b");
        UUID userA = seedTenant(tenantAId);
        UUID userB = seedTenant(tenantBId);
        UUID workspaceB = data.createWorkspace(tenantBId, userB);
        UUID foreignFile = createParsedFile(tenantBId, workspaceB, userB, "B 文件", "B 的内容");
        TenantId tenantA = TenantId.of(tenantAId);
        assertThat(userA).isNotNull();

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenantA, () -> retriever.retrieve(tenantA, "内容", List.of(foreignFile))))
                .isInstanceOf(AgentException.class)
                .satisfies(error ->
                        assertThat(((AgentException) error).errorCode()).isEqualTo(AgentException.NOT_FOUND));
    }

    /** 空查询返回空知识库结果，但文件仍可加载。 */
    @Test
    void retrieve_blankQuery_returnsFileChunksOnly() {
        String tenant = uniqueTenant("tenant-a");
        UUID user = seedTenant(tenant);
        UUID workspaceId = data.createWorkspace(tenant, user);
        UUID fileId = createParsedFile(tenant, workspaceId, user, "资料", "正文");

        List<RetrievedChunk> chunks = TenantContext.callWithTenant(
                TenantId.of(tenant), () -> retriever.retrieve(TenantId.of(tenant), " ", List.of(fileId)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sourceType().name()).isEqualTo("FILE");
        assertThat(TenantContext.callWithTenant(
                        TenantId.of(tenant), () -> retriever.retrieveKnowledge(TenantId.of(tenant), "")))
                .isEmpty();
    }

    /** 创建租户与用户。 */
    private UUID seedTenant(String tenant) {
        data.createTenant(tenant);
        return data.createUser(tenant);
    }

    /** 把文档标记为已索引。 */
    private void markIndexed(String tenant, UUID documentId) {
        jdbcClient
                .sql("UPDATE knowledge_document SET status = 'INDEXED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
    }

    /** 创建带成功解析分块的文件。 */
    private UUID createParsedFile(String tenant, UUID workspaceId, UUID userId, String name, String content) {
        UUID fileId = UuidBinary.newV7();
        UUID parseId = UuidBinary.newV7();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        jdbcClient
                .sql("""
                        INSERT INTO file
                            (tenant_id, id, workspace_id, uploaded_by, original_name, format,
                             mime_type, size_bytes, sha256, storage_key, status)
                        VALUES
                            (:tenantId, :id, :workspaceId, :userId, :name, 'PNG',
                             'image/png', :size, :sha256, :storageKey, 'AVAILABLE')
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(fileId))
                .param("workspaceId", UuidBinary.toBytes(workspaceId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("name", name)
                .param("size", bytes.length)
                .param("sha256", sha256(bytes))
                .param("storageKey", "tenants/" + tenant + "/file/2026/" + fileId)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO file_parse (tenant_id, id, file_id, attempt, status, chunk_count)
                        VALUES (:tenantId, :id, :fileId, 1, 'SUCCEEDED', 1)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(parseId))
                .param("fileId", UuidBinary.toBytes(fileId))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO file_chunk
                            (tenant_id, id, file_id, parse_id, ordinal, locator_type, page_no, content, token_count)
                        VALUES
                            (:tenantId, :id, :fileId, :parseId, 1, 'PAGE', 1, :content, 4)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(UuidBinary.newV7()))
                .param("fileId", UuidBinary.toBytes(fileId))
                .param("parseId", UuidBinary.toBytes(parseId))
                .param("content", content)
                .update();
        return fileId;
    }

    /** SHA-256。 */
    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
