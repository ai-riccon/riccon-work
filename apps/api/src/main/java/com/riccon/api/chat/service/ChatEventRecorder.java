package com.riccon.api.chat.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.api.chat.repository.MessageRepository;
import com.riccon.api.common.UuidV7;
import com.riccon.api.run.repository.RunRepository;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import com.riccon.platform.domain.chat.MessageStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话事件落库与停止审计。
 *
 * <p>每个方法只做一个短事务，避免在 SSE 读取线程长期持有数据库连接。状态更新使用条件 SQL：取消优先于迟到的
 * completed/failed，重复事件不会把终态回退。审计与停止状态在同一事务写入，审计失败使停止操作失败（ADR-0007）。
 */
@Service
public class ChatEventRecorder {

    /** 消息仓储。 */
    private final MessageRepository messageRepository;

    /** 运行仓储。 */
    private final RunRepository runRepository;

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建记录器。
     *
     * @param messageRepository 消息仓储
     * @param runRepository 运行仓储
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public ChatEventRecorder(
            MessageRepository messageRepository, RunRepository runRepository, AuditService auditService, Clock clock) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 记录 started：assistant 消息进入 STREAMING，运行进入 RUNNING，并回填模型调用关联。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param modelId 模型 ID
     */
    @Transactional
    public void recordStarted(TenantId tenantId, UUID runId, UUID messageId, UUID modelId) {
        Instant now = clock.instant();
        int updated = messageRepository.markStreaming(tenantId, messageId, modelId, now);
        if (updated == 0) {
            throw new IllegalStateException("assistant message is not in a streamable state");
        }
        runRepository.markRunning(tenantId, runId, now);
        // 内部契约不含 messageId，Worker 先留空；这里回填，失败仅表示没有待关联的调用记录。
        runRepository.attachInvocationMessage(tenantId, runId, messageId);
    }

    /**
     * 记录 delta；消息已终止时返回 {@code false}，调用方应结束转发。
     *
     * @param tenantId 租户
     * @param messageId assistant 消息 ID
     * @param delta 文本增量
     * @return 是否写入
     */
    @Transactional
    public boolean recordDelta(TenantId tenantId, UUID messageId, String delta) {
        return messageRepository.appendContent(tenantId, messageId, delta, clock.instant()) > 0;
    }

    /**
     * 记录 citation 快照。
     *
     * @param tenantId 租户
     * @param messageId assistant 消息 ID
     * @param citations 已校验的引用
     */
    @Transactional
    public void recordCitations(TenantId tenantId, UUID messageId, List<ChatEventValidator.ParsedCitation> citations) {
        Instant now = clock.instant();
        for (ChatEventValidator.ParsedCitation citation : citations) {
            CitationRecord record = new CitationRecord(
                    UuidV7.generate(now),
                    citation.ordinal(),
                    citation.sourceType(),
                    citation.fileId(),
                    citation.knowledgeDocumentId(),
                    citation.chunkId(),
                    citation.locatorType(),
                    citation.pageNo(),
                    citation.sheetName(),
                    citation.cellRange(),
                    citation.paragraphNo(),
                    citation.title(),
                    citation.snippet(),
                    citation.score(),
                    now);
            messageRepository.insertCitation(tenantId, messageId, record);
        }
    }

    /**
     * 记录 completed。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     */
    @Transactional
    public void recordCompleted(TenantId tenantId, UUID runId, UUID messageId) {
        Instant now = clock.instant();
        messageRepository.markTerminal(tenantId, messageId, MessageStatus.COMPLETED, null, now);
        runRepository.markSucceeded(tenantId, runId, now);
    }

    /**
     * 记录 failed，保留已生成内容。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param code 稳定错误码
     * @param message 脱敏说明
     */
    @Transactional
    public void recordFailed(TenantId tenantId, UUID runId, UUID messageId, String code, String message) {
        Instant now = clock.instant();
        String safeCode = truncate(code, 64);
        messageRepository.markTerminal(tenantId, messageId, MessageStatus.FAILED, safeCode, now);
        runRepository.markFailed(tenantId, runId, safeCode, message, now);
    }

    /**
     * 记录 stopped，保留已生成内容。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     */
    @Transactional
    public void recordStopped(TenantId tenantId, UUID runId, UUID messageId) {
        Instant now = clock.instant();
        messageRepository.markTerminal(tenantId, messageId, MessageStatus.STOPPED, null, now);
        runRepository.markCancelled(tenantId, runId, now);
    }

    /**
     * 首字前失败：assistant 消息与运行都进入失败终态。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param code 稳定错误码
     * @param message 脱敏说明
     */
    @Transactional
    public void recordPreFirstFailure(TenantId tenantId, UUID runId, UUID messageId, String code, String message) {
        recordFailed(tenantId, runId, messageId, code, message);
    }

    /**
     * 记录员工停止并写审计；消息与运行在同一事务进入取消终态。
     *
     * @param tenantId 租户
     * @param userId 操作者
     * @param conversationId 会话 ID
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID；可为空（理论上总是存在）
     * @param traceId 追踪 ID
     * @param requestId 请求 ID
     */
    @Transactional
    public void recordUserStop(
            TenantId tenantId,
            UUID userId,
            UUID conversationId,
            UUID runId,
            UUID messageId,
            String traceId,
            String requestId) {
        Instant now = clock.instant();
        if (messageId != null) {
            messageRepository.markTerminal(tenantId, messageId, MessageStatus.STOPPED, null, now);
        } else {
            messageRepository.markTerminalByRun(tenantId, runId, MessageStatus.STOPPED, null, now);
        }
        runRepository.markCancelled(tenantId, runId, now);
        Map<String, Object> details = new LinkedHashMap<>();
        if (messageId != null) {
            details.put("messageId", messageId.toString());
        }
        details.put("runId", runId.toString());
        details.put("reason", "USER_CANCELLED");
        auditService.append(AuditEvent.create(
                now,
                tenantId.value(),
                AuditActorType.USER,
                userId.toString(),
                AdditionalAuditActions.MESSAGE_STOPPED,
                AuditTargetType.CONVERSATION,
                conversationId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                requestId,
                null,
                details));
    }

    /**
     * 客户端断开：保留已生成内容并写停止审计；审计失败由调用方记录日志，不再向已断开的连接写事件。
     *
     * @param tenantId 租户
     * @param userId 发起人
     * @param conversationId 会话 ID
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param traceId 追踪 ID
     */
    @Transactional
    public void recordClientDisconnect(
            TenantId tenantId, UUID userId, UUID conversationId, UUID runId, UUID messageId, String traceId) {
        Instant now = clock.instant();
        messageRepository.markTerminal(tenantId, messageId, MessageStatus.STOPPED, null, now);
        runRepository.markCancelled(tenantId, runId, now);
        auditService.append(AuditEvent.create(
                now,
                tenantId.value(),
                AuditActorType.USER,
                userId.toString(),
                AdditionalAuditActions.MESSAGE_STOPPED,
                AuditTargetType.CONVERSATION,
                conversationId.toString(),
                AuditOutcome.SUCCESS,
                traceId,
                null,
                null,
                Map.of(
                        "messageId", messageId.toString(),
                        "runId", runId.toString(),
                        "reason", "CLIENT_DISCONNECT")));
    }

    /**
     * 截断错误码到列长度。
     *
     * @param value 原文
     * @param maxLength 最大长度
     * @return 截断值
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
