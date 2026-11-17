package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.chat.LocatorType;

/**
 * 解析器输出的原始文本段，尚未按长度切分。
 *
 * <p>定位字段必须能回溯到原文：PDF 用页码、Excel 用工作表与单元格范围、Word 用段落序号；
 * 图片 M02 只输出文件名/格式等元数据，不做 OCR。
 *
 * @param content 文本内容
 * @param locatorType 定位方式
 * @param pageNo 页码，可为空
 * @param sheetName 工作表名，可为空
 * @param cellRange 单元格范围，可为空
 * @param paragraphNo 段落序号，可为空
 */
public record ParsedSegment(
        String content,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo) {}
