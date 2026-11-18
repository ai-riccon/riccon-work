package com.riccon.api.chat.persistence;

import com.riccon.api.chat.domain.ConversationRecord;
import com.riccon.api.chat.domain.ConversationStatus;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 会话 SQL。
 *
 * <p>所有查询由 {@link TenantJdbc} 强制带 {@code tenant_id}。M03 起会话可见性由所属工作空间的
 * {@code WorkspaceService.requireAccessible} 决定：本仓储只按 {@code (tenant_id, id)} 与工作空间过滤，
 * 不复制空间可见性 SQL；调用方必须先完成空间判定。软删除会话不物理删除消息与引用。
 */
@Component
public class ConversationJdbc {

    /** 响应与审计共用的列。 */
    private static final String COLUMNS =
            "id, workspace_id, created_by, title, status, last_message_at, created_at, updated_at";

    /** 按租户读取未删除会话；可见性由调用方先经工作空间判定。 */
    private static final String FIND_BY_ID = "SELECT " + COLUMNS
            + " FROM conversation"
            + " WHERE tenant_id = :tenantId AND id = :conversationId AND deleted_at IS NULL";

    /** 发送消息前锁定会话行，串行分配 seq 并阻止并发删除。 */
    private static final String FIND_BY_ID_FOR_UPDATE = FIND_BY_ID + " FOR UPDATE";

    /** 插入会话；retention_expires_at 由服务端按租户保留策略计算。 */
    private static final String INSERT = """
            INSERT INTO conversation (
                tenant_id, id, workspace_id, created_by, title, status, last_message_at,
                retention_expires_at, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :workspaceId, :createdBy, :title, :status, :lastMessageAt,
                :retentionExpiresAt, :createdAt, :updatedAt
            )
            """;

    /** 列表只返回 ACTIVE 且未删除的会话，并限定在工作空间内。 */
    private static final String ACTIVE_FILTER = """
             FROM conversation
            WHERE tenant_id = :tenantId
              AND workspace_id = :workspaceId
              AND deleted_at IS NULL
              AND status = 'ACTIVE'
            """;

    /** 统计工作空间内活跃会话。 */
    private static final String COUNT_ACTIVE = "SELECT COUNT(*) " + ACTIVE_FILTER;

    /** 分页列出工作空间内活跃会话；最近有消息的排前。 */
    private static final String LIST_ACTIVE = "SELECT " + COLUMNS + " " + ACTIVE_FILTER
            + " ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC LIMIT :limit OFFSET :offset";

    /** 修改标题；创建人校验由服务层完成。 */
    private static final String UPDATE_TITLE = """
            UPDATE conversation
               SET title = :title,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :conversationId
               AND deleted_at IS NULL
            """;

    /**
     * 发送消息后刷新会话：首条消息自动生成标题（已有标题不覆盖），并推进最近消息时间。
     * 标题生成在 SQL 中使用 CASE，避免并发两个首条消息时后到者覆盖先到者。
     */
    private static final String TOUCH_AFTER_MESSAGE = """
            UPDATE conversation
               SET title = CASE WHEN title IS NULL THEN :title ELSE title END,
                   last_message_at = :now,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :conversationId
               AND deleted_at IS NULL
            """;

    /** 软删除；消息、引用与运行记录保留至租户保留期结束。 */
    private static final String SOFT_DELETE = """
            UPDATE conversation
               SET deleted_at = :now,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :conversationId
               AND deleted_at IS NULL
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建会话 SQL 组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public ConversationJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入会话。
     *
     * @param tenantId 当前租户
     * @param conversation 会话快照
     * @param retentionExpiresAt 保留到期时间
     */
    public void insert(TenantId tenantId, ConversationRecord conversation, Instant retentionExpiresAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(conversation.id()));
        params.put("workspaceId", BinaryUuids.toBytes(conversation.workspaceId()));
        params.put("createdBy", BinaryUuids.toBytes(conversation.createdBy()));
        params.put("title", conversation.title());
        params.put("status", conversation.status().name());
        params.put(
                "lastMessageAt",
                conversation.lastMessageAt() == null ? null : SqlTimestamps.toDatabase(conversation.lastMessageAt()));
        params.put("retentionExpiresAt", SqlTimestamps.toDatabase(retentionExpiresAt));
        params.put("createdAt", SqlTimestamps.toDatabase(conversation.createdAt()));
        params.put("updatedAt", SqlTimestamps.toDatabase(conversation.updatedAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 按租户读取未删除会话。
     *
     * <p>不做可见性判断；调用方必须先按 {@code workspace_id} 通过
     * {@code WorkspaceService.requireAccessible}，再使用本方法的结果。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 会话；不存在、已删除或属于其他租户时为空
     */
    public Optional<ConversationRecord> findById(TenantId tenantId, UUID conversationId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_BY_ID,
                Map.of("conversationId", BinaryUuids.toBytes(conversationId)),
                ConversationJdbc::mapRow);
    }

    /**
     * 锁定并查询未删除会话，供发送消息串行分配 seq。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @return 会话；不存在时为空
     */
    public Optional<ConversationRecord> findByIdForUpdate(TenantId tenantId, UUID conversationId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_BY_ID_FOR_UPDATE,
                Map.of("conversationId", BinaryUuids.toBytes(conversationId)),
                ConversationJdbc::mapRow);
    }

    /**
     * 统计工作空间内活跃会话。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @return 总数
     */
    public long countActive(TenantId tenantId, UUID workspaceId) {
        return tenantJdbc.count(tenantId, COUNT_ACTIVE, Map.of("workspaceId", BinaryUuids.toBytes(workspaceId)));
    }

    /**
     * 分页列出工作空间内活跃会话。
     *
     * @param tenantId 当前租户
     * @param workspaceId 工作空间 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 会话列表
     */
    public List<ConversationRecord> listActive(TenantId tenantId, UUID workspaceId, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workspaceId", BinaryUuids.toBytes(workspaceId));
        params.put("limit", limit);
        params.put("offset", offset);
        return tenantJdbc.query(tenantId, LIST_ACTIVE, params, ConversationJdbc::mapRow);
    }

    /**
     * 修改会话标题。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param title 新标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int updateTitle(TenantId tenantId, UUID conversationId, String title, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conversationId", BinaryUuids.toBytes(conversationId));
        params.put("title", title);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, UPDATE_TITLE, params);
    }

    /**
     * 发送消息后刷新标题与最近消息时间。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param autoTitle 空标题时使用的自动标题
     * @param now 当前时间
     * @return 受影响行数
     */
    public int touchAfterMessage(TenantId tenantId, UUID conversationId, String autoTitle, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conversationId", BinaryUuids.toBytes(conversationId));
        params.put("title", autoTitle);
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, TOUCH_AFTER_MESSAGE, params);
    }

    /**
     * 软删除会话。
     *
     * @param tenantId 当前租户
     * @param conversationId 会话 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int softDelete(TenantId tenantId, UUID conversationId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("conversationId", BinaryUuids.toBytes(conversationId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, SOFT_DELETE, params);
    }

    /**
     * 映射会话行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 会话快照
     * @throws SQLException 列读取失败
     */
    private static ConversationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationRecord(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("workspace_id")),
                BinaryUuids.fromBytes(rs.getBytes("created_by")),
                rs.getString("title"),
                ConversationStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("last_message_at", LocalDateTime.class)),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    /**
     * 将数据库 UTC 时间转为 Instant。
     *
     * @param value 数据库时间
     * @return UTC 时间；null 原样返回
     */
    private static Instant toInstant(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
