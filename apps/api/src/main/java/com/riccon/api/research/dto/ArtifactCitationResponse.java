package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.UUID;

/**
 * 产物引用响应，对应 OpenAPI {@code ArtifactCitation}。
 *
 * @param ordinal 序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID
 * @param knowledgeDocumentId 知识库文档 ID
 * @param mcpInvocationId MCP 调用 ID
 * @param title 标题
 * @param snippet 片段
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param paragraphNo 段落号
 */
public record ArtifactCitationResponse(
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

    /**
     * 将引用快照映射为响应。
     *
     * @param citation 引用快照
     * @return 响应
     */
    public static ArtifactCitationResponse from(ArtifactCitationSnapshot citation) {
        return new ArtifactCitationResponse(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.mcpInvocationId(),
                citation.title(),
                citation.snippet(),
                citation.locatorType(),
                citation.pageNo(),
                citation.sheetName(),
                citation.paragraphNo());
    }
}
