package com.riccon.api.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 文档 ACL 名单条目。
 *
 * @param userId 账号 ID
 * @param displayName 展示名
 * @param creator 是否创建人隐式授权；创建人条目不可移除
 */
public record KnowledgeAclUser(UUID userId, String displayName, boolean creator) {

    /** 校验账号与展示名。 */
    public KnowledgeAclUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
