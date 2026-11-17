package com.riccon.api.research.domain;

/**
 * 产物引用来源类型。
 *
 * <p>OpenAPI {@code ArtifactCitation.sourceType} 取值为 FILE、KNOWLEDGE、MCP；平台共享的
 * {@code CitationSourceType} 只覆盖前两者（对话引用不来自 MCP），因此报告模块保留自己的枚举，
 * 避免为 M02 未定义的对话来源提前扩张共享词汇。
 */
public enum ArtifactSourceType {
    /** 工作空间文件。 */
    FILE,
    /** 租户知识库文档。 */
    KNOWLEDGE,
    /** MCP 工具调用结果。 */
    MCP
}
