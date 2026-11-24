package com.riccon.platform.domain.workflow;

/**
 * 内置模板的稳定键与固定 ID。
 *
 * <p>内置模板种子在 V15 写入，升级不得改变 ID；M06 只提供 {@link #MARKET_RESEARCH} 一个 J2 等价模板
 * （检索 → 模型 → 审批 → 工具 → 模型 → 结束）。模板复制时用户副本使用新 UUIDv7，不复用该 ID
 * （ADR-0024 第 3.3.7/12 节）。
 */
public final class WorkflowTemplateKeys {

    /** 市场调研模板的稳定键。 */
    public static final String MARKET_RESEARCH = "market-research";

    /** 市场调研模板的固定 ID；V15 种子与 API 读取共用，升级不改变。 */
    public static final String MARKET_RESEARCH_ID = "01990000-0000-7000-8000-000000000601";

    /** 工具类禁止实例化。 */
    private WorkflowTemplateKeys() {}
}
