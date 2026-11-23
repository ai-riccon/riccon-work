package com.riccon.api.knowledge.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * 连接器行快照。
 *
 * <p>{@code credentialCiphertext} 只在服务层内部用于判断是否已配置凭据；响应与日志永不输出。
 *
 * @param id 连接器 ID
 * @param name 名称
 * @param type 类型
 * @param status 状态
 * @param endpointId 部署允许列表 ID
 * @param config 配置
 * @param defaultVisibility 导入文档默认可见范围
 * @param defaultDepartmentId 默认部门；仅 DEPT
 * @param credentialCiphertext 凭据密文；仅内部使用
 * @param credentialKeyId 凭据引用 DEK 标识
 * @param credentialMaskedHint 凭据掩码
 * @param scheduleEnabled 是否参与部署固定周期同步
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
 * @param deletedAt 软删除时间
 */
public record ConnectorRecord(
        UUID id,
        String name,
        ConnectorType type,
        ConnectorStatus status,
        String endpointId,
        ConnectorConfig config,
        KnowledgeVisibility defaultVisibility,
        UUID defaultDepartmentId,
        byte[] credentialCiphertext,
        String credentialKeyId,
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
        Instant updatedAt,
        Instant deletedAt) {

    /**
     * 防御性复制凭据密文，避免调用方修改后影响解密。
     */
    public ConnectorRecord {
        credentialCiphertext = credentialCiphertext == null ? null : credentialCiphertext.clone();
    }

    /**
     * 判断是否已配置凭据。
     *
     * @return 密文存在且非空时返回 true
     */
    public boolean hasCredential() {
        return credentialCiphertext != null && credentialCiphertext.length > 0 && credentialKeyId != null;
    }
}
