package com.riccon.api.admin.model.credential.repository;

import com.riccon.api.admin.model.credential.domain.ModelCredentialRow;
import com.riccon.api.admin.model.credential.domain.PlatformDataKeyRecord;
import com.riccon.api.admin.model.credential.persistence.ModelCredentialJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台托管模型凭据仓储。
 *
 * <p>只暴露平台 key 池的读写语义；查询与更新都由持久层固定 {@code tenant_id='platform'}，不提供跨租户
 * 或任意租户参数（ADR-0021 §6.1）。状态更新返回受影响行数，供 service 判断幂等与并发冲突。
 */
@Repository
public class ModelCredentialRepository {

    /** 凭据持久化。 */
    private final ModelCredentialJdbc credentialJdbc;

    /**
     * 创建凭据仓储。
     *
     * @param credentialJdbc 凭据持久化
     */
    public ModelCredentialRepository(ModelCredentialJdbc credentialJdbc) {
        this.credentialJdbc = Objects.requireNonNull(credentialJdbc, "credentialJdbc must not be null");
    }

    /**
     * 分页读取某 provider 的平台 key。
     *
     * @param providerId 供应商 ID
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 凭据行
     */
    public List<ModelCredentialRow> findPage(UUID providerId, int limit, int offset) {
        return credentialJdbc.findPage(providerId, limit, offset);
    }

    /**
     * 统计某 provider 的平台 key 数量。
     *
     * @param providerId 供应商 ID
     * @return 行数
     */
    public long count(UUID providerId) {
        return credentialJdbc.count(providerId);
    }

    /**
     * 按 provider + ID 读取平台 key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @return 凭据行
     */
    public Optional<ModelCredentialRow> findById(UUID providerId, UUID credentialId) {
        return credentialJdbc.findById(providerId, credentialId);
    }

    /**
     * 判断同一 provider 下是否已存在相同 fingerprint。
     *
     * @param providerId 供应商 ID
     * @param fingerprint HMAC 指纹
     * @return 已存在返回 {@code true}
     */
    public boolean fingerprintExists(UUID providerId, String fingerprint) {
        return credentialJdbc.fingerprintExists(providerId, fingerprint);
    }

    /**
     * 新增平台 key。
     *
     * @param credentialId 凭据 ID
     * @param providerId 供应商 ID
     * @param ciphertext 信封密文
     * @param keyId DEK 标识
     * @param masterKeyId 主密钥标识
     * @param fingerprint HMAC 指纹
     * @param maskedHint 末 4 位掩码
     * @param label 标签
     * @param priority 优先级
     * @param now 创建/更新时间
     * @param rotatedAt 轮换时间；直接新增为 {@code null}
     */
    public void insert(
            UUID credentialId,
            UUID providerId,
            String ciphertext,
            String keyId,
            String masterKeyId,
            String fingerprint,
            String maskedHint,
            String label,
            int priority,
            Instant now,
            Instant rotatedAt) {
        credentialJdbc.insert(
                credentialId,
                providerId,
                ciphertext,
                keyId,
                masterKeyId,
                fingerprint,
                maskedHint,
                label,
                priority,
                now,
                rotatedAt);
    }

    /**
     * 停用平台 key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param actorId 操作者账号 ID
     * @param now 操作时间
     * @return 受影响行数
     */
    public int disable(UUID providerId, UUID credentialId, UUID actorId, Instant now) {
        return credentialJdbc.disable(providerId, credentialId, actorId, now);
    }

    /**
     * 轮换时停用旧 key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 旧凭据 ID
     * @param expectedVersion 读取到的旧版本
     * @param actorId 操作者账号 ID
     * @param now 操作时间
     * @return 受影响行数
     */
    public int disableForRotation(UUID providerId, UUID credentialId, int expectedVersion, UUID actorId, Instant now) {
        return credentialJdbc.disableForRotation(providerId, credentialId, expectedVersion, actorId, now);
    }

    /**
     * 恢复平台 key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param now 操作时间
     * @return 受影响行数
     */
    public int enable(UUID providerId, UUID credentialId, Instant now) {
        return credentialJdbc.enable(providerId, credentialId, now);
    }

    /**
     * 读取平台模型凭据用途下最新的未退役 DEK。
     *
     * @return DEK 记录
     */
    public Optional<PlatformDataKeyRecord> findActiveDataKey() {
        return credentialJdbc.findActiveDataKey();
    }

    /**
     * 新增平台 DEK。
     *
     * @param record DEK 记录
     */
    public void insertDataKey(PlatformDataKeyRecord record) {
        credentialJdbc.insertDataKey(record);
    }
}
