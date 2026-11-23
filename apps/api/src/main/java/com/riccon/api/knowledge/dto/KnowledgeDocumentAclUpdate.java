package com.riccon.api.knowledge.dto;

import java.util.List;

/**
 * 整体替换文档 ACL，对应 OpenAPI {@code KnowledgeDocumentAclUpdate}。
 *
 * <p>使用字符串接收枚举与 UUID，服务层统一转换为 {@code RICCON-KB-400}，避免契约错误码漂移。
 *
 * @param visibility 目标可见范围
 * @param departmentId 目标部门；仅 {@code DEPT} 允许且必填
 * @param userIds 目标显式名单；仅 {@code USERS} 允许，最多 200 人
 */
public record KnowledgeDocumentAclUpdate(String visibility, String departmentId, List<String> userIds) {}
