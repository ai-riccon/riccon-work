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
     * <p>V9 收紧后 visibility 为 NOT NULL（ADR-0018 §6.2），测试种子必须与生产写入方一致，
     * 显式写 PRIVATE + 空部门；否则 MySQL 严格模式会拒绝插入。
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
                            (tenant_id, id, kind, name, owner_user_id, personal_owner_user_id, created_by,
                             department_id, visibility)
                        VALUES
                            (:tenantId, :id, 'PERSONAL', :name, :userId, :userId, :userId, NULL, 'PRIVATE')
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
     * 创建会话。
     *
     * @param tenantId 租户
     * @param workspaceId 工作空间
     * @param userId 创建人
     * @return 会话 ID
     */
    public UUID createConversation(String tenantId, UUID workspaceId, UUID userId) {
        UUID conversationId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO conversation
                            (tenant_id, id, workspace_id, created_by, title, status)
                        VALUES
                            (:tenantId, :id, :workspaceId, :userId, 'Test conversation', 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(conversationId))
                .param("workspaceId", UuidBinary.toBytes(workspaceId))
                .param("userId", UuidBinary.toBytes(userId))
                .update();
        return conversationId;
    }

    /**
     * 创建消息。
     *
     * @param tenantId 租户
     * @param conversationId 会话
     * @param seq 序号
     * @param role 角色
     * @param content 内容
     * @return 消息 ID
     */
    public UUID createMessage(String tenantId, UUID conversationId, long seq, String role, String content) {
        UUID messageId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO message
                            (tenant_id, id, conversation_id, seq, role, content, status)
                        VALUES
                            (:tenantId, :id, :conversationId, :seq, :role, :content, 'COMPLETED')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(messageId))
                .param("conversationId", UuidBinary.toBytes(conversationId))
                .param("seq", seq)
                .param("role", role)
                .param("content", content)
                .update();
        return messageId;
    }

    /**
     * 创建 CHAT 运行。
     *
     * @param tenantId 租户
     * @param conversationId 会话
     * @param userId 用户
     * @return 运行 ID
     */
    public UUID createChatRun(String tenantId, UUID conversationId, UUID userId) {
        UUID runId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run
                            (tenant_id, id, kind, conversation_id, user_id, status, attempt, max_attempts, trace_id)
                        VALUES
                            (:tenantId, :id, 'CHAT', :conversationId, :userId, 'PENDING', 1, 3, :traceId)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(runId))
                .param("conversationId", UuidBinary.toBytes(conversationId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("traceId", traceFor(runId))
                .update();
        return runId;
    }

    /**
     * 创建报告任务。
     *
     * @param tenantId 租户
     * @param workspaceId 工作空间
     * @param userId 用户
     * @param topic 主题
     * @param mcpToolId 工具 ID，可为空
     * @return 任务 ID
     */
    public UUID createResearchTask(String tenantId, UUID workspaceId, UUID userId, String topic, UUID mcpToolId) {
        UUID taskId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO research_task
                            (tenant_id, id, workspace_id, created_by, topic, status, mcp_tool_id, idempotency_key)
                        VALUES
                            (:tenantId, :id, :workspaceId, :userId, :topic, 'PENDING', :mcpToolId, :idempotencyKey)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(taskId))
                .param("workspaceId", UuidBinary.toBytes(workspaceId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("topic", topic)
                .param("mcpToolId", mcpToolId == null ? null : UuidBinary.toBytes(mcpToolId))
                .param("idempotencyKey", "task-" + taskId)
                .update();
        return taskId;
    }

    /**
     * 创建 RESEARCH_REPORT 运行。
     *
     * @param tenantId 租户
     * @param taskId 报告任务
     * @param userId 用户
     * @return 运行 ID
     */
    public UUID createResearchRun(String tenantId, UUID taskId, UUID userId) {
        UUID runId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO agent_run
                            (tenant_id, id, kind, research_task_id, user_id, status, attempt, max_attempts,
                             trace_id, current_stage)
                        VALUES
                            (:tenantId, :id, 'RESEARCH_REPORT', :taskId, :userId, 'PENDING', 1, 3,
                             :traceId, 'RETRIEVAL')
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(runId))
                .param("taskId", UuidBinary.toBytes(taskId))
                .param("userId", UuidBinary.toBytes(userId))
                .param("traceId", traceFor(runId))
                .update();
        return runId;
    }

    /**
     * 写入模型目录、DEK 与部署级凭据。
     *
     * @param baseUrl 模型端点
     * @param apiKey 测试 API key
     * @return 模型 ID
     */
    public UUID createModelCatalog(String baseUrl, String apiKey) {
        UUID providerId = UuidBinary.newV7();
        UUID modelId = UuidBinary.newV7();
        UUID credentialId = UuidBinary.newV7();
        String keyId = "dek-test";
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped = TestCredentials.wrap(
                TestCredentials.MASTER_KEY_BASE64, "platform", TestCredentials.PURPOSE, keyId, dek);
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'Stub', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(providerId))
                .param("code", "openai-compatible-" + providerId.toString().substring(0, 8))
                .param("baseUrl", baseUrl)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model
                            (id, provider_id, model_key, display_name, context_window, max_output_tokens, status)
                        VALUES (:id, :providerId, 'fake-model', 'Fake Model', 8192, 1024, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(modelId))
                .param("providerId", UuidBinary.toBytes(providerId))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO tenant_data_key
                            (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                        VALUES ('platform', :keyId, :purpose, :wrappedDek, :wrapNonce, 'AES-256-GCM', :masterKeyId)
                        """)
                .param("keyId", keyId)
                .param("purpose", TestCredentials.PURPOSE)
                .param("wrappedDek", wrapped.wrappedDek())
                .param("wrapNonce", wrapped.nonce())
                .param("masterKeyId", TestCredentials.MASTER_KEY_ID)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO provider_credential
                            (tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id, version, status)
                        VALUES ('platform', :id, :providerId, 'API_KEY', :ciphertext, :keyId, :masterKeyId, 1, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(credentialId))
                .param("providerId", UuidBinary.toBytes(providerId))
                .param(
                        "ciphertext",
                        TestCredentials.encryptCredential("platform", TestCredentials.PURPOSE, keyId, dek, apiKey))
                .param("keyId", keyId)
                .param("masterKeyId", TestCredentials.MASTER_KEY_ID)
                .update();
        return modelId;
    }

    /**
     * 创建 MCP server。
     *
     * @param code server code
     * @param command stdio 命令
     * @return server ID
     */
    public UUID createMcpServer(String code, String command) {
        UUID serverId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, command, status)
                        VALUES (:id, :code, 'Builtin stub', 'STDIO', :command, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(serverId))
                .param("code", code)
                .param("command", command)
                .update();
        return serverId;
    }

    /**
     * 创建只读、需审批的 MCP 工具。
     *
     * @param serverId server ID
     * @param name 工具名
     * @param inputSchema JSON Schema
     * @return 工具 ID
     */
    public UUID createMcpTool(UUID serverId, String name, String inputSchema) {
        UUID toolId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool
                            (id, server_id, name, description, input_schema, risk_level, requires_approval, status)
                        VALUES (:id, :serverId, :name, 'Echo tool', :inputSchema, 'READ', TRUE, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(toolId))
                .param("serverId", UuidBinary.toBytes(serverId))
                .param("name", name)
                .param("inputSchema", inputSchema)
                .update();
        return toolId;
    }

    /**
     * 授权工具给租户。
     *
     * @param tenantId 租户
     * @param toolId 工具 ID
     */
    public void grantMcpTool(String tenantId, UUID toolId) {
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled)
                        VALUES (:tenantId, :toolId, TRUE)
                        """)
                .param("tenantId", tenantId)
                .param("toolId", UuidBinary.toBytes(toolId))
                .update();
    }

    /**
     * 创建报告任务 + 运行 + requested outbox。
     *
     * @param tenantId 租户
     * @param workspaceId 工作空间
     * @param userId 用户
     * @param topic 主题
     * @param toolId 工具 ID
     * @return 已发布事件
     */
    public ResearchPublished publishResearchRequested(
            String tenantId, UUID workspaceId, UUID userId, String topic, UUID toolId) {
        UUID taskId = createResearchTask(tenantId, workspaceId, userId, topic, toolId);
        UUID runId = createResearchRun(tenantId, taskId, userId);
        String payload = json(Map.of(
                "runId", runId.toString(), "researchTaskId", taskId.toString(), "stage", "RETRIEVAL", "attempt", 1));
        UUID eventId = createOutbox(
                tenantId,
                runId,
                com.riccon.platform.domain.contract.EventTypes.AGENT_RUN_REQUESTED,
                payload,
                "agent-run:" + runId);
        return new ResearchPublished(runId, taskId, eventId);
    }

    /**
     * 写入 resume outbox。
     *
     * @param tenantId 租户
     * @param runId 运行
     * @param taskId 任务
     * @param approvalId 审批
     * @return 事件 ID
     */
    public UUID publishResearchResume(String tenantId, UUID runId, UUID taskId, UUID approvalId) {
        String payload = json(Map.of(
                "runId", runId.toString(),
                "researchTaskId", taskId.toString(),
                "approvalId", approvalId.toString(),
                "stage", "GENERATION",
                "attempt", 1));
        return createOutbox(
                tenantId,
                runId,
                com.riccon.platform.domain.contract.EventTypes.AGENT_RUN_RESUME,
                payload,
                "resume:" + runId + ":" + approvalId);
    }

    /**
     * 写入 cancel outbox。
     *
     * @param tenantId 租户
     * @param runId 运行
     * @param reason 原因
     * @return 事件 ID
     */
    public UUID publishResearchCancel(String tenantId, UUID runId, String reason) {
        String payload = json(Map.of("runId", runId.toString(), "reason", reason));
        return createOutbox(
                tenantId,
                runId,
                com.riccon.platform.domain.contract.EventTypes.AGENT_RUN_CANCEL,
                payload,
                "cancel:" + runId);
    }

    /**
     * 返回运行 traceId。
     *
     * @param runId 运行 ID
     * @return traceId
     */
    public static String traceFor(UUID runId) {
        return "trace-" + runId.toString().substring(0, 8);
    }

    /**
     * 报告任务发布结果。
     *
     * @param runId 运行 ID
     * @param taskId 任务 ID
     * @param eventId outbox 事件 ID
     */
    public record ResearchPublished(UUID runId, UUID taskId, UUID eventId) {}

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
