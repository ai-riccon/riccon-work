package com.riccon.platform.domain.chat;

/**
 * 引用来源类型。
 *
 * <p>{@link #FILE} 指向工作空间文件，{@link #KNOWLEDGE} 指向租户知识库文档；M02 不引入外部网页引用。
 */
public enum CitationSourceType {
    /** 工作空间文件。 */
    FILE,
    /** 租户知识库文档。 */
    KNOWLEDGE
}
