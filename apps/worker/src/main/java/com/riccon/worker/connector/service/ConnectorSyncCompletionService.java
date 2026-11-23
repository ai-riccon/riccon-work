package com.riccon.worker.connector.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import com.riccon.worker.connector.repository.ConnectorRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器同步终态收尾。
 *
 * <p>把 {@code finishSync}（last_sync_* / status / summary / next_sync_at）与
 * {@code knowledge.connector.sync.completed|failed}、必要的 {@code status.changed} 审计放进同一个短事务：
 * 任何审计写入失败都会回滚终态，避免“终态已落库但审计缺失”的假成功（ADR-0023 第 6.6 节、TM-64）。
 *
 * <p>本类不包含长同步、S3 下载或重试投递；调用方只在同步收尾时调用一次短事务。
 */
@Service
public class ConnectorSyncCompletionService {

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 审计写入。 */
    private final ConnectorAuditWriter auditWriter;

    /**
     * 创建终态收尾服务。
     *
     * @param connectorRepository 仓储
     * @param auditWriter 审计写入
     */
    public ConnectorSyncCompletionService(ConnectorRepository connectorRepository, ConnectorAuditWriter auditWriter) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * 在单事务内写同步终态与完成/失败审计。
     *
     * @param tenantId 租户
     * @param connector 同步前连接器快照
     * @param syncId 同步 ID
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param status 同步状态
     * @param complete list 是否完整
     * @param errorCode 失败码；成功为空
     * @param summary 计数
     * @param connectorStatus 目标连接器状态
     * @param nextSyncAt 下一次定时同步
     * @param durationMs 耗时
     * @param retryable 是否可重试
     * @param traceId 追踪 ID
     */
    @Transactional
    public void complete(
            TenantId tenantId,
            ConnectorRecord connector,
            UUID syncId,
            Instant startedAt,
            Instant finishedAt,
            ConnectorSyncStatus status,
            boolean complete,
            String errorCode,
            ConnectorSyncSummary summary,
            ConnectorStatus connectorStatus,
            Instant nextSyncAt,
            long durationMs,
            boolean retryable,
            String traceId) {
        connectorRepository.finishSync(
                tenantId,
                connector.id(),
                startedAt,
                finishedAt,
                status,
                complete,
                errorCode,
                summary,
                connectorStatus,
                nextSyncAt);
        if (status == ConnectorSyncStatus.ERROR) {
            auditWriter.syncFailed(
                    tenantId, connector.id(), syncId, errorCode, retryable, complete, durationMs, traceId);
        } else {
            auditWriter.syncCompleted(tenantId, connector.id(), syncId, summary, durationMs, traceId);
        }
        if (connectorStatus != connector.status()) {
            auditWriter.statusChanged(tenantId, connector, connector.status(), connectorStatus, "SYNC_RESULT", traceId);
        }
    }
}
