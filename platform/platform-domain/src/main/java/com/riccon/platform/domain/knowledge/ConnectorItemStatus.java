package com.riccon.platform.domain.knowledge;

/**
 * 连接器源对象的同步状态（ADR-0023 第 6.2/6.3 节）。
 *
 * <p>同一 {@code (connector, external_id_hash)} 源对象重现时复用 {@link #DELETED} 旧行并把
 * {@code generation} 加一，不插入第二行；{@link #FAILED} 在下一轮同步重试。
 */
public enum ConnectorItemStatus {
    /** 已发现但尚未处理。 */
    PENDING,
    /** 已导入并绑定知识库文档。 */
    SYNCED,
    /** 内容 checksum 未变化或文档已索引，本轮跳过。 */
    SKIPPED,
    /** 单项处理失败；保留错误码，下一轮重试。 */
    FAILED,
    /** 源端删除或同步删除；行保留为 tombstone 以便重现复用。 */
    DELETED
}
