package com.riccon.api.research.domain;

import com.riccon.platform.domain.chat.LocatorType;
import java.util.Objects;
import java.util.UUID;

/**
 * 产物引用条目。
 *
 * <p>每条引用必须能对应任务时间线中的检索命中或 MCP 调用，供 AC-10 人工抽查。OpenAPI 的
 * {@code ArtifactCitation} 不包含 {@code cellRange}，因此本快照不承载该列。
 *
 * @param ordinal 引用序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 文件 ID
 * @param knowledgeDocumentId 知识库文档 ID
 * @param mcpInvocationId MCP 调用 ID
 * @param title 标题快照
 * @param snippet 片段摘要
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param paragraphNo 段落号
 */
public record ArtifactCitationSnapshot(
        int ordinal,
        ArtifactSourceType sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID mcpInvocationId,
        String title,
        String snippet,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        Integer paragraphNo) {

    /** 校验必填字段与来源 ID 的组合，防止跨来源错配。 */
    public ArtifactCitationSnapshot {
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1");
        }
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(snippet, "snippet must not be null");
    }
}
