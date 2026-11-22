package com.riccon.api.admin.model.credential.persistence;

import com.riccon.api.admin.model.credential.domain.ModelCredentialRow;
import com.riccon.api.admin.model.credential.domain.PlatformDataKeyRecord;
import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.identity.domain.PlatformTenant;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.tenant.TenantId;
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
 * 平台托管模型凭据与 DEK 访问。
 *
 * <p>所有 SQL 都显式固定 {@code tenant_id='platform'}：M04 的模型 key 池是平台托管池，租户级遗留行既不可
 * 列出也不可按 ID 操作（ADR-0021 §6.1）。本类不返回密文、明文或 raw DEK，只返回展示/状态机字段；
 * key 明文只存在于 service 的短暂局部变量中。
 *
 * <p>凭据池查询按 {@code priority, created_at, id} 稳定排序，与 Worker 选择顺序一致；写入/更新必须经过
 * {@link PlatformJdbc} 的租户守卫，避免大小写或格式差异绕过 {@code tenant_id} 条件检查。
 */
@Component
public class ModelCredentialJdbc {

    /** 管理端响应需要的时间与状态字段；不包含 ciphertext/key_id。 */
    private static final String SELECT_COLUMNS = """
            id,
            provider_id,
            label,
            masked_hint,
            status,
            priority,
            version,
            consecutive_failures,
            created_at,
            updated_at,
            rotated_at,
            last_used_at,
            cooldown_until
            """;

    /** 单 provider 的 key 分页；排序与 Worker 选择顺序一致。 */
    private static final String SELECT_PAGE = """
            SELECT %s
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
             ORDER BY priority ASC, created_at ASC, id ASC
             LIMIT :limit OFFSET :offset
            """.formatted(SELECT_COLUMNS);

    /** 单 provider 的 key 总数。 */
    private static final String COUNT_BY_PROVIDER = """
            SELECT COUNT(*)
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
            """;

    /** 按 ID 读取，必须同时匹配 providerId，防止通过路径枚举其他 provider 的 key。 */
    private static final String SELECT_ONE = """
            SELECT %s
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
               AND id = :id
            """.formatted(SELECT_COLUMNS);

    /** fingerprint 去重查询；pepper 计算的 HMAC 只在服务层出现。 */
    private static final String COUNT_BY_FINGERPRINT = """
            SELECT COUNT(*)
              FROM provider_credential
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
               AND fingerprint = :fingerprint
            """;

    /** 新增平台 key；fingerprint 唯一键是并发去重的最终防线。 */
    private static final String INSERT_CREDENTIAL = """
            INSERT INTO provider_credential (
                tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id,
                version, status, label, fingerprint, masked_hint, priority, consecutive_failures,
                rotated_at, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :providerId, 'API_KEY', :ciphertext, :keyId, :masterKeyId,
                1, 'ACTIVE', :label, :fingerprint, :maskedHint, :priority, 0,
                :rotatedAt, :createdAt, :updatedAt
            )
            """;

    /** 停用：ACTIVE/INVALID → DISABLED；0 行表示已是停用态或已并发变更。 */
    private static final String DISABLE_CREDENTIAL = """
            UPDATE provider_credential
               SET status = 'DISABLED',
                   disabled_at = :now,
                   disabled_by = :actorId,
                   version = version + 1,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
               AND id = :id
               AND status IN ('ACTIVE', 'INVALID')
            """;

    /** 轮换时停用旧 key；要求版本未变化，保证并发轮换只有一个成功。 */
    private static final String DISABLE_FOR_ROTATION = """
            UPDATE provider_credential
               SET status = 'DISABLED',
                   disabled_at = :now,
                   disabled_by = :actorId,
                   rotated_at = :now,
                   version = version + 1,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
               AND id = :id
               AND version = :version
               AND status IN ('ACTIVE', 'INVALID')
            """;

    /** 恢复：DISABLED/INVALID → ACTIVE，并清空冷却、失效与停用标记。 */
    private static final String ENABLE_CREDENTIAL = """
            UPDATE provider_credential
               SET status = 'ACTIVE',
                   consecutive_failures = 0,
                   cooldown_until = NULL,
                   invalidated_at = NULL,
                   disabled_at = NULL,
                   disabled_by = NULL,
                   version = version + 1,
                   updated_at = :now
             WHERE tenant_id = :tenantId
               AND provider_id = :providerId
               AND credential_type = 'API_KEY'
               AND id = :id
               AND status IN ('DISABLED', 'INVALID')
            """;

    /** 读取平台模型凭据用途下最新的未退役 DEK。 */
    private static final String SELECT_ACTIVE_DATA_KEY = """
            SELECT key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
              FROM tenant_data_key
             WHERE tenant_id = :tenantId
               AND purpose = :purpose
               AND retired_at IS NULL
             ORDER BY created_at DESC, key_id DESC
             LIMIT 1
            """;

    /** 新增平台 DEK；幂等性由 (tenant_id, key_id) 主键兜底。 */
    private static final String INSERT_DATA_KEY = """
            INSERT INTO tenant_data_key (
                tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
            ) VALUES (
                :tenantId, :keyId, :purpose, :wrappedDek, :wrapNonce, :algorithm, :masterKeyId
            )
            """;

    /** 平台租户；所有凭据与 DEK 都固定使用该租户。 */
    private static final TenantId PLATFORM = PlatformTenant.TENANT_ID;

    /** 平台管理全局/租户表入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建凭据访问。
     *
     * @param platformJdbc 平台管理数据访问入口
     */
    public ModelCredentialJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
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
        Map<String, Object> params = Map.of(
                "providerId", BinaryUuids.toBytes(providerId),
                "limit", limit,
                "offset", offset);
        return platformJdbc.queryTenant(PLATFORM, SELECT_PAGE, params, ModelCredentialJdbc::mapRow);
    }

    /**
     * 统计某 provider 的平台 key 数量。
     *
     * @param providerId 供应商 ID
     * @return 行数
     */
    public long count(UUID providerId) {
        return platformJdbc.countTenant(
                PLATFORM, COUNT_BY_PROVIDER, Map.of("providerId", BinaryUuids.toBytes(providerId)));
    }

    /**
     * 按 provider + ID 读取平台 key。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @return 凭据行；跨 provider 或租户级行返回空
     */
    public Optional<ModelCredentialRow> findById(UUID providerId, UUID credentialId) {
        Map<String, Object> params = Map.of(
                "providerId", BinaryUuids.toBytes(providerId),
                "id", BinaryUuids.toBytes(credentialId));
        return platformJdbc.queryOneTenant(PLATFORM, SELECT_ONE, params, ModelCredentialJdbc::mapRow);
    }

    /**
     * 判断同一 provider 下是否已存在相同 fingerprint。
     *
     * @param providerId 供应商 ID
     * @param fingerprint HMAC 指纹
     * @return 已存在返回 {@code true}
     */
    public boolean fingerprintExists(UUID providerId, String fingerprint) {
        Map<String, Object> params = Map.of("providerId", BinaryUuids.toBytes(providerId), "fingerprint", fingerprint);
        return platformJdbc.countTenant(PLATFORM, COUNT_BY_FINGERPRINT, params) > 0;
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(credentialId));
        params.put("providerId", BinaryUuids.toBytes(providerId));
        params.put("ciphertext", ciphertext);
        params.put("keyId", keyId);
        params.put("masterKeyId", masterKeyId);
        params.put("fingerprint", fingerprint);
        params.put("maskedHint", maskedHint);
        params.put("label", label);
        params.put("priority", priority);
        params.put("rotatedAt", SqlTimestamps.toDatabase(rotatedAt));
        params.put("createdAt", SqlTimestamps.toDatabase(now));
        params.put("updatedAt", SqlTimestamps.toDatabase(now));
        platformJdbc.insertTenant(PLATFORM, INSERT_CREDENTIAL, params);
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
        return platformJdbc.updateTenant(
                PLATFORM, DISABLE_CREDENTIAL, stateParams(providerId, credentialId, actorId, now));
    }

    /**
     * 轮换时停用旧 key；版本条件用于串行化并发轮换。
     *
     * @param providerId 供应商 ID
     * @param credentialId 旧凭据 ID
     * @param expectedVersion 读取到的旧版本
     * @param actorId 操作者账号 ID
     * @param now 操作时间
     * @return 受影响行数
     */
    public int disableForRotation(UUID providerId, UUID credentialId, int expectedVersion, UUID actorId, Instant now) {
        Map<String, Object> params = stateParams(providerId, credentialId, actorId, now);
        params.put("version", expectedVersion);
        return platformJdbc.updateTenant(PLATFORM, DISABLE_FOR_ROTATION, params);
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerId", BinaryUuids.toBytes(providerId));
        params.put("id", BinaryUuids.toBytes(credentialId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return platformJdbc.updateTenant(PLATFORM, ENABLE_CREDENTIAL, params);
    }

    /**
     * 读取平台模型凭据用途下最新的未退役 DEK。
     *
     * @return DEK 记录；不存在时为空
     */
    public Optional<PlatformDataKeyRecord> findActiveDataKey() {
        return platformJdbc.queryOneTenant(
                PLATFORM,
                SELECT_ACTIVE_DATA_KEY,
                Map.of("purpose", CredentialEnvelope.PURPOSE_MODEL_CREDENTIAL),
                (rs, rowNum) -> new PlatformDataKeyRecord(
                        rs.getString("key_id"),
                        rs.getString("purpose"),
                        rs.getBytes("wrapped_dek"),
                        rs.getBytes("wrap_nonce"),
                        rs.getString("algorithm"),
                        rs.getString("master_key_id")));
    }

    /**
     * 新增平台 DEK。
     *
     * @param record DEK 记录
     */
    public void insertDataKey(PlatformDataKeyRecord record) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyId", record.keyId());
        params.put("purpose", record.purpose());
        params.put("wrappedDek", record.wrappedDek());
        params.put("wrapNonce", record.wrapNonce());
        params.put("algorithm", record.algorithm());
        params.put("masterKeyId", record.masterKeyId());
        platformJdbc.insertTenant(PLATFORM, INSERT_DATA_KEY, params);
    }

    /**
     * 组装状态变更参数；禁止调用方覆盖 tenantId（由 PlatformJdbc 绑定）。
     *
     * @param providerId 供应商 ID
     * @param credentialId 凭据 ID
     * @param actorId 操作者账号 ID
     * @param now 操作时间
     * @return 可变参数映射
     */
    private static Map<String, Object> stateParams(UUID providerId, UUID credentialId, UUID actorId, Instant now) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerId", BinaryUuids.toBytes(providerId));
        params.put("id", BinaryUuids.toBytes(credentialId));
        params.put("actorId", BinaryUuids.toBytes(actorId));
        params.put("now", SqlTimestamps.toDatabase(now));
        return params;
    }

    /**
     * 映射凭据行；时间按 UTC 读出，展示层再转换为 Asia/Shanghai 偏移。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 凭据行
     * @throws java.sql.SQLException 列读取失败
     */
    private static ModelCredentialRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelCredentialRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                BinaryUuids.fromBytes(rs.getBytes("provider_id")),
                rs.getString("label"),
                rs.getString("masked_hint"),
                rs.getString("status"),
                rs.getInt("priority"),
                rs.getInt("version"),
                rs.getInt("consecutive_failures"),
                timestamp(rs.getObject("created_at", LocalDateTime.class)),
                timestamp(rs.getObject("updated_at", LocalDateTime.class)),
                timestamp(rs.getObject("rotated_at", LocalDateTime.class)),
                timestamp(rs.getObject("last_used_at", LocalDateTime.class)),
                timestamp(rs.getObject("cooldown_until", LocalDateTime.class)));
    }

    /**
     * 将数据库 UTC 时间转换为 {@link Instant}。
     *
     * @param value 数据库值
     * @return UTC 时间或 {@code null}
     */
    private static Instant timestamp(LocalDateTime value) {
        return SqlTimestamps.toInstant(value);
    }
}
