package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.chat.LocatorType;

/**
 * 已切分的文本块，写入 {@code file_chunk} 或 {@code knowledge_chunk}。
 *
 * @param content 块内容
 * @param locatorType 定位方式
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param tokenCount 估算 token 数（按字符数 / 4 向上取整）
 */
public record ParsedChunk(
        String content,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        int tokenCount) {}
