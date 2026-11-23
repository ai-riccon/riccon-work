package com.riccon.worker.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrPageResult;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OCR 领域模型测试：置信度加权、边界校验与成功/失败互斥。 */
class OcrDomainTest {

    /** 平均置信度按文本长度加权，保留 3 位小数。 */
    @Test
    @DisplayName("OcrResult 按文本长度加权平均置信度")
    void averageConfidence_weightedByTextLength() {
        OcrBlock longBlock = OcrTestSupport.block("abcdefghij", "0.900", 1, 1, new BoundingBox(0, 0, 10, 10));
        OcrBlock shortBlock = OcrTestSupport.block("xy", "0.500", 1, 2, new BoundingBox(0, 10, 10, 10));

        OcrResult result = new OcrResult(List.of(longBlock, shortBlock), 100, 100);

        // (0.9*10 + 0.5*2) / 12 = 0.8333... → 0.833
        assertThat(result.averageConfidence()).isEqualByComparingTo("0.833");
        assertThat(result.blocks()).hasSize(2);
    }

    /** 空块列表的平均置信度为 null，表示没有可索引文本。 */
    @Test
    @DisplayName("OcrResult 空块平均置信度为 null")
    void averageConfidence_emptyBlocks_returnsNull() {
        assertThat(new OcrResult(List.of(), 10, 10).averageConfidence()).isNull();
    }

    /** OCR 选项与块校验页码、DPI、置信度与 bbox。 */
    @Test
    @DisplayName("OCR 选项与块校验边界")
    void optionsAndBlocks_validateBounds() {
        assertThat(new OcrOptions(1, 200).pageNo()).isEqualTo(1);
        assertThatThrownBy(() -> new OcrOptions(0, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OcrOptions(1, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OcrTestSupport.block(" ", "0.5", 1, 1, new BoundingBox(0, 0, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OcrTestSupport.block("x", "1.5", 1, 1, new BoundingBox(0, 0, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OcrTestSupport.block("x", "0.5", 0, 1, new BoundingBox(0, 0, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 成功结果不得携带错误码，失败结果不得携带文本块。 */
    @Test
    @DisplayName("OcrPageResult 成功/失败字段互斥")
    void pageResult_successAndFailureAreExclusive() {
        OcrPageResult success = OcrPageResult.success(
                1,
                List.of(OcrTestSupport.block("text", "0.9", 1, 1, new BoundingBox(0, 0, 10, 10))),
                new BigDecimal("0.900"),
                false,
                false);
        assertThat(success.success()).isTrue();
        assertThat(success.errorCode()).isNull();

        OcrPageResult failure = OcrPageResult.failure(2, "KB_OCR_FAILED", "OCR 识别失败");
        assertThat(failure.success()).isFalse();
        assertThat(failure.blocks()).isEmpty();

        assertThatThrownBy(() -> new OcrPageResult(1, true, "KB_OCR_FAILED", null, List.of(), null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OcrPageResult(
                        1,
                        false,
                        "KB_OCR_FAILED",
                        "x",
                        List.of(OcrTestSupport.block("text", "0.9", 1, 1, new BoundingBox(0, 0, 1, 1))),
                        null,
                        false,
                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
