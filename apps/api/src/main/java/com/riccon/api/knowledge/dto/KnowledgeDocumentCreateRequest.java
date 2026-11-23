package com.riccon.api.knowledge.dto;

import java.util.List;
import java.util.UUID;

/**
 * 将已上传文件登记为知识库文档，对应 OpenAPI {@code KnowledgeDocumentCreateRequest}。
 *
 * <p>可见范围字段使用字符串接收，由服务层转换为枚举并返回 {@code RICCON-KB-400}；这样非法枚举值
 * 不会在 JSON 解析阶段变成通用 400，符合 M05 契约错误码。
 *
 * @param title 标题，1–300 字符
 * @param fileId 来源文件 ID
 * @param visibility 可见范围；缺省 {@code TENANT}
 * @param departmentId 可见部门；仅 {@code DEPT} 允许且必填
 * @param userIds 显式授权账号；仅 {@code USERS} 允许
 */
public record KnowledgeDocumentCreateRequest(
        String title, UUID fileId, String visibility, String departmentId, List<String> userIds) {}
