package com.riccon.api.knowledge.dto;

import com.riccon.api.knowledge.domain.KnowledgeAclUser;
import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.util.List;
import java.util.UUID;

/**
 * 知识库文档 ACL 响应，对应 OpenAPI {@code KnowledgeDocumentAcl}。
 *
 * <p>{@code USERS} 名单包含创建人条目（{@code creator=true}）；{@code TENANT}/{@code DEPT} 时名单为空。
 * 历史 SEED 文档没有创建人，{@code creatorUserId} 为空。
 *
 * @param visibility 可见范围
 * @param departmentId 可见部门
 * @param users 显式名单（含创建人）
 * @param creatorUserId 创建人
 */
public record KnowledgeDocumentAclResponse(
        KnowledgeVisibility visibility, UUID departmentId, List<KnowledgeAclUser> users, UUID creatorUserId) {

    /**
     * 由文档快照与 ACL 名单构造响应。
     *
     * @param document 文档
     * @param users 名单（调用方负责包含创建人并排序）
     * @return ACL 响应
     */
    public static KnowledgeDocumentAclResponse from(KnowledgeDocumentRecord document, List<KnowledgeAclUser> users) {
        return new KnowledgeDocumentAclResponse(
                document.visibility(), document.departmentId(), List.copyOf(users), document.createdBy());
    }
}
