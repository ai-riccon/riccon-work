/**
 * 文件领域词汇：支持格式、文件状态与解析状态。
 *
 * <p>文件原文在对象存储，元数据在 MySQL；解析由 worker 在隔离进程执行，解析文本与分块元数据回写
 * MySQL，检索索引写 OpenSearch。任何状态值都必须与迁移中的 CHECK 约束一致。
 */
package com.riccon.platform.domain.file;
