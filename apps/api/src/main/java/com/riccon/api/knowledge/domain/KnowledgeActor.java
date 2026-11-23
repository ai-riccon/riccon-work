package com.riccon.api.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 知识库操作者快照。
 *
 * <p>控制器从 {@code AuthService} 读取当前权限后构造；服务层只依据布尔值做资源级判定，不重复查询角色表。
 * {@code aclManage} 同时是 U8 管理性读取标记：当前只有 {@code TENANT_ADMIN} 持有
 * {@code knowledge.acl.manage}（ADR-0023 第 5.6 节）。
 *
 * @param userId 账号 ID
 * @param tenantId 租户代码
 * @param aclManage 是否拥有 {@code knowledge.acl.manage}
 * @param manage 是否拥有 {@code knowledge.manage}
 */
public record KnowledgeActor(UUID userId, String tenantId, boolean aclManage, boolean manage) {

    /** 校验操作者快照。 */
    public KnowledgeActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    /**
     * 是否允许绕过文档 ACL 做管理性读取。
     *
     * @return 拥有 {@code knowledge.acl.manage} 时返回 {@code true}
     */
    public boolean unrestricted() {
        return aclManage;
    }
}
