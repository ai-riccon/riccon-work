package com.riccon.platform.domain.chat;

/**
 * 消息角色。
 *
 * <p>角色由服务端构造；客户端不能提交 {@link #SYSTEM} 或 {@link #TOOL} 消息，避免提示注入伪造角色。
 */
public enum MessageRole {
    /** 员工输入。 */
    USER,
    /** 模型回答。 */
    ASSISTANT,
    /** 服务端系统提示，永不回显给客户端。 */
    SYSTEM,
    /** 工具/MCP 结果摘要，作为不可信数据处理。 */
    TOOL
}
