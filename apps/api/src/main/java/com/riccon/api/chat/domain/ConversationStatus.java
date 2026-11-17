package com.riccon.api.chat.domain;

/**
 * 会话状态。
 *
 * <p>与 {@code conversation.status} 列一致：{@code ACTIVE|ARCHIVED}。{@code platform-domain} 尚未提供会话状态枚举，
 * 按编码规范放入 feature 局部 domain；新增状态值必须同步迁移注释与 OpenAPI。
 */
public enum ConversationStatus {
    /** 正常可读写。 */
    ACTIVE,
    /** 归档；M02 只读，不提供归档入口。 */
    ARCHIVED
}
