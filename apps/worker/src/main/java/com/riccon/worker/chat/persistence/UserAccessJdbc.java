package com.riccon.worker.chat.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 运行发起人状态访问。
 *
 * <p>Worker 在每次执行前必须校验用户仍启用（ADR-0008 第 3 节）；只读取状态，不读取 PII。
 */
@Component
public class UserAccessJdbc {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建用户状态访问。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public UserAccessJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 判断用户是否为 ACTIVE。
     *
     * @param tenantId 租户
     * @param userId 用户 ID
     * @return 是否启用
     */
    public boolean isActive(TenantId tenantId, UUID userId) {
        return tenantJdbc
                .queryOne(
                        tenantId,
                        """
                        SELECT status
                          FROM app_user
                         WHERE tenant_id = :tenantId
                           AND id = :userId
                        """,
                        Map.of("userId", UuidBinary.toBytes(userId)),
                        (rs, rowNum) -> "ACTIVE".equals(rs.getString("status")))
                .orElse(false);
    }
}
