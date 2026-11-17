package com.riccon.worker.research.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.research.persistence.ApprovalParamsKeyJdbc;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 审批参数数据密钥仓储。
 *
 * <p>服务层只依赖本仓储；SQL 与 {@code TenantJdbc} 保留在 persistence 层，保持
 * controller → service → repository → persistence 单向依赖。
 */
@Component
public class ApprovalParamsKeyRepository {

    /** JDBC 访问。 */
    private final ApprovalParamsKeyJdbc keyJdbc;

    /**
     * 创建仓储。
     *
     * @param keyJdbc JDBC 访问
     */
    public ApprovalParamsKeyRepository(ApprovalParamsKeyJdbc keyJdbc) {
        this.keyJdbc = Objects.requireNonNull(keyJdbc, "keyJdbc must not be null");
    }

    /**
     * 读取租户当前可用的审批参数 DEK。
     *
     * @param tenantId 租户
     * @return 最新未退役 DEK；不存在时为空
     */
    public Optional<TenantDataKeyRecord> findActive(TenantId tenantId) {
        return keyJdbc.findActive(tenantId);
    }

    /**
     * 按 key id 读取租户 DEK。
     *
     * @param tenantId 租户
     * @param keyId 密钥标识
     * @return DEK；不存在时为空
     */
    public Optional<TenantDataKeyRecord> find(TenantId tenantId, String keyId) {
        return keyJdbc.find(tenantId, keyId);
    }

    /**
     * 写入新的审批参数 DEK。
     *
     * @param tenantId 租户
     * @param record DEK 记录
     */
    public void insert(TenantId tenantId, TenantDataKeyRecord record) {
        keyJdbc.insert(tenantId, record);
    }
}
