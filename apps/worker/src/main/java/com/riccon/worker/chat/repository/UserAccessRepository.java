package com.riccon.worker.chat.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.chat.persistence.UserAccessJdbc;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 用户状态仓储。 */
@Component
public class UserAccessRepository {

    /** 用户状态访问。 */
    private final UserAccessJdbc userAccessJdbc;

    /**
     * 创建仓储。
     *
     * @param userAccessJdbc 用户状态访问
     */
    public UserAccessRepository(UserAccessJdbc userAccessJdbc) {
        this.userAccessJdbc = Objects.requireNonNull(userAccessJdbc, "userAccessJdbc must not be null");
    }

    /**
     * 判断用户启用。
     *
     * @param tenantId 租户
     * @param userId 用户
     * @return 是否启用
     */
    public boolean isActive(TenantId tenantId, UUID userId) {
        return userAccessJdbc.isActive(tenantId, userId);
    }
}
