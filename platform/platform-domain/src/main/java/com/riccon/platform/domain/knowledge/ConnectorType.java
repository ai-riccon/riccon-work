package com.riccon.platform.domain.knowledge;

/**
 * 知识库外部连接器类型（ADR-0023 第 6 节）。
 *
 * <p>M05b 只实现 S3 兼容目录；类型是封闭集合，数据库以 CHECK 约束固化。新增类型必须同步
 * ADR、迁移与连接器实现，不能只放宽 CHECK。
 */
public enum ConnectorType {
    /** S3 兼容对象存储（bucket + prefix，ListObjectsV2 + ETag 增量）。 */
    S3
}
