package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.knowledge.ParseEngine;
import java.util.List;

/**
 * 一次文档解析的完整结果。
 *
 * <p>M05 起携带解析引擎、白名单警告与结构摘要，由索引处理器写入
 * {@code knowledge_document.parse_engine/parse_warnings/parse_summary}；旧构造器默认
 * {@link ParseEngine#NONE}、空警告、空摘要，保证 M02–M04 解析器在 B4/B5 接入前可编译。
 *
 * @param segments 原始文本段，顺序与原文一致
 * @param pageCount PDF/图片页数；不适用时为 null
 * @param sheetCount Excel 工作表数量；不适用时为 null
 * @param parserVersion 解析器版本，便于失败复现
 * @param parseEngine 实际使用的解析引擎；未知/未解析为 {@link ParseEngine#NONE}
 * @param warnings 白名单警告码（含参数），不含原文
 * @param summary 结构摘要；不适用时为空
 */
public record ParsedContent(
        List<ParsedSegment> segments,
        Integer pageCount,
        Integer sheetCount,
        String parserVersion,
        ParseEngine parseEngine,
        List<String> warnings,
        ParseSummary summary) {

    /** 防御性复制并对 null 归一。 */
    public ParsedContent {
        segments = List.copyOf(segments);
        parseEngine = parseEngine == null ? ParseEngine.NONE : parseEngine;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 兼容 M02–M04 的简化构造器。
     *
     * @param segments 原始文本段
     * @param pageCount 页数
     * @param sheetCount 工作表数
     * @param parserVersion 解析器版本
     */
    public ParsedContent(List<ParsedSegment> segments, Integer pageCount, Integer sheetCount, String parserVersion) {
        this(segments, pageCount, sheetCount, parserVersion, null, null, null);
    }
}
