package com.riccon.worker.ocr.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 单页 OCR 识别结果。
 *
 * <p>引擎只返回文本块与渲染尺寸；平均置信度在读取时按文本长度加权计算，避免短词/标点对整页
 * 质量产生不成比例的影响。空块列表表示引擎成功执行但没有识别到文本，由服务层映射为
 * {@code KB_NO_TEXT}。
 *
 * @param blocks 文本块，按阅读顺序
 * @param width 渲染图片宽度（像素）
 * @param height 渲染图片高度（像素）
 */
public record OcrResult(List<OcrBlock> blocks, int width, int height) {

    /** 防御性复制并校验渲染尺寸。 */
    public OcrResult {
        blocks = List.copyOf(blocks);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("ocr result width and height must be positive");
        }
    }

    /**
     * 按文本长度加权计算平均置信度。
     *
     * @return 0–1 的置信度，保留 3 位小数；无文本块时返回 null
     */
    public BigDecimal averageConfidence() {
        return weightedAverageConfidence(blocks);
    }

    /**
     * 计算一组文本块的加权平均置信度。
     *
     * @param blocks 文本块
     * @return 0–1 的置信度，保留 3 位小数；无文本块时返回 null
     */
    public static BigDecimal weightedAverageConfidence(List<OcrBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        long weight = 0;
        for (OcrBlock block : blocks) {
            int blockWeight =
                    Math.max(1, block.text().codePointCount(0, block.text().length()));
            weighted = weighted.add(block.confidence().multiply(BigDecimal.valueOf(blockWeight)));
            weight += blockWeight;
        }
        return weighted.divide(BigDecimal.valueOf(weight), 3, RoundingMode.HALF_UP);
    }
}
