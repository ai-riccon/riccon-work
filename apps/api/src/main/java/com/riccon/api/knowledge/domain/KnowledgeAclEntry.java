package com.riccon.api.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 文档 ACL 显式名单条目（仓储层投影）。
 *
 * <p>只含账号 ID 与展示名；创建人条目由服务层单独合并，不写入 {@code knowledge_document_acl}。
 *
 * @param userId 账号 ID
 * @param displayName 展示名
 */
public record KnowledgeAclEntry(UUID userId, String displayName) {

    /** 校验账号与展示名。 */
    public KnowledgeAclEntry {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
