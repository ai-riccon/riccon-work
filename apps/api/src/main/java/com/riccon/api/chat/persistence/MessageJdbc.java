package com.riccon.api.chat.persistence;

import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.api.chat.domain.MessageRecord;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.chat.MessageRole;
import com.riccon.platform.domain.chat.MessageStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 消息与引用 SQL。
 *
 * <p>所有 SQL 带 {@code tenant_id} 且以 conversation_id 收窄范围。seq 由调用方在会话行锁内分配；消息状态推进
 * 使用 {@code status IN (...)} 条件，重复事件或取消竞态不会把终态回退。引用按 {@code (message_id, ordinal)} 幂等写入。
 */
@Component
public class MessageJdbc {

    /** 消息列。 */
    private static final String MESSAGE_COLUMNS =
            "id, conversation_id, seq, role, content, status, client_message_id, run_id, model_id, error_code, created_at";

    /** 插入消息。 */
    private static final String INSERT = """
            INSERT INTO message (
                tenant_id, id, conversation_id, seq, role, content, status,
                client_message_id, run_id, model_id, error_code, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :conversationId, :seq, :role, :content, :status,
                :clientMessageId, :runId, :modelId, :errorCode, :createdAt, :createdAt
            )
            """;

    /** 会话内最大 seq；调用方必须已锁定会话行。 */
    private static final String MAX_SEQ = """
            SELECT COALESCE(MAX(seq), 0)
              FROM message
             WHERE tenant_id = :tenantId
               AND conversation_id = :conversationId
            """;

    /** 按客户端幂等键查询用户消息。 */
    private static final String FIND_BY_CLIENT_MESSAGE_ID = "SELECT " + MESSAGE_COLUMNS
            + " FROM message"
            + " WHERE tenant_id = :tenantId AND conversation_id = :conversationId"
            + " AND client_message_id = :clientMessageId AND role = 'USER'";

    /** 按运行查询 assistant 消息；用于停止时定位消息。 */
    private static final String FIND_ASSISTANT_BY_RUN = "SELECT " + MESSAGE_COLUMNS
            + " FROM message"
            + " WHERE tenant_id = :tenantId AND run_id = :runId AND role = 'ASSISTANT'"
            + " ORDER BY seq DESC LIMIT 1";

    /** 按 ID 查询消息状态；用于流中断时合成终态事件。 */
    private static final String FIND_STATUS =
            "SELECT status FROM message WHERE tenant_id = :tenantId AND id = :messageId";

    /** 会话消息总数。 */
    private static final String COUNT_BY_CONVERSATION = """
            SELECT COUNT(*)
              FROM message
             WHERE tenant_id = :tenantId
               AND conversation_id = :conversationId
            """;

    /** 追加流式增量；消息已离开 PENDING/STREAMING 时不再写入，避免取消后继续增长。 */
    private static final String APPEND_CONTENT = """
            UPDATE message
               SET content = CONCAT(content, :delta),
                   status = 'STREAMING',
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :messageId
               AND status IN ('PENDING', 'STREAMING')
            """;

    /** 收到 started 后写入模型并进入 STREAMING。 */
    private static final String MARK_STREAMING = """
            UPDATE message
               SET status = 'STREAMING',
                   model_id = :modelId,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :messageId
               AND status IN ('PENDING', 'STREAMING')
            """;

    /** 终态更新；只从 PENDING/STREAMING 推进，取消优先于迟到的 completed/failed。 */
    private static final String MARK_TERMINAL = """
            UPDATE message
               SET status = :status,
                   error_code = :errorCode,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :messageId
               AND status IN ('PENDING', 'STREAMING')
            """;

    /** 按运行推进 assistant 消息终态；停止路径无需先查消息 ID。 */
    private static final String MARK_TERMINAL_BY_RUN = """
            UPDATE message
               SET status = :status,
                   error_code = :errorCode,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND run_id = :runId
               AND role = 'ASSISTANT'
               AND status IN ('PENDING', 'STREAMING')
            """;

    /** 插入引用；重复 ordinal 时保留首次写入，保证流重放幂等。 */
    private static final String INSERT_CITATION = """
            INSERT INTO message_citation (
                tenant_id, id, message_id, ordinal, source_type, file_id, knowledge_document_id,
                chunk_id, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                title_snapshot, snippet, score, created_at
            ) VALUES (
                :tenantId, :id, :messageId, :ordinal, :sourceType, :fileId, :knowledgeDocumentId,
                :chunkId, :locatorType, :pageNo, :sheetName, :cellRange, :paragraphNo,
                :titleSnapshot, :snippet, :score, :createdAt
            ) ON DUPLICATE KEY UPDATE ordinal = ordinal
            """;

    /** 引用列。 */
    private static final String CITATION_COLUMNS =
            "id, message_id, ordinal, source_type, file_id, knowledge_document_id, chunk_id,"
                    + " locator_type, page_no, sheet_name, cell_range, paragraph_no,"
                    + " title_snapshot, snippet, score, created_at";

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建消息 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public MessageJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入消息。
     *
     * @param tenantId 当前租户
     * @param message 消息快照
     */
    public void insert(TenantId tenantId, MessageRecord message) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(message.id()));
        params.put("conversationId", BinaryUuids.toBytes(message.conversationId()));
        params.put("seq", message.seq());
        params.put("role", message.role().name());
        params.put("content", message.content());
        params.put("status", message.status().name());
        params.put("clientMessageId", message.clientMessageId());
        params.put("runId", message.runId() == null ? null : BinaryUuids.toBytes(message.runId()));
        params.put("modelId", message.modelId() == null ? null : BinaryUuids.toBytes(message.modelId()));
        params.put("errorCode", message.errorCode());
        params.put("createdAt", SqlTimestamps.toDatabase(message.createdAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 查询会话内最大 seq。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 最大 seq；无消息时为 0
     */
    public long maxSeq(TenantId tenantId, UUID conversationId) {
        return tenantJdbc.count(tenantId, MAX_SEQ, Map.of("conversationId", BinaryUuids.toBytes(conversationId)));
    }

    /**
     * 按客户端幂等键查询用户消息。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param clientMessageId 客户端幂等键
     * @return 用户消息；不存在时为空
     */
    public Optional<MessageRecord> findByClientMessageId(
            TenantId tenantId, UUID conversationId, String clientMessageId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_BY_CLIENT_MESSAGE_ID,
                Map.of("conversationId", BinaryUuids.toBytes(conversationId), "clientMessageId", clientMessageId),
                MessageJdbc::mapRow);
    }

    /**
     * 按运行查询 assistant 消息。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return assistant 消息；不存在时为空
     */
    public Optional<MessageRecord> findAssistantByRun(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId, FIND_ASSISTANT_BY_RUN, Map.of("runId", BinaryUuids.toBytes(runId)), MessageJdbc::mapRow);
    }

    /**
     * 查询消息状态。
     *
     * @param tenantId 当前租户
     * @param messageId 消息 ID
     * @return 状态；不存在时为空
     */
    public Optional<MessageStatus> findStatus(TenantId tenantId, UUID messageId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_STATUS,
                Map.of("messageId", BinaryUuids.toBytes(messageId)),
                (rs, rowNum) -> MessageStatus.valueOf(rs.getString("status")));
    }

    /**
     * 统计会话消息总数。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 总数
     */
    public long countByConversation(TenantId tenantId, UUID conversationId) {
        return tenantJdbc.count(
                tenantId, COUNT_BY_CONVERSATION, Map.of("conversationId", BinaryUuids.toBytes(conversationId)));
    }

    /**
     * 查询最近消息；{@code beforeSeq} 用于向前翻页。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param beforeSeq 只返回 seq 小于该值的消息；为空时不限制
     * @param limit 条数上限
     * @return 按 seq 升序的消息
     */
    public List<MessageRecord> listLatest(TenantId tenantId, UUID conversationId, Long beforeSeq, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS
                + " FROM message WHERE tenant_id = :tenantId AND conversation_id = :conversationId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conversationId", BinaryUuids.toBytes(conversationId));
        if (beforeSeq != null) {
            sql.append(" AND seq < :beforeSeq");
            params.put("beforeSeq", beforeSeq);
        }
        sql.append(" ORDER BY seq DESC LIMIT :limit");
        params.put("limit", limit);
        List<MessageRecord> descending = tenantJdbc.query(tenantId, sql.toString(), params, MessageJdbc::mapRow);
        List<MessageRecord> ascending = new ArrayList<>(descending.size());
        for (int index = descending.size() - 1; index >= 0; index--) {
            ascending.add(descending.get(index));
        }
        return List.copyOf(ascending);
    }

    /**
     * 追加 assistant 流式内容。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param delta 文本增量
     * @param now 当前时间
     * @return 受影响行数；消息已终止时为 0
     */
    public int appendContent(TenantId tenantId, UUID messageId, String delta, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("messageId", BinaryUuids.toBytes(messageId));
        params.put("delta", delta);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, APPEND_CONTENT, params);
    }

    /**
     * 收到 started 后写入模型并进入 STREAMING。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param modelId 模型 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markStreaming(TenantId tenantId, UUID messageId, UUID modelId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("messageId", BinaryUuids.toBytes(messageId));
        params.put("modelId", BinaryUuids.toBytes(modelId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_STREAMING, params);
    }

    /**
     * 推进消息终态。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param status 终态
     * @param errorCode 失败错误码；成功/停止为空
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminal(TenantId tenantId, UUID messageId, MessageStatus status, String errorCode, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("messageId", BinaryUuids.toBytes(messageId));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_TERMINAL, params);
    }

    /**
     * 按运行推进 assistant 消息终态。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param status 终态
     * @param errorCode 失败错误码；成功/停止为空
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markTerminalByRun(TenantId tenantId, UUID runId, MessageStatus status, String errorCode, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("status", status.name());
        params.put("errorCode", errorCode);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_TERMINAL_BY_RUN, params);
    }

    /**
     * 插入引用快照。
     *
     * @param tenantId 当前租户
     * @param messageId assistant 消息 ID
     * @param citation 引用
     */
    public void insertCitation(TenantId tenantId, UUID messageId, CitationRecord citation) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(citation.id()));
        params.put("messageId", BinaryUuids.toBytes(messageId));
        params.put("ordinal", citation.ordinal());
        params.put("sourceType", citation.sourceType().name());
        params.put("fileId", citation.fileId() == null ? null : BinaryUuids.toBytes(citation.fileId()));
        params.put(
                "knowledgeDocumentId",
                citation.knowledgeDocumentId() == null ? null : BinaryUuids.toBytes(citation.knowledgeDocumentId()));
        params.put("chunkId", citation.chunkId() == null ? null : BinaryUuids.toBytes(citation.chunkId()));
        params.put("locatorType", citation.locatorType().name());
        params.put("pageNo", citation.pageNo());
        params.put("sheetName", citation.sheetName());
        params.put("cellRange", citation.cellRange());
        params.put("paragraphNo", citation.paragraphNo());
        params.put("titleSnapshot", citation.title());
        params.put("snippet", citation.snippet());
        params.put("score", citation.score());
        params.put("createdAt", SqlTimestamps.toDatabase(citation.createdAt()));
        tenantJdbc.insert(tenantId, INSERT_CITATION, params);
    }

    /**
     * 批量查询消息引用。
     *
     * <p>使用固定数量的命名参数，不拼接用户输入；单次调用最多对应一页消息（≤100）。返回顺序保持
     * {@code message_id, ordinal} 升序。
     *
     * @param tenantId 当前租户
     * @param messageIds 消息 ID 列表
     * @return 消息 ID 到引用列表的映射
     */
    public Map<UUID, List<CitationRecord>> listCitations(TenantId tenantId, List<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + CITATION_COLUMNS
                + " FROM message_citation WHERE tenant_id = :tenantId AND message_id IN (");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < messageIds.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "messageId" + index;
            sql.append(':').append(name);
            params.put(name, BinaryUuids.toBytes(messageIds.get(index)));
        }
        sql.append(") ORDER BY message_id, ordinal");
        List<Map.Entry<UUID, CitationRecord>> rows = tenantJdbc.query(
                tenantId,
                sql.toString(),
                params,
                (rs, rowNum) -> Map.entry(BinaryUuids.fromBytes(rs.getBytes("message_id")), mapCitation(rs, rowNum)));
        Map<UUID, List<CitationRecord>> grouped = new LinkedHashMap<>();
        for (Map.Entry<UUID, CitationRecord> row : rows) {
            grouped.computeIfAbsent(row.getKey(), key -> new ArrayList<>()).add(row.getValue());
        }
        return grouped;
    }

    /**
     * 映射消息行；引用由服务层批量附加。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 消息快照
     * @throws SQLException 列读取失败
     */
    private static MessageRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("conversation_id")),
                rs.getLong("seq"),
                MessageRole.valueOf(rs.getString("role")),
                rs.getString("content"),
                MessageStatus.valueOf(rs.getString("status")),
                rs.getString("client_message_id"),
                rs.getBytes("run_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("run_id")),
                rs.getBytes("model_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("model_id")),
                rs.getString("error_code"),
                Collections.emptyList(),
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /**
     * 映射引用行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 引用快照
     * @throws SQLException 列读取失败
     */
    private static CitationRecord mapCitation(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal score = rs.getBigDecimal("score");
        return new CitationRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getInt("ordinal"),
                CitationSourceType.valueOf(rs.getString("source_type")),
                rs.getBytes("file_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("file_id")),
                rs.getBytes("knowledge_document_id") == null
                        ? null
                        : BinaryUuids.fromBytes(rs.getBytes("knowledge_document_id")),
                rs.getBytes("chunk_id") == null ? null : BinaryUuids.fromBytes(rs.getBytes("chunk_id")),
                LocatorType.valueOf(rs.getString("locator_type")),
                rs.getObject("page_no", Integer.class),
                rs.getString("sheet_name"),
                rs.getString("cell_range"),
                rs.getObject("paragraph_no", Integer.class),
                rs.getString("title_snapshot"),
                rs.getString("snippet"),
                score,
                SqlTimestamps.toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }
}
