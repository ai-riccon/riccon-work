package com.riccon.platform.domain.file;

/**
 * 逻辑文件状态。
 *
 * <p>{@link #AVAILABLE} 表示对象已写入且元数据已提交；{@link #DELETED} 为软删除，对象清理由提交后的补偿任务执行。
 */
public enum FileStatus {
    /** 可预览、可解析、可引用。 */
    AVAILABLE,
    /** 已软删除；列表与读取默认不可见，审计保留。 */
    DELETED
}
