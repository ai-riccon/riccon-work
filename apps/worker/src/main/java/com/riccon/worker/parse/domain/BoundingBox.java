package com.riccon.worker.parse.domain;

/**
 * 解析分块在渲染页/图片上的像素区域（ADR-0023 第 4.3 节）。
 *
 * <p>坐标原点为渲染图片左上角，单位是渲染像素；不新增 {@code locator_type} 枚举值。
 * 只有 OCR/扫描件分块才有值，文本型 PDF/DOCX/Excel 为 null。
 *
 * @param x 左上角 X，非负
 * @param y 左上角 Y，非负
 * @param width 区域宽度，必须为正
 * @param height 区域高度，必须为正
 */
public record BoundingBox(int x, int y, int width, int height) {

    /**
     * 校验坐标边界，避免把负值或空区域写入索引/契约。
     */
    public BoundingBox {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("bounding box origin must not be negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bounding box size must be positive");
        }
    }
}
