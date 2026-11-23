package com.riccon.api.chat.domain;

/**
 * 引用可用性状态，对应 OpenAPI {@code CitationSourceStatus}。
 *
 * <p>由读取时的知识库文档状态重新计算，不持久化：{@code NO_ACCESS}（ACL 撤销）优先于
 * {@code DELETED}（源文档软删除），{@code UPDATED}（源内容版本变化）再次之，其余为
 * {@code AVAILABLE}（ADR-0023 第 5.4 节）。聊天消息引用与产物引用共用同一枚举，避免两处语义漂移。
 */
public enum CitationSourceStatus {
    /** 当前账号仍可访问，且源内容版本未变化（或没有版本快照可比）。 */
    AVAILABLE,
    /** 当前账号已失去文档 ACL 可见性；响应必须遮蔽标题、片段与定位。 */
    NO_ACCESS,
    /** 源文档已软删除；响应必须遮蔽标题、片段与定位。 */
    DELETED,
    /** 源内容 checksum 与引用创建时快照不同；保留快照并标注已更新。 */
    UPDATED
}
