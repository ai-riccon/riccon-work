package com.riccon.api.research.domain;

import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.Objects;
import java.util.UUID;

/**
 * 产物引用条目。
 *
 * <p>每条引用必须能对应任务时间线中的检索命中或 MCP 调用，供 AC-10 人工抽查。{@code cellRange} 对应 OpenAPI
 * {@code ArtifactCitation.cellRange}，Excel 引用保留工作表与区域。{@code sourceVersion} 只在
 * {@code KNOWLEDGE} 引用写入，记录引用创建时的 {@code knowledge_document.checksum}；
 * {@code sourceStatus} 是读取时按当前 ACL、删除标记与 checksum 计算的展示状态，库内快照不改写。
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
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param sourceVersion 引用时的知识库文档 checksum；FILE/MCP 与历史引用为空
 * @param sourceStatus 读取时计算的可用性
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
        String cellRange,
        Integer paragraphNo,
        String sourceVersion,
        CitationSourceStatus sourceStatus) {

    /** 校验必填字段与来源 ID 的组合，防止跨来源错配。 */
    public ArtifactCitationSnapshot {
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1");
        }
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(snippet, "snippet must not be null");
        Objects.requireNonNull(sourceStatus, "sourceStatus must not be null");
        if (sourceVersion != null && sourceType != ArtifactSourceType.KNOWLEDGE) {
            throw new IllegalArgumentException("sourceVersion is only allowed for KNOWLEDGE citations");
        }
    }

    /**
     * 复制引用并替换读取时计算的可用性。
     *
     * @param status 可用性
     * @return 新引用
     */
    public ArtifactCitationSnapshot withSourceStatus(CitationSourceStatus status) {
        return new ArtifactCitationSnapshot(
                ordinal,
                sourceType,
                fileId,
                knowledgeDocumentId,
                mcpInvocationId,
                title,
                snippet,
                locatorType,
                pageNo,
                sheetName,
                cellRange,
                paragraphNo,
                sourceVersion,
                status);
    }
}
