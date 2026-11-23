package com.riccon.worker.parse.service;

import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 文本切分器。
 *
 * <p>按字符窗口切分，相邻块保留重叠，避免答案跨块被截断；定位字段（页码/工作表/单元格/段落）
 * 与 M05 的 OCR 区域（bbox）和置信度从原始文本段原样继承，同一页/行被切多块时仍能回溯。
 * Excel/OCR 文本通常含换行，切分优先在换行处断开，避免把一行拆成两个无语义块；token 数按字符数
 * / 4 估算，只用于上下文预算，不作为精确计费依据。
 */
@Component
public class TextChunker {

    /** 单块最大字符数。 */
    private final int chunkSize;

    /** 相邻块重叠字符数。 */
    private final int chunkOverlap;

    /**
     * 创建切分器。
     *
     * @param properties 解析配置
     */
    public TextChunker(ParseProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.chunkSize = properties.chunkSize();
        this.chunkOverlap = properties.chunkOverlap();
    }

    /**
     * 切分解析结果。
     *
     * @param content 解析结果
     * @return 文本块，顺序与原文一致
     */
    public List<ParsedChunk> chunk(ParsedContent content) {
        List<ParsedChunk> chunks = new ArrayList<>();
        for (ParsedSegment segment : content.segments()) {
            chunks.addAll(chunkSegment(segment));
        }
        return List.copyOf(chunks);
    }

    /**
     * 切分单个文本段。
     *
     * @param segment 原始文本段
     * @return 文本块
     */
    private List<ParsedChunk> chunkSegment(ParsedSegment segment) {
        String text = segment.content() == null ? "" : segment.content().strip();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.length() <= chunkSize) {
            return List.of(toChunk(text, segment));
        }
        List<ParsedChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                // 换行优先：只在窗口后半段断行，避免产生大量极短块。
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline >= start + chunkSize / 2) {
                    end = newline;
                }
            }
            // 避免在 UTF-16 代理对中间截断，否则会产生非法字符。
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            if (end <= start) {
                end = Math.min(start + chunkSize, text.length());
            }
            String part = text.substring(start, end).strip();
            if (!part.isBlank()) {
                chunks.add(toChunk(part, segment));
            }
            if (end >= text.length()) {
                break;
            }
            int next = Math.max(end - chunkOverlap, start + 1);
            start = next;
        }
        return List.copyOf(chunks);
    }

    /**
     * 构造带定位、bbox、置信度与 token 估算的文本块。
     *
     * @param content 块内容
     * @param segment 来源文本段
     * @return 文本块
     */
    private static ParsedChunk toChunk(String content, ParsedSegment segment) {
        return new ParsedChunk(
                content,
                segment.locatorType(),
                segment.pageNo(),
                segment.sheetName(),
                segment.cellRange(),
                segment.paragraphNo(),
                estimateTokens(content),
                segment.bbox(),
                segment.ocrConfidence());
    }

    /**
     * 估算 token 数。
     *
     * @param content 文本
     * @return 向上取整的字符数 / 4
     */
    public static int estimateTokens(String content) {
        return content == null ? 0 : (content.length() + 3) / 4;
    }
}
