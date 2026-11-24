package com.riccon.worker.index.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 连接器纯文本（txt/md/markdown）抽取器。
 *
 * <p>连接器同步白名单允许 {@code txt}/{@code md}，但平台文件格式枚举与上传校验只覆盖
 * PDF/Office/图片；本类只服务连接器索引分支，不参与上传格式判定，也不注册为
 * {@code DocumentParser}，避免依赖 {@code FileFormat} 或放宽上传校验。
 *
 * <p>文本按 UTF-8 解码：非法字节替换为 U+FFFD，不抛未捕获异常；去掉开头 BOM，统一
 * {@code CRLF}/{@code CR} 为 {@code LF}；以空行分隔的文本块作为段落，交由调用方用既有
 * {@code TextChunker} 按部署上限切分。空内容或仅空白视为不可解析，抛出稳定错误码，
 * 避免生成空索引或静默成功。文本内容仍是不可信数据，不在此处触发任何工具调用。
 */
final class ConnectorPlainTextExtractor {

    /** 连接器文本对象支持的扩展名（小写）。 */
    private static final List<String> EXTENSIONS = List.of("txt", "md", "markdown");

    /** 段落切分：一个或多个空行；行内换行保留，供 TextChunker 优先断行。 */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n+");

    /** 纯文本抽取版本，用于 parse_engine 之外的排障定位。 */
    private static final String PARSER_VERSION = "m05b-b7b.1";

    /** 工具类禁止实例化。 */
    private ConnectorPlainTextExtractor() {}

    /**
     * 判断扩展名是否由本抽取器处理。
     *
     * @param extension 小写扩展名
     * @return 支持时返回 true
     */
    static boolean supports(String extension) {
        return EXTENSIONS.contains(extension);
    }

    /**
     * 把纯文本字节转换为带段落定位的解析结果。
     *
     * @param bytes 对象字节
     * @return 解析结果；段落按空行切分
     * @throws ParseException 内容为空或仅空白
     */
    static ParsedContent extract(byte[] bytes) {
        // UTF-8 解码使用替换模式：历史/错误编码文件不会导致未捕获异常或整篇文档崩溃。
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isBlank()) {
            throw ParseException.unprocessable("text document has no extractable text", null);
        }
        List<ParsedSegment> segments = new ArrayList<>();
        int paragraphNo = 1;
        for (String block : PARAGRAPH_BREAK.split(text)) {
            String part = block.strip();
            if (!part.isBlank()) {
                segments.add(new ParsedSegment(part, LocatorType.PARAGRAPH, null, null, null, paragraphNo++));
            }
        }
        return new ParsedContent(segments, null, null, PARSER_VERSION);
    }
}
