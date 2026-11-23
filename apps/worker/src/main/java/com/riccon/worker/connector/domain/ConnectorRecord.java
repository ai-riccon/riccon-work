package com.riccon.worker.connector.domain;

import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * Worker 侧连接器快照。
 *
 * <p>包含解密所需的密文与 keyId；只在同步服务内部使用，日志不得输出。配置、可见范围与创建人用于
 * 生成导入文档、ACL 与索引事件。
 *
 * @param id 连接器 ID
 * @param name 名称
 * @param type 类型
 * @param status 状态
 * @param endpointId 允许列表 ID
 * @param config 配置
 * @param defaultVisibility 导入文档默认可见范围
 * @param defaultDepartmentId 默认部门
 * @param credentialCiphertext 凭据密文
 * @param credentialKeyId 凭据 DEK 标识
 * @param scheduleEnabled 是否参与定时同步
 * @param nextSyncAt 下一次定时同步
 * @param lastSyncStatus 最近同步结果
 * @param createdBy 创建人
 * @param createdAt 创建时间
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
        boolean scheduleEnabled,
        Instant nextSyncAt,
        ConnectorSyncStatus lastSyncStatus,
        UUID createdBy,
        Instant createdAt,
        Instant deletedAt) {

    /** 防御性复制凭据密文。 */
    public ConnectorRecord {
        credentialCiphertext = credentialCiphertext == null ? null : credentialCiphertext.clone();
    }

    /**
     * 判断是否已配置凭据。
     *
     * @return 密文与 keyId 都存在时返回 true
     */
    public boolean hasCredential() {
        return credentialCiphertext != null && credentialCiphertext.length > 0 && credentialKeyId != null;
    }
}
