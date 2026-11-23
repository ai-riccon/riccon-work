package com.riccon.api.chat.domain;

/**
 * 引用在渲染页/图片中的像素区域，对应 OpenAPI {@code BoundingBox}。
 *
 * <p>坐标是渲染像素，不是 PDF 点单位，也不暴露对象存储坐标。仅 OCR 分块写入；没有完整四元组时引用不返回 bbox。
 *
 * @param x 左上角 X，从 0 开始
 * @param y 左上角 Y，从 0 开始
 * @param w 宽度，至少 1
 * @param h 高度，至少 1
 */
public record CitationBoundingBox(int x, int y, int w, int h) {

    /** 校验坐标边界，防止把坏数据反射给浏览器。 */
    public CitationBoundingBox {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("bbox x/y must be >= 0");
        }
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("bbox w/h must be >= 1");
        }
    }
}
