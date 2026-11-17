package com.riccon.platform.domain.knowledge;

/**
 * 知识库文档状态。
 *
 * <p>M02 的文档级权限尚未引入；同租户员工可检索全部知识库文档，这是已记录的残余风险（THREAT-MODEL R-01）。
 */
public enum KnowledgeDocumentStatus {
    /** 已登记，等待解析/索引。 */
    PENDING,
    /** 正在解析或写入 OpenSearch。 */
    INDEXING,
    /** 可检索。 */
    INDEXED,
    /** 解析或索引失败。 */
    FAILED,
    /** 已软删除，等待索引清理。 */
    DELETED
}
