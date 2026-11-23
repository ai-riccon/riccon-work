package com.riccon.api.knowledge.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 知识库可见性求值上下文。
 *
 * <p>由 {@code KnowledgeAccessJdbc} 按当前账号一次性解析：用户主部门、部门是否 {@code ACTIVE}、
 * 是否拥有管理性读取（{@code TENANT_ADMIN}）以及 ACL 强制开关。列表、详情、检索、下载与 ACL 判定必须
 * 共享同一实例语义，禁止各入口自行拼装条件（ADR-0023 第 5.4 节、TM-56）。
 *
 * @param userId 当前账号 ID
 * @param departmentId 当前账号主部门；为空表示未分配
 * @param departmentActive 主部门是否启用；无部门时为 {@code false}
 * @param unrestricted 是否管理性读取（绕过文档 ACL）
 * @param enforceAcl 是否启用文档级 ACL；关闭时谓词退化为租户级（ADR-0023 第 5.8 节）
 */
public record KnowledgeVisibilityScope(
        UUID userId, UUID departmentId, boolean departmentActive, boolean unrestricted, boolean enforceAcl) {

    /** 校验求值上下文完整。 */
    public KnowledgeVisibilityScope {
        Objects.requireNonNull(userId, "userId must not be null");
        if (!enforceAcl) {
            // 关闭 ACL 时谓词退化为租户级，部门信息不参与判定；userId 保留用于审计。
            departmentId = null;
            departmentActive = false;
        }
    }

    /**
     * 是否需要对 SQL 追加 ACL 谓词。
     *
     * @return 强制开启且非管理性读取时返回 {@code true}
     */
    public boolean requiresAclPredicate() {
        return enforceAcl && !unrestricted;
    }
}
