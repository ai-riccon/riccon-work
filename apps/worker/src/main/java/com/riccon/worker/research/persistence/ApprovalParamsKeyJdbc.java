package com.riccon.worker.research.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.persistence.TenantJdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 审批参数数据密钥访问。
 *
 * <p>完整工具参数使用租户 DEK（{@code purpose = approval-params}）加密后写入
 * {@code approval.params_ciphertext}。DEK 由主密钥包裹并保存在 {@code tenant_data_key}；
 * 本类只读写包裹后的密文与 key 引用，不保存、不返回明文 DEK。
 *
 * <p>M02 没有独立的密钥管理 API，Worker 在首次创建审批时按租户懒生成 DEK；生成与写入都在
 * 当前租户作用域内完成，并通过主键 {@code (tenant_id, key_id)} 防止重复 key id。
 */
@Component
public class ApprovalParamsKeyJdbc {

    /** 审批参数 DEK 用途；与 ADR-0017、V8 迁移注释一致。 */
    public static final String PURPOSE = "approval-params";

    /** 租户数据访问强制点。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建访问器。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public ApprovalParamsKeyJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 读取租户当前可用的审批参数 DEK。
     *
     * @param tenantId 租户
     * @return 最新的未退役 DEK；不存在时为空
     */
    public Optional<TenantDataKeyRecord> findActive(TenantId tenantId) {
        return tenantJdbc.queryOne(tenantId, """
                SELECT tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                  FROM tenant_data_key
                 WHERE tenant_id = :tenantId
                   AND purpose = :purpose
                   AND retired_at IS NULL
                 ORDER BY created_at DESC, key_id DESC
                 LIMIT 1
                """, Map.of("purpose", PURPOSE), ApprovalParamsKeyJdbc::map);
    }

    /**
     * 按 key id 读取租户 DEK；跨租户或 key 不存在时为空。
     *
     * @param tenantId 租户
     * @param keyId 密钥标识
     * @return DEK 记录；不存在时为空
     */
    public Optional<TenantDataKeyRecord> find(TenantId tenantId, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return tenantJdbc.queryOne(tenantId, """
                SELECT tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                  FROM tenant_data_key
                 WHERE tenant_id = :tenantId
                   AND key_id = :keyId
                """, Map.of("keyId", keyId), ApprovalParamsKeyJdbc::map);
    }

    /**
     * 写入新的审批参数 DEK。
     *
     * @param tenantId 租户
     * @param record DEK 记录
     */
    public void insert(TenantId tenantId, TenantDataKeyRecord record) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyId", record.keyId());
        params.put("purpose", record.purpose());
        params.put("wrappedDek", record.wrappedDek());
        params.put("wrapNonce", record.wrapNonce());
        params.put("algorithm", record.algorithm());
        params.put("masterKeyId", record.masterKeyId());
        tenantJdbc.insert(tenantId, """
                INSERT INTO tenant_data_key
                    (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                VALUES
                    (:tenantId, :keyId, :purpose, :wrappedDek, :wrapNonce, :algorithm, :masterKeyId)
                """, params);
    }

    /**
     * 映射数据密钥行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 记录
     * @throws SQLException 列读取失败
     */
    private static TenantDataKeyRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TenantDataKeyRecord(
                rs.getString("tenant_id"),
                rs.getString("key_id"),
                rs.getString("purpose"),
                rs.getBytes("wrapped_dek"),
                rs.getBytes("wrap_nonce"),
                rs.getString("algorithm"),
                rs.getString("master_key_id"));
    }
}
