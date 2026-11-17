package com.riccon.worker.index.domain;

import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import java.util.UUID;

/**
 * 知识库文档快照。
 *
 * @param id 文档 ID
 * @param title 文档标题
 * @param fileId 来源文件 ID；演示语料可为空
 * @param status 当前索引状态
 */
public record KnowledgeDocumentRecord(UUID id, String title, UUID fileId, KnowledgeDocumentStatus status) {}
