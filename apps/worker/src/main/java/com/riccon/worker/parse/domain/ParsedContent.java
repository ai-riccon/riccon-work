package com.riccon.worker.parse.domain;

import java.util.List;

/**
 * 一次文档解析的完整结果。
 *
 * @param segments 原始文本段，顺序与原文一致
 * @param pageCount PDF/图片页数；不适用时为 null
 * @param sheetCount Excel 工作表数量；不适用时为 null
 * @param parserVersion 解析器版本，便于失败复现
 */
public record ParsedContent(List<ParsedSegment> segments, Integer pageCount, Integer sheetCount, String parserVersion) {

    /** 防御性复制，避免调用方修改解析结果。 */
    public ParsedContent {
        segments = List.copyOf(segments);
    }
}
