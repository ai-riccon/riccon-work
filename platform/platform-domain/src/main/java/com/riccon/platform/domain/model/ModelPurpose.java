package com.riccon.platform.domain.model;

/**
 * 模型调用用途。
 *
 * <p>用途进入审计与运行详情，用于区分对话、报告生成与后续的向量化；不得用于承载用户可控文本。
 */
public enum ModelPurpose {
    /** J1 对话回答。 */
    CHAT,
    /** J2 报告生成。 */
    REPORT
}
