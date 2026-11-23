package com.riccon.api.knowledge.connector.domain;

import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.util.UUID;

/**
 * 连接器已导入文档的删除所需字段。
 *
 * @param documentId 文档 ID
 * @param visibility 可见范围
 * @param departmentId 部门
 * @param aclVersion ACL 版本
 * @param sourceType 来源类型
 */
public record ConnectorDocumentRow(
        UUID documentId, KnowledgeVisibility visibility, UUID departmentId, int aclVersion, String sourceType) {}
