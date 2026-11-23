package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 文本切分器测试。 */
class TextChunkerTest {

    /** 测试切分器：块大小 100，重叠 20。 */
    private final TextChunker chunker = new TextChunker(new ParseProperties(1024, 100, 20));

    /** 短文本不切分，定位字段原样保留。 */
    @Test
    void chunk_shortSegment_returnsSingleChunkWithLocator() {
        ParsedContent content = new ParsedContent(
                List.of(new ParsedSegment("short paragraph", LocatorType.PAGE, 3, null, null, null)),
                null,
                null,
                "test");

        List<ParsedChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("short paragraph");
        assertThat(chunks.get(0).locatorType()).isEqualTo(LocatorType.PAGE);
        assertThat(chunks.get(0).pageNo()).isEqualTo(3);
    }

    /** 长文本按窗口切分，块大小不超过配置。 */
    @Test
    void chunk_longSegment_splitsIntoBoundedChunks() {
        String text = "0123456789".repeat(25);
        ParsedContent content = new ParsedContent(
                List.of(new ParsedSegment(text, LocatorType.SHEET, null, "Budget", "A1:B20", null)), null, 1, "test");

        List<ParsedChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(100));
        assertThat(chunks.get(0).sheetName()).isEqualTo("Budget");
        assertThat(chunks.get(0).cellRange()).isEqualTo("A1:B20");
    }

    /** 空白段不产生分块。 */
    @Test
    void chunk_blankSegment_returnsEmpty() {
        ParsedContent content = new ParsedContent(
                List.of(new ParsedSegment("   ", LocatorType.PARAGRAPH, null, null, null, 1)), null, null, "test");

        assertThat(chunker.chunk(content)).isEmpty();
    }

    /** 多段内容保持原始顺序。 */
    @Test
    void chunk_multipleSegments_preservesOrder() {
        ParsedContent content = new ParsedContent(
                List.of(
                        new ParsedSegment("first", LocatorType.PAGE, 1, null, null, null),
                        new ParsedSegment("second", LocatorType.PAGE, 2, null, null, null)),
                2,
                null,
                "test");

        List<ParsedChunk> chunks = chunker.chunk(content);

        assertThat(chunks).extracting(ParsedChunk::pageNo).containsExactly(1, 2);
    }

    /** token 估算按 4 字符向上取整。 */
    @Test
    void estimateTokens_roundsUp() {
        assertThat(TextChunker.estimateTokens(null)).isZero();
        assertThat(TextChunker.estimateTokens("")).isZero();
        assertThat(TextChunker.estimateTokens("abcd")).isEqualTo(1);
        assertThat(TextChunker.estimateTokens("abcde")).isEqualTo(2);
    }

    /** 长文本优先在换行处截断，避免把一整行拆成两个块。 */
    @Test
    void chunk_longTextWithNewlines_breaksAtNewline() {
        String text = "A".repeat(60) + "\n" + "B".repeat(60) + "\n" + "C".repeat(60);
        ParsedContent content = new ParsedContent(
                List.of(new ParsedSegment(text, LocatorType.SHEET, null, "Budget", "A1:B20", null)), null, 1, "test");

        List<ParsedChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).content()).isEqualTo("A".repeat(60));
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(100));
    }

    /** OCR 段的 bbox 与置信度被切分块原样继承。 */
    @Test
    void chunk_ocrSegment_preservesBboxAndConfidence() {
        BoundingBox bbox = new BoundingBox(1, 2, 30, 40);
        ParsedSegment segment =
                new ParsedSegment("ocr text", LocatorType.PAGE, 2, null, null, null, bbox, new BigDecimal("0.780"));
        ParsedContent content = new ParsedContent(List.of(segment), 2, null, "test");

        List<ParsedChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).bbox()).isEqualTo(bbox);
        assertThat(chunks.get(0).ocrConfidence()).isEqualByComparingTo("0.780");
        assertThat(chunks.get(0).pageNo()).isEqualTo(2);
    }
}
