package com.riccon.worker.model.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型凭据与数据密钥只读访问。
 *
 * <p>凭据与 DEK 都是租户数据，必须通过 {@link TenantJdbc} 读取；平台级凭据使用
 * {@code platform} 租户作用域，调用方负责在解析部署级凭据时显式打开该作用域。
 * 本类不返回明文，只返回密文与 key 引用。
 */
@Component
public class CredentialJdbc {

    /** 凭据查询；同 provider/type 只允许一条 ACTIVE。 */
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

    /** DEK 查询；key_id 与 purpose 必须匹配。 */
    private static final String SELECT_DATA_KEY = """
            SELECT tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
              FROM tenant_data_key
             WHERE tenant_id = :tenantId
               AND key_id = :keyId
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
     * 读取指定租户的 ACTIVE 凭据。
     *
     * @param tenantId 凭据租户（tenant 或 platform）
     * @param providerId 供应商 ID
     * @param credentialType 凭据类型，M02 固定 API_KEY
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
}
