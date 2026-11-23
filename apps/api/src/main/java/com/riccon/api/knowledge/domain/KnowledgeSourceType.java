package com.riccon.api.knowledge.domain;

/**
 * 知识库文档来源。
 *
 * <p>M02 只有用户上传与演示语料；M05b 增加连接器导入。连接器文档的 {@code file_id} 为空，
 * 原文由连接器服务写入对象存储，并由 {@code connector_id} 关联来源连接器。
 */
public enum KnowledgeSourceType {
    /** 由工作空间文件登记。 */
    UPLOAD,
    /** 平台预置的演示语料。 */
    SEED,
    /** 由知识库连接器导入；{@code fileId} 为空，{@code connectorId} 非空。 */
    CONNECTOR
}
