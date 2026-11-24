package com.riccon.api.workflow.definition.domain;

import com.riccon.platform.domain.workflow.WorkflowTemplateSource;

/**
 * 流程来源模板类型（内部口径）。
 *
 * <p>公开契约 {@code WorkflowDefinition.sourceTemplateType} 使用 {@code BUILT_IN}/{@code TENANT}，
 * 而 V15 的 {@code workflow_definition.source_template_type} 与审计白名单使用
 * {@code BUILT_IN}/{@code USER}。本枚举在业务层采用契约口径，把数据库/审计差异集中在
 * {@link #auditValue()} 与持久化映射中，避免修改已冻结的 V15（ADR-0024 §3.3.1/§8.1）。
 */
public enum WorkflowDefinitionSourceType {
    /** 全局内置模板。 */
    BUILT_IN,
    /** 租户内用户模板（M06b 启用）。 */
    TENANT;

    /**
     * 转换为公开契约枚举。
     *
     * @return 契约模板来源
     */
    public WorkflowTemplateSource toContract() {
        return this == BUILT_IN ? WorkflowTemplateSource.BUILT_IN : WorkflowTemplateSource.TENANT;
    }

    /**
     * 返回数据库与审计白名单使用的值。
     *
     * <p>V15 CHECK 与 {@code AuditDetailsPolicy.workflow.created} 只接受 {@code BUILT_IN}/{@code USER}；
     * 该差异来自契约与迁移的命名不一致，不能通过修改已应用的 V15 解决。
     *
     * @return {@code BUILT_IN} 或 {@code USER}
     */
    public String auditValue() {
        return this == BUILT_IN ? "BUILT_IN" : "USER";
    }

    /**
     * 从数据库值解析。
     *
     * @param value 数据库列值
     * @return 内部枚举
     * @throws IllegalArgumentException 未知值
     */
    public static WorkflowDefinitionSourceType fromDatabase(String value) {
        if ("BUILT_IN".equals(value)) {
            return BUILT_IN;
        }
        if ("USER".equals(value)) {
            return TENANT;
        }
        throw new IllegalArgumentException("unsupported workflow source template type");
    }
}
