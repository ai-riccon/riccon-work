package com.riccon.api.knowledge.domain;

/**
 * 知识库文档来源。
 *
 * <p>M02 只有用户上传与演示语料两种来源；演示语料没有来源文件，由迁移或运维脚本写入。
 */
public enum KnowledgeSourceType {
    /** 由工作空间文件登记。 */
    UPLOAD,
    /** 平台预置的演示语料。 */
    SEED
}
