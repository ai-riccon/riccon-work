package com.riccon.worker.support;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.persistence.UuidBinary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 集成测试数据构造器。
 *
 * <p>只通过显式 SQL 与 S3 写入构造真实数据，不绕过租户键、外键与状态约束；测试结束后由
 * 基类统一清空数据。ID 统一使用 UUIDv7，和生产路径一致。
 */
public final class TestDataFactory {

    /** 测试租户库使用的 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** 测试对象存储客户端。 */
    private final S3Client s3Client;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 测试桶名。 */
    private final String bucket;

    /**
     * 创建测试数据构造器。
     *
     * @param jdbcClient JDBC 客户端
     * @param s3Client S3 客户端
     * @param objectMapper JSON 序列化器
     * @param bucket 测试桶
     */
    public TestDataFactory(JdbcClient jdbcClient, S3Client s3Client, ObjectMapper objectMapper, String bucket) {
        this.jdbcClient = jdbcClient;
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    /**
     * 创建租户。
     *
     * @param tenantId 租户 ID
     */
    public void createTenant(String tenantId) {
        jdbcClient
                .sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """)
                .param("tenantId", tenantId)
                .param("name", "Test " + tenantId)
                .update();
    }

    /**
     * 创建账号。
     *
     * @param tenantId 租户
     * @return 账号 ID
     */
    public UUID createUser(String tenantId) {
        UUID userId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO app_user
                            (tenant_id, id, username, display_name, status, auth_source)
                        VALUES
                            (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(userId))
                .param("username", "user-" + userId.toString().substring(0, 8))
                .param("displayName", "Test User")
                .update();
        return userId;
    }

    /**
     * 创建个人工作空间。
     *
     * @param tenantId 租户
     * @param userId 所有者
     * @return 工作空间 ID
     */
    public UUID createWorkspace(String tenantId, UUID userId) {
        UUID workspaceId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO workspace
                            (tenant_id, id, kind, name, owner_user_id, personal_owner_user_id, created_by)
                        VALUES
                            (:tenantId, :id, 'PERSONAL', :name, :userId, :userId, :userId)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(workspaceId))
                .param("name", "Personal Space")
                .param("userId", UuidBinary.toBytes(userId))
                .update();
        return workspaceId;
    }

    /**
     * 上传对象并创建文件元数据。
     *
     * @param tenantId 租户
     * @param workspaceId 工作空间
     * @param userId 上传人
     * @param format 文件格式
     * @param content 文件内容
     * @return 文件 ID
     */
    public UUID createFile(String tenantId, UUID workspaceId, UUID userId, FileFormat format, byte[] content) {
        UUID fileId = UuidBinary.newV7();
        String storageKey = "tenants/" + tenantId + "/file/2026/" + fileId;
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(storageKey)
                        .contentType(mimeType(format))
                        .build(),
                RequestBody.fromBytes(content));
        jdbcClient
                .sql("""
                        INSERT INTO file
                            (tenant_id, id, workspace_id, uploaded_by, original_name, format,
                             mime_type, size_bytes, sha256, storage_key, status)
                        VALUES
                            (:tenantId, :id, :workspaceId, :userId, :originalName, :format,
                             :mimeType, :sizeBytes, :sha256, :storageKey, 'AVAILABLE')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(fileId))
                .param("workspaceId", UuidBinary.toBytes(workspaceId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("originalName", "sample." + format.name().toLowerCase())
                .param("format", format.name())
                .param("mimeType", mimeType(format))
                .param("sizeBytes", content.length)
                .param("sha256", sha256(content))
                .param("storageKey", storageKey)
                .update();
        return fileId;
    }

    /**
     * 创建 PENDING 解析尝试。
     *
     * @param tenantId 租户
     * @param fileId 文件 ID
     * @param attempt 尝试号
     * @return 解析尝试 ID
     */
    public UUID createParse(String tenantId, UUID fileId, int attempt) {
        UUID parseId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO file_parse (tenant_id, id, file_id, attempt, status)
                        VALUES (:tenantId, :id, :fileId, :attempt, 'PENDING')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(parseId))
                .param("fileId", UuidBinary.toBytes(fileId))
                .param("attempt", attempt)
                .update();
        return parseId;
    }

    /**
     * 创建 PENDING 运行。
     *
     * @param tenantId 租户
     * @param kind 运行类别
     * @param fileId 文件 ID；知识库索引可为空
     * @param userId 发起人
     * @param maxAttempts 最大尝试次数
     * @return 运行 ID
     */
    public UUID createRun(String tenantId, RunKind kind, UUID fileId, UUID userId, int maxAttempts) {
        UUID runId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run
                            (tenant_id, id, kind, file_id, user_id, status, attempt, max_attempts, trace_id)
                        VALUES
                            (:tenantId, :id, :kind, :fileId, :userId, 'PENDING', 1, :maxAttempts, :traceId)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(runId))
                .param("kind", kind.name())
                .param("fileId", fileId == null ? null : UuidBinary.toBytes(fileId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("maxAttempts", maxAttempts)
                .param("traceId", "trace-" + runId.toString().substring(0, 8))
                .update();
        return runId;
    }

    /**
     * 创建知识库文档。
     *
     * @param tenantId 租户
     * @param title 标题
     * @param fileId 来源文件；演示语料为空
     * @param createdBy 创建人
     * @return 文档 ID
     */
    public UUID createKnowledgeDocument(String tenantId, String title, UUID fileId, UUID createdBy) {
        UUID documentId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document
                            (tenant_id, id, title, source_type, file_id, checksum,
                             mime_type, size_bytes, status, created_by)
                        VALUES
                            (:tenantId, :id, :title, 'UPLOAD', :fileId, :checksum,
                             :mimeType, :sizeBytes, 'PENDING', :createdBy)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(documentId))
                .param("title", title)
                .param("fileId", fileId == null ? null : UuidBinary.toBytes(fileId))
                .param("checksum", sha256(title.getBytes(StandardCharsets.UTF_8)))
                .param("mimeType", fileId == null ? "text/plain" : "application/octet-stream")
                .param("sizeBytes", (long) title.length())
                .param("createdBy", UuidBinary.toBytes(createdBy))
                .update();
        return documentId;
    }

    /**
     * 写入演示语料分块（无来源文件）。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param ordinal 序号
     * @param content 内容
     * @param paragraphNo 段落号
     * @return 分块 ID
     */
    public UUID createKnowledgeChunk(String tenantId, UUID documentId, int ordinal, String content, int paragraphNo) {
        UUID chunkId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_chunk
                            (tenant_id, id, document_id, ordinal, locator_type,
                             paragraph_no, content, token_count)
                        VALUES
                            (:tenantId, :id, :documentId, :ordinal, 'PARAGRAPH',
                             :paragraphNo, :content, :tokenCount)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(chunkId))
                .param("documentId", UuidBinary.toBytes(documentId))
                .param("ordinal", ordinal)
                .param("paragraphNo", paragraphNo)
                .param("content", content)
                .param("tokenCount", (content.length() + 3) / 4)
                .update();
        return chunkId;
    }

    /**
     * 创建 outbox 事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param eventType 事件类型
     * @param payload 事件 payload JSON
     * @param idempotencyKey 幂等键
     * @return 事件 ID
     */
    public UUID createOutbox(String tenantId, UUID runId, String eventType, String payload, String idempotencyKey) {
        UUID eventId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run_outbox
                            (tenant_id, id, run_id, event_type, event_version,
                             idempotency_key, payload, status, attempts, available_at)
                        VALUES
                            (:tenantId, :id, :runId, :eventType, 1,
                             :idempotencyKey, :payload, 'PENDING', 0, NOW(6))
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(eventId))
                .param("runId", UuidBinary.toBytes(runId))
                .param("eventType", eventType)
                .param("idempotencyKey", idempotencyKey)
                .param("payload", payload)
                .update();
        return eventId;
    }

    /**
     * 创建文件解析运行与 outbox 事件。
     *
     * @param tenantId 租户
     * @param fileId 文件 ID
     * @param parseId 解析尝试 ID
     * @param userId 发起人
     * @param attempt 尝试号
     * @return 已发布事件
     */
    public PublishedEvent publishFileParse(String tenantId, UUID fileId, UUID parseId, UUID userId, int attempt) {
        UUID runId = createRun(tenantId, RunKind.FILE_PARSE, fileId, userId, 3);
        String payload = fileParsePayload(fileId, parseId, attempt);
        UUID eventId = createOutbox(
                tenantId,
                runId,
                com.riccon.platform.domain.contract.EventTypes.FILE_PARSE_REQUESTED,
                payload,
                "file:" + fileId + ":parse:" + attempt);
        return new PublishedEvent(runId, eventId, payload);
    }

    /**
     * 创建知识库索引运行与 outbox 事件。
     *
     * @param tenantId 租户
     * @param documentId 文档 ID
     * @param fileId 来源文件；可为空
     * @param userId 发起人
     * @param attempt 尝试号
     * @return 已发布事件
     */
    public PublishedEvent publishKnowledgeIndex(
            String tenantId, UUID documentId, UUID fileId, UUID userId, int attempt) {
        UUID runId = createRun(tenantId, RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        String payload = knowledgeIndexPayload(documentId, fileId, attempt);
        UUID eventId = createOutbox(
                tenantId,
                runId,
                com.riccon.platform.domain.contract.EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                payload,
                "knowledge:" + documentId + ":" + attempt);
        return new PublishedEvent(runId, eventId, payload);
    }

    /**
     * 构造文件解析 payload。
     *
     * @param fileId 文件 ID
     * @param parseId 解析尝试 ID
     * @param attempt 尝试号
     * @return JSON
     */
    public String fileParsePayload(UUID fileId, UUID parseId, int attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", fileId.toString());
        payload.put("parseId", parseId.toString());
        payload.put("attempt", attempt);
        return json(payload);
    }

    /**
     * 构造知识库索引 payload。
     *
     * @param documentId 文档 ID
     * @param fileId 来源文件；可为空
     * @param attempt 尝试号
     * @return JSON
     */
    public String knowledgeIndexPayload(UUID documentId, UUID fileId, int attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        if (fileId != null) {
            payload.put("fileId", fileId.toString());
        }
        payload.put("attempt", attempt);
        return json(payload);
    }

    /**
     * 序列化 payload。
     *
     * @param value 对象
     * @return JSON
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize test payload", ex);
        }
    }

    /**
     * 计算 SHA-256 十六进制。
     *
     * @param content 内容
     * @return 64 位小写十六进制
     */
    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * 根据格式返回 MIME。
     *
     * @param format 格式
     * @return MIME
     */
    private static String mimeType(FileFormat format) {
        return switch (format) {
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
        };
    }

    /**
     * 已发布事件。
     *
     * @param runId 运行 ID
     * @param eventId 事件 ID
     * @param payload payload JSON
     */
    public record PublishedEvent(UUID runId, UUID eventId, String payload) {}
}
