/**
 * 知识库领域词汇：文档索引状态。
 *
 * <p>MySQL 保存权威元数据与分块原文；OpenSearch 只是可重建的检索索引。删除文档时先软删除 MySQL，
 * 再异步清理索引，避免索引残留导致越权检索。
 */
package com.riccon.platform.domain.knowledge;
