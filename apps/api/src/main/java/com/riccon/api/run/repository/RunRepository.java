package com.riccon.api.run.repository;

import com.riccon.api.run.domain.RunSnapshot;
import com.riccon.api.run.persistence.RunJdbc;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.research.RunKind;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Agent 运行仓储。
 *
 * <p>API 创建 PENDING 运行并在对话流事件/取消路径做条件状态推进；Worker 仍是主要执行者，
 * 所有状态迁移都带租户条件，重复事件不会把终态回退。
 */
@Repository
public class RunRepository {

    /** 运行 SQL。 */
    private final RunJdbc runJdbc;

    /**
     * 创建仓储。
     *
     * @param runJdbc 运行 SQL
     */
    public RunRepository(RunJdbc runJdbc) {
        this.runJdbc = Objects.requireNonNull(runJdbc, "runJdbc must not be null");
    }

    /**
     * 创建 PENDING 运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param kind 运行类别
     * @param fileId 关联文件；知识库索引为 {@code null}
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void createPending(
            TenantId tenantId, UUID runId, RunKind kind, UUID fileId, UUID userId, String traceId, Instant now) {
        runJdbc.insertPending(tenantId, runId, kind, fileId, userId, traceId, now);
    }

    /**
     * 创建 PENDING 对话运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param conversationId 会话 ID
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void createChatPending(
            TenantId tenantId, UUID runId, UUID conversationId, UUID userId, String traceId, Instant now) {
        runJdbc.insertChatPending(tenantId, runId, conversationId, userId, traceId, now);
    }

    /**
     * 按租户读取运行快照。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行快照
     */
    public Optional<RunSnapshot> findChat(TenantId tenantId, UUID runId) {
        return runJdbc.findChat(tenantId, runId);
    }

    /**
     * 创建 PENDING 报告任务运行。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param researchTaskId 报告任务 ID
     * @param userId 发起人
     * @param traceId 追踪 ID
     * @param now 创建时间
     */
    public void createResearchPending(
            TenantId tenantId, UUID runId, UUID researchTaskId, UUID userId, String traceId, Instant now) {
        runJdbc.insertResearchPending(tenantId, runId, researchTaskId, userId, traceId, now);
    }

    /**
     * 按租户读取任意类别运行快照。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @return 运行快照
     */
    public Optional<RunSnapshot> findRun(TenantId tenantId, UUID runId) {
        return runJdbc.findRun(tenantId, runId);
    }

    /**
     * 把等待审批的运行恢复为 RUNNING。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markResumed(TenantId tenantId, UUID runId, Instant now) {
        return runJdbc.markResumed(tenantId, runId, now);
    }

    /**
     * 把运行推进到 RUNNING。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markRunning(TenantId tenantId, UUID runId, Instant now) {
        return runJdbc.markRunning(tenantId, runId, now);
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
        return runJdbc.markSucceeded(tenantId, runId, now);
    }

    /**
     * 把运行推进到 FAILED。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏说明
     * @param now 当前时间
     * @return 受影响行数
     */
    public int markFailed(TenantId tenantId, UUID runId, String errorCode, String errorMessage, Instant now) {
        return runJdbc.markFailed(tenantId, runId, errorCode, errorMessage, now);
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
        return runJdbc.markCancelled(tenantId, runId, now);
    }

    /**
     * 回填模型调用的 assistant 消息 ID。
     *
     * @param tenantId 当前租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @return 受影响行数
     */
    public int attachInvocationMessage(TenantId tenantId, UUID runId, UUID messageId) {
        return runJdbc.attachInvocationMessage(tenantId, runId, messageId);
    }
}
