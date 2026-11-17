package com.riccon.worker.parse.service;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 文档解析器注册表。
 *
 * <p>按 {@link FileFormat} 路由到唯一实现；重复或缺失实现都在启动/调用时快速失败，
 * 不在运行期猜测格式。
 */
@Component
public class DocumentParserRegistry {

    /** 格式到解析器的不可变映射。 */
    private final Map<FileFormat, DocumentParser> parsers;

    /**
     * 创建注册表。
     *
     * @param parserList 解析器列表
     */
    public DocumentParserRegistry(List<DocumentParser> parserList) {
        Map<FileFormat, DocumentParser> map = new EnumMap<>(FileFormat.class);
        for (DocumentParser parser : parserList) {
            DocumentParser previous = map.put(parser.format(), parser);
            if (previous != null) {
                throw new IllegalStateException("duplicate document parser for format: " + parser.format());
            }
        }
        this.parsers = Map.copyOf(map);
    }

    /**
     * 解析文件内容。
     *
     * @param file 文件元数据
     * @param content 文件字节
     * @return 解析结果
     * @throws ParseException 没有对应解析器或内容不可解析
     */
    public ParsedContent parse(FileRecord file, byte[] content) {
        DocumentParser parser = parsers.get(file.format());
        if (parser == null) {
            throw ParseException.unprocessable("unsupported file format for parsing", null);
        }
        return parser.parse(file, content);
    }

    /**
     * 返回注册的格式数量，供启动日志与测试使用。
     *
     * @return 解析器数量
     */
    public int size() {
        return parsers.size();
    }
}
