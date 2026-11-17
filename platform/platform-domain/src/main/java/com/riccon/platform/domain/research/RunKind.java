package com.riccon.platform.domain.research;

/**
 * 运行类别。
 *
 * <p>对话流、文件解析与报告任务共用运行与租约模型；文件解析不产生对话消息，报告任务产生阶段与审批。
 */
public enum RunKind {
    /** J1 流式对话，直连 SSE，不走 outbox。 */
    CHAT,
    /** 文件解析，走 outbox。 */
    FILE_PARSE,
    /** 知识库文档解析与索引，走 outbox。 */
    KNOWLEDGE_INDEX,
    /** J2 市场调研报告，走 outbox。 */
    RESEARCH_REPORT
}
