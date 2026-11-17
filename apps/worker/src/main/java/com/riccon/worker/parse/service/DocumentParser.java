package com.riccon.worker.parse.service;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;

/**
 * 文档解析器 SPI。
 *
 * <p>实现只做字节到文本段的转换，不访问数据库或对象存储；解析结果必须带可回溯的定位字段。
 */
public interface DocumentParser {

    /** M02 解析器版本，写入 file_parse.parser_version 便于失败复现。 */
    String PARSER_VERSION = "m02.1";

    /**
     * 返回支持的格式。
     *
     * @return 文件格式
     */
    FileFormat format();

    /**
     * 解析文件内容。
     *
     * @param file 文件元数据
     * @param content 文件字节；图片可为空
     * @return 解析结果
     * @throws com.riccon.worker.parse.domain.ParseException 内容损坏或不可解析
     */
    ParsedContent parse(FileRecord file, byte[] content);
}
