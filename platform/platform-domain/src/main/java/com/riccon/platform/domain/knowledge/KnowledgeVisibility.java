package com.riccon.platform.domain.knowledge;

/**
 * 知识库文档可见范围（ADR-0023 第 5.1/5.2 节）。
 *
 * <p>{@link #TENANT} 表示本租户全部启用账号可见，也是历史文档与 M02 行为的默认值；{@link #DEPT}
 * 表示仅文档所属部门（单主部门）的启用成员可见，创建/修改时必须同时提供部门 ID；{@link #USERS}
 * 表示仅 {@code knowledge_document_acl} 中显式授权的账号与创建人可见。三种取值是封闭集合，
 * 数据库以 CHECK 约束固化，业务代码不得写字符串字面量。
 */
public enum KnowledgeVisibility {
    /** 本租户全部启用账号可见；M02/M03 的等价行为与历史文档回填值。 */
    TENANT,
    /** 仅所属部门启用成员可见；部门停用后下一次请求即失效。 */
    DEPT,
    /** 仅显式授权账号与创建人可见；创建人隐式可见且不可移除。 */
    USERS
}
