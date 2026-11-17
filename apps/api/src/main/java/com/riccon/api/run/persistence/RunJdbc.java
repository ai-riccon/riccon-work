package com.riccon.api.run.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.api.run.domain.RunSnapshot;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Agent 运行持久化。
 *
 * <p>API 只创建 PENDING 运行并交给 Worker 推进；文件解析与知识库索引的运行类别决定关联字段：
 * FILE_PARSE 必须有 file_id，KNOWLEDGE_INDEX 不绑定文件。SQL 由 {@link TenantJdbc} 强制租户条件。
 */
@Component
public class RunJdbc {

    /** 插入 PENDING 运行；max_attempts 固定为 3，与 ADR-0008 的默认重试上限一致。 */
    private static final String INSERT = """
            INSERT INTO agent_run (
                tenant_id, id, kind, file_id, user_id, status, attempt, max_attempts,
                trace_id, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :kind, :fileId, :userId, 'PENDING', 1, 3,
                :traceId, :createdAt, :createdAt
            )
            """;

    /** 插入对话运行；按 chk_agent_run_kind，CHAT 必须有 conversation_id 且 file_id/research_task_id 为空。 */
    private static final String INSERT_CHAT = """
            INSERT INTO agent_run (
                tenant_id, id, kind, conversation_id, user_id, status, attempt, max_attempts,
                trace_id, created_at, updated_at
            ) VALUES (
                :tenantId, :id, 'CHAT', :conversationId, :userId, 'PENDING', 1, 3,
                :traceId, :createdAt, :createdAt
            )
            """;

    /** 按租户读取运行快照。 */
    private static final String FIND_CHAT = """
            SELECT id, kind, status, conversation_id, user_id, attempt
              FROM agent_run
             WHERE tenant_id = :tenantId
               AND id = :runId
            """;

    /** 首个 SSE 事件到达后把 PENDING 运行推进为 RUNNING；Worker 已推进时影响 0 行。 */
    private static final String MARK_RUNNING = """
            UPDATE agent_run
               SET status = 'RUNNING',
                   started_at = COALESCE(started_at, :now),
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status = 'PENDING'
            """;

    /** 运行成功；只从非终态推进，避免取消后被迟到的 completed 覆盖。 */
    private static final String MARK_SUCCEEDED = """
            UPDATE agent_run
               SET status = 'SUCCEEDED',
                   finished_at = COALESCE(finished_at, :now),
                   updated_at = :now,
                   error_code = NULL,
                   error_message = NULL
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status IN ('PENDING', 'RUNNING')
            """;

    /** 运行失败；已取消/已成功时不回退。 */
    private static final String MARK_FAILED = """
            UPDATE agent_run
               SET status = 'FAILED',
                   finished_at = COALESCE(finished_at, :now),
                   updated_at = :now,
                   error_code = :errorCode,
                   error_message = :errorMessage
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'WAITING_APPROVAL')
            """;

    /** 运行取消；已成功/已失败时不覆盖终态。 */
    private static final String MARK_CANCELLED = """
            UPDATE agent_run
               SET status = 'CANCELLED',
                   finished_at = COALESCE(finished_at, :now),
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND id = :runId
               AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'WAITING_APPROVAL')
            """;

    /**
     * 回填模型调用与 assistant 消息的关联。
     *
     * <p>Worker 在收到首字前插入 model_invocation 时还不知道 API 侧的 assistant 消息 ID（内部契约不含 messageId），
     * 因此先留空；API 收到 started 后按 runId 回填。重复事件影响 0 行，不视为错误。
     */
    private static final String ATTACH_INVOCATION_MESSAGE = """
            UPDATE model_invocation
               SET message_id = :messageId
             WHERE tenant_id = :tenantId
               AND run_id = :runId
               AND message_id IS NULL
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建运行持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public RunJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 插入 PENDING 运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param kind 运行类别
     * @param fileId 关联文件；知识库索引为 {@code null}
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void insertPending(
            TenantId tenantId, UUID runId, RunKind kind, UUID fileId, UUID userId, String traceId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(runId));
        params.put("kind", kind.name());
        params.put("fileId", fileId == null ? null : BinaryUuids.toBytes(fileId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("traceId", traceId);
        params.put("createdAt", SqlTimestamps.toDatabase(now));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 插入 PENDING 对话运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param conversationId 会话 ID
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void insertChatPending(
            TenantId tenantId, UUID runId, UUID conversationId, UUID userId, String traceId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(runId));
        params.put("conversationId", BinaryUuids.toBytes(conversationId));
        params.put("userId", BinaryUuids.toBytes(userId));
        params.put("traceId", traceId);
        params.put("createdAt", SqlTimestamps.toDatabase(now));
        tenantJdbc.insert(tenantId, INSERT_CHAT, params);
    }

    /**
     * 按租户读取运行快照。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行快照；不存在或属于其他租户时为空
     */
    public Optional<RunSnapshot> findChat(TenantId tenantId, UUID runId) {
        return tenantJdbc.queryOne(
                tenantId,
                FIND_CHAT,
                Map.of("runId", BinaryUuids.toBytes(runId)),
                (rs, rowNum) -> new RunSnapshot(
                        BinaryUuids.fromBytes(rs.getBytes("id")),
                        RunKind.valueOf(rs.getString("kind")),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getBytes("conversation_id") == null
                                ? null
                                : BinaryUuids.fromBytes(rs.getBytes("conversation_id")),
                        BinaryUuids.fromBytes(rs.getBytes("user_id")),
                        rs.getInt("attempt")));
    }

    /**
     * 把运行推进到 RUNNING。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数；Worker 已推进时为 0
     */
    public int markRunning(TenantId tenantId, UUID runId, Instant now) {
        return tenantJdbc.update(
                tenantId,
                MARK_RUNNING,
                Map.of("runId", BinaryUuids.toBytes(runId), "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 把运行推进到 SUCCEEDED。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markSucceeded(TenantId tenantId, UUID runId, Instant now) {
        return tenantJdbc.update(
                tenantId,
                MARK_SUCCEEDED,
                Map.of("runId", BinaryUuids.toBytes(runId), "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 把运行推进到 FAILED。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param errorCode 稳定错误码，最长 64
     * @param errorMessage 脱敏说明，最长 500
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markFailed(TenantId tenantId, UUID runId, String errorCode, String errorMessage, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", BinaryUuids.toBytes(runId));
        params.put("errorCode", truncate(errorCode, 64));
        params.put("errorMessage", truncate(errorMessage, 500));
        params.put("now", SqlTimestamps.toDatabase(now));
        return tenantJdbc.update(tenantId, MARK_FAILED, params);
    }

    /**
     * 把运行推进到 CANCELLED。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markCancelled(TenantId tenantId, UUID runId, Instant now) {
        return tenantJdbc.update(
                tenantId,
                MARK_CANCELLED,
                Map.of("runId", BinaryUuids.toBytes(runId), "now", SqlTimestamps.toDatabase(now)));
    }

    /**
     * 回填模型调用的 assistant 消息 ID。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @return 受影响行数；没有待回填调用时为 0
     */
    public int attachInvocationMessage(TenantId tenantId, UUID runId, UUID messageId) {
        return tenantJdbc.update(
                tenantId,
                ATTACH_INVOCATION_MESSAGE,
                Map.of(
                        "runId", BinaryUuids.toBytes(runId),
                        "messageId", BinaryUuids.toBytes(messageId)));
    }

    /**
     * 截断数据库列超长的文本，避免 MySQL 严格模式抛错掩盖原始失败。
     *
     * @param value 原文
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
