package com.riccon.platform.domain.knowledge;

/**
 * 知识库索引请求模式（ADR-0023 第 7.3 节）。
 *
 * <p>索引事件 payload 的可选字段 {@code mode}；旧消费者缺省按 {@link #FULL} 处理，保持向后兼容。
 * 模式只影响 Worker 的索引行为，不影响授权判定（MySQL 始终是授权权威）。
 */
public enum KnowledgeIndexMode {
    /** 允许重新解析源文件并原子替换分块。 */
    FULL,
    /** 只读现有 MySQL 分块并刷新 OpenSearch 文档，保持 {@code chunk_id} 稳定。 */
    ACL_ONLY,
    /** 删除该文档的索引与分块（软删除后的清理）。 */
    DELETE
}
