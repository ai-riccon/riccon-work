package com.riccon.worker.model.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 平台托管 key 池访问。
 *
 * <p>`provider_credential` 是租户表，M04 只读写 `tenant_id='platform'`；查询/更新必须带
 * `tenant_id = :tenantId`，由 {@link TenantJdbc} 强制当前线程租户与平台租户一致。
 * 键的排序固定为 `priority, created_at, id`，不做随机或加权（ADR-0021 §6.2）。
 */
@Component
public class CredentialJdbc {

    /** M02 兼容的单凭据读取；只用于读取平台池第一把可用 key。 */
    private static final String SELECT_CREDENTIAL = """
            SELECT id, tenant_id, provider_id, credential_type, ciphertext, key_id, master_key_id, version
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = :credentialType
               AND status = 'ACTIVE'
             ORDER BY version DESC
             LIMIT 1
            """;

    /** 平台 key 池：ACTIVE 且不在冷却期，按 priority/created_at/id 稳定排序。 */
    private static final String SELECT_POOL = """
            SELECT id, provider_id, ciphertext, key_id, master_key_id, version, masked_hint, priority
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = :credentialType
               AND status = 'ACTIVE'
               AND (cooldown_until IS NULL OR cooldown_until <= NOW(6))
             ORDER BY priority, created_at, id
            """;

    /** DEK 查询；key_id 与 purpose 必须匹配。 */
    private static final String SELECT_DATA_KEY = """
            SELECT tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
              FROM tenant_data_key
             WHERE tenant_id = :tenantId
               AND key_id = :keyId
            """;

    /**
     * 可重试失败：次数 +1，连续 3 次进入冷却。
     *
     * <p>MySQL 的 SET 赋值从左到右求值且后面的表达式会看到已更新值；必须先算 cooldown
     * （使用旧计数 + 1），再自增，否则第 2 次失败就会误触发 30 秒冷却。
     */
    private static final String UPDATE_FAILURE = """
            UPDATE provider_credential
               SET cooldown_until = CASE
                       WHEN consecutive_failures + 1 >= 3 THEN :cooldownUntil
                       ELSE cooldown_until
                   END,
                   consecutive_failures = consecutive_failures + 1,
                   updated_at = NOW(6)
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'ACTIVE'
            """;

    /** 限流：只写冷却截止，不改状态与失败计数。 */
    private static final String UPDATE_RATE_LIMITED = """
            UPDATE provider_credential
               SET cooldown_until = GREATEST(COALESCE(cooldown_until, NOW(6)), :cooldownUntil),
                   updated_at = NOW(6)
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'ACTIVE'
            """;

    /** 401/403：条件失效，已停用/已失效的行不重复改写。 */
    private static final String UPDATE_INVALID = """
            UPDATE provider_credential
               SET status = 'INVALID',
                   invalidated_at = NOW(6),
                   updated_at = NOW(6)
             WHERE tenant_id = :tenantId
               AND id = :id
               AND status = 'ACTIVE'
            """;

    /** 读取连续失败次数；0 表示没有记录或已恢复。 */
    private static final String SELECT_FAILURES = """
            SELECT consecutive_failures
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND id = :id
            """;

    /**
     * 成功使用：重置连续失败计数与冷却，并记录最近成功时间。
     *
     * <p>`consecutive_failures` 是“连续可重试失败次数”（ADR-0021 §3.2），成功一次即归零，
     * 否则历史累计会让下一次失败误触发冷却。
     */
    private static final String UPDATE_LAST_USED = """
            UPDATE provider_credential
               SET last_used_at = NOW(6),
                   consecutive_failures = 0,
                   cooldown_until = NULL
             WHERE tenant_id = :tenantId
               AND id = :id
            """;

    /** 租户数据访问强制点。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建凭据访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public CredentialJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取指定租户的 ACTIVE 凭据（M02 兼容入口）。
     *
     * @param tenantId 凭据租户（M04 只传 platform）
     * @param providerId 供应商 ID
     * @param credentialType 凭据类型，M04 固定 API_KEY
     * @return 凭据记录；不存在时为空
     */
    public Optional<CredentialRecord> findActive(TenantId tenantId, UUID providerId, String credentialType) {
        return tenantJdbc.queryOne(
                tenantId,
                SELECT_CREDENTIAL,
                Map.of("providerId", UuidBinary.toBytes(providerId), "credentialType", credentialType),
                (rs, rowNum) -> new CredentialRecord(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        rs.getString("tenant_id"),
                        UuidBinary.fromBytes(rs.getBytes("provider_id")),
                        rs.getString("credential_type"),
                        rs.getString("ciphertext"),
                        rs.getString("key_id"),
                        rs.getString("master_key_id"),
                        rs.getInt("version")));
    }

    /**
     * 读取平台 key 池中指定 provider 的可用 key。
     *
     * @param tenantId 凭据租户（M04 只传 platform）
     * @param providerId 供应商 ID
     * @param credentialType 凭据类型，M04 固定 API_KEY
     * @return 按 priority/created_at/id 排序的可用 key；无可用 key 时为空列表
     */
    public List<ModelCredential> findAvailablePool(TenantId tenantId, UUID providerId, String credentialType) {
        return tenantJdbc.query(
                tenantId,
                SELECT_POOL,
                Map.of("providerId", UuidBinary.toBytes(providerId), "credentialType", credentialType),
                (rs, rowNum) -> new ModelCredential(
                        UuidBinary.fromBytes(rs.getBytes("id")),
                        UuidBinary.fromBytes(rs.getBytes("provider_id")),
                        rs.getString("ciphertext"),
                        rs.getString("key_id"),
                        rs.getString("master_key_id"),
                        rs.getInt("version"),
                        rs.getString("masked_hint"),
                        rs.getInt("priority")));
    }

    /**
     * 读取凭据引用的 DEK。
     *
     * @param tenantId 凭据租户
     * @param keyId DEK 标识
     * @return DEK 记录；不存在时为空
     */
    public Optional<TenantDataKeyRecord> findDataKey(TenantId tenantId, String keyId) {
        return tenantJdbc.queryOne(
                tenantId,
                SELECT_DATA_KEY,
                Map.of("keyId", keyId),
                (rs, rowNum) -> new TenantDataKeyRecord(
                        rs.getString("tenant_id"),
                        rs.getString("key_id"),
                        rs.getString("purpose"),
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce"),
                        rs.getString("algorithm"),
                        rs.getString("master_key_id")));
    }

    /**
     * 记录一次可重试失败；连续 3 次时写入冷却截止。
     *
     * @param tenantId 凭据租户（platform）
     * @param credentialId 凭据 ID
     * @param cooldownUntil 冷却截止（UTC）；仅在达到阈值时使用
     * @return 受影响行数
     */
    public int recordFailure(TenantId tenantId, UUID credentialId, LocalDateTime cooldownUntil) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(credentialId));
        params.put("cooldownUntil", cooldownUntil);
        return tenantJdbc.update(tenantId, UPDATE_FAILURE, params);
    }

    /**
     * 记录一次 429：只写冷却截止，不改状态。
     *
     * @param tenantId 凭据租户（platform）
     * @param credentialId 凭据 ID
     * @param cooldownUntil 冷却截止（UTC）
     * @return 受影响行数
     */
    public int recordRateLimited(TenantId tenantId, UUID credentialId, LocalDateTime cooldownUntil) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UuidBinary.toBytes(credentialId));
        params.put("cooldownUntil", cooldownUntil);
        return tenantJdbc.update(tenantId, UPDATE_RATE_LIMITED, params);
    }

    /**
     * 条件失效 401/403 的 key。
     *
     * @param tenantId 凭据租户（platform）
     * @param credentialId 凭据 ID
     * @return 受影响行数；0 表示已不是 ACTIVE
     */
    public int markInvalid(TenantId tenantId, UUID credentialId) {
        return tenantJdbc.update(tenantId, UPDATE_INVALID, Map.of("id", UuidBinary.toBytes(credentialId)));
    }

    /**
     * 读取 key 的连续失败次数，用于判断是否达到冷却阈值。
     *
     * @param tenantId 凭据租户（platform）
     * @param credentialId 凭据 ID
     * @return 连续失败次数；行不存在时为空
     */
    public Optional<Integer> currentFailures(TenantId tenantId, UUID credentialId) {
        return tenantJdbc.queryOne(
                tenantId,
                SELECT_FAILURES,
                Map.of("id", UuidBinary.toBytes(credentialId)),
                (rs, rowNum) -> rs.getInt("consecutive_failures"));
    }

    /**
     * 记录成功使用时间。
     *
     * @param tenantId 凭据租户（platform）
     * @param credentialId 凭据 ID
     * @return 受影响行数
     */
    public int markUsed(TenantId tenantId, UUID credentialId) {
        return tenantJdbc.update(tenantId, UPDATE_LAST_USED, Map.of("id", UuidBinary.toBytes(credentialId)));
    }
}
