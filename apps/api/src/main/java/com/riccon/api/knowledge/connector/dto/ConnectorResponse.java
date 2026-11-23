package com.riccon.api.knowledge.connector.dto;

import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.domain.ConnectorSyncSummary;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * 连接器响应（OpenAPI {@code Connector}）。
 *
 * <p>只返回配置、状态与掩码；永不返回凭据明文、密文、DEK、端点 URL 或对象 key。
 *
 * @param id 连接器 ID
 * @param name 名称
 * @param type 类型
 * @param status 状态
 * @param endpointId 允许列表端点 ID
 * @param config 配置
 * @param defaultVisibility 默认可见范围
 * @param defaultDepartmentId 默认部门
 * @param credentialMaskedHint 凭据掩码
 * @param scheduleEnabled 是否参与定时同步
 * @param nextSyncAt 下一次定时同步
 * @param lastSyncStartedAt 最近同步开始
 * @param lastSyncFinishedAt 最近同步结束
 * @param lastSyncStatus 最近同步结果
 * @param lastSyncComplete list 是否完整
 * @param lastSuccessAt 最近成功时间
 * @param lastErrorCode 最近失败码
 * @param lastSyncSummary 最近同步计数
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ConnectorResponse(
        UUID id,
        String name,
        ConnectorType type,
        ConnectorStatus status,
        String endpointId,
        ConnectorConfig config,
        KnowledgeVisibility defaultVisibility,
        UUID defaultDepartmentId,
        String credentialMaskedHint,
        boolean scheduleEnabled,
        Instant nextSyncAt,
        Instant lastSyncStartedAt,
        Instant lastSyncFinishedAt,
        ConnectorSyncStatus lastSyncStatus,
        boolean lastSyncComplete,
        Instant lastSuccessAt,
        String lastErrorCode,
        ConnectorSyncSummary lastSyncSummary,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 映射连接器响应。
     *
     * @param connector 连接器快照
     * @return 响应
     */
    public static ConnectorResponse from(ConnectorRecord connector) {
        return new ConnectorResponse(
                connector.id(),
                connector.name(),
                connector.type(),
                connector.status(),
                connector.endpointId(),
                connector.config(),
                connector.defaultVisibility(),
                connector.defaultDepartmentId(),
                connector.credentialMaskedHint(),
                connector.scheduleEnabled(),
                connector.nextSyncAt(),
                connector.lastSyncStartedAt(),
                connector.lastSyncFinishedAt(),
                connector.lastSyncStatus(),
                connector.lastSyncComplete(),
                connector.lastSuccessAt(),
                connector.lastErrorCode(),
                connector.lastSyncSummary(),
                connector.createdBy(),
                connector.createdAt(),
                connector.updatedAt());
    }
}
