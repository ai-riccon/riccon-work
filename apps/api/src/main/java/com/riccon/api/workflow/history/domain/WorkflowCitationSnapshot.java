package com.riccon.api.workflow.history.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.Objects;
import java.util.UUID;

/**
 * 节点引用快照（运行详情聚合前的原始记录）。
 *
 * <p>字段与 Worker 写入的 {@code workflow_node_run.citations_json} 一致；{@code sourceStatus} 不持久化，
 * 由 API 读取时按当前账号 ACL、软删除与 checksum 重新计算（ADR-0024 §3.4、ADR-0023 §5.4）。
 *
 * @param ordinal 节点内展示序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID；可空
 * @param knowledgeDocumentId 知识库文档 ID；可空
 * @param chunkId 分块 ID；可空
 * @param locatorType 定位类型；可空
 * @param pageNo 页码；可空
 * @param sheetName 工作表名；可空
 * @param cellRange 单元格范围；可空
 * @param paragraphNo 段落号；可空
 * @param title 标题快照
 * @param snippet 片段快照
 * @param score 相关度；可空
 * @param sourceVersion 引用时文档 checksum；可空
 */
public record WorkflowCitationSnapshot(
        int ordinal,
        CitationSourceType sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID chunkId,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        String title,
        String snippet,
        Double score,
        String sourceVersion) {

    /** 校验必填字段。 */
    public WorkflowCitationSnapshot {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        title = title == null ? "" : title;
        snippet = snippet == null ? "" : snippet;
    }

    /**
     * 返回引用去重键。
     *
     * <p>同一文档的不同分块是不同引用；缺少全部 ID 时退化为来源类型 + 标题 + 片段，避免把不可识别的
     * 快照全部折叠成一条。
     *
     * @return 去重键
     */
    public String dedupeKey() {
        if (chunkId != null) {
            return sourceType.name() + ":chunk:" + chunkId;
        }
        if (knowledgeDocumentId != null) {
            return sourceType.name() + ":document:" + knowledgeDocumentId;
        }
        if (fileId != null) {
            return sourceType.name() + ":file:" + fileId;
        }
        return sourceType.name() + ":text:" + title + '\u0000' + snippet;
    }

    /**
     * 返回审批数据范围摘要使用的文档引用键。
     *
     * <p>与 T09 审批 `dataScopeSummary.documentReferenceCount` 口径一致：优先文档、其次文件、再次分块，
     * 只计数不返回标题/片段/正文。
     *
     * @return 引用键；没有任何 ID 时为空
     */
    public String documentKey() {
        if (knowledgeDocumentId != null) {
            return knowledgeDocumentId.toString();
        }
        if (fileId != null) {
            return fileId.toString();
        }
        if (chunkId != null) {
            return chunkId.toString();
        }
        return null;
    }
}
