package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XLSX 解析器（ADR-0023 第 3 节）。
 *
 * <p>M05 起使用 {@link XSSFReader} + SAX 单次顺序读取工作表 XML，内存占用与文件行数解耦；
 * DOM 只保留在测试夹具中。每个工作表按“行带”（默认 20 行）输出文本段，定位字段保存
 * {@code sheetName} 与 {@code cellRange}（例如 {@code 销售数据!B2:D10}），供引用详情高亮。
 *
 * <p>使用自定义 SAX 处理器而不是 {@code XSSFSheetXMLHandler}，是因为 ADR-0023 要求数值按
 * {@link BigDecimal} 保留精度、日期使用固定格式，并区分“公式无缓存值”警告；这些信息在
 * {@code XSSFSheetXMLHandler} 的格式化回调中不可得。
 *
 * <p>安全与降级边界：只读取缓存值，不使用 {@code FormulaEvaluator}；隐藏工作表默认跳过并写白名单
 * 警告；ZIP 条目大小、压缩比、条目数与行列/字符/段落上限均 fail-closed 或截断并记录摘要。
 * 加密、损坏与非 OOXML 内容转换为 {@link ParseException} 稳定错误码，不向调用方泄漏堆栈。
 */
@Component
public class XlsxDocumentParser implements DocumentParser {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(XlsxDocumentParser.class);

    /** M05 流式解析器版本，写入 file_parse/knowledge_document 便于失败复现。 */
    private static final String XLSX_PARSER_VERSION = "m05.1";

    /** OOXML 包是 ZIP 容器，必须以此签名开始。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** OLE2/CFB 容器签名；旧版 .xls 与加密 OOXML 都使用该容器。 */
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    /** 单个 ZIP 条目解压后上限（ADR-0023 第 3.1 节）。 */
    private static final long ZIP_MAX_ENTRY_BYTES = 64L * 1024 * 1024;

    /** 解压文本上限；POI 5.5.1 仍保留该全局开关，按 ADR 显式收紧。 */
    private static final long ZIP_MAX_TEXT_SIZE = 256L * 1024 * 1024;

    /** 压缩比下限；低于该比例视为 ZIP 炸弹。 */
    private static final double ZIP_MIN_INFLATE_RATIO = 0.01d;

    /** ZIP 条目数量上限；POI 对应 API 名为 maxFileCount。 */
    private static final long ZIP_MAX_ENTRY_COUNT = 10_000L;

    /** 日期单元格固定展示格式；Excel 日期不带时区，按字段墙钟时间输出。 */
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    static {
        // POI 的 ZIP 上限是进程级静态值；启动时按 ADR-0023 收紧一次，避免每个解析器实例重复设置。
        applyZipLimits();
    }

    /** 解析上限与行带配置。 */
    private final Limits limits;

    /**
     * 默认构造器，使用 ADR-0023 的默认上限；测试与非 Spring 调用可直接使用。
     */
    public XlsxDocumentParser() {
        this(Limits.defaults());
    }

    /**
     * Spring 构造器：绑定 {@code RICCON_KB_XLSX_*} 环境变量（Spring relaxed binding）。
     *
     * <p>配置属性类位于 {@code com.riccon.worker.config.ParseProperties}，由 M05-B1/B5 维护；
     * B4 的写入范围不含配置类，因此在此处用局部占位符读取，避免跨任务修改共享配置。
     *
     * @param bandRows 行带大小
     * @param maxRows 单表最大行数
     * @param maxColumns 单表最大列数
     * @param maxSheets 最大索引工作表数
     * @param maxChars 单文档最大字符数
     * @param maxSegments 单文档最大段落数
     */
    @Autowired
    public XlsxDocumentParser(
            @Value("${riccon.kb.xlsx.band-rows:20}") int bandRows,
            @Value("${riccon.kb.xlsx.max-rows:20000}") int maxRows,
            @Value("${riccon.kb.xlsx.max-columns:200}") int maxColumns,
            @Value("${riccon.kb.xlsx.max-sheets:50}") int maxSheets,
            @Value("${riccon.kb.xlsx.max-chars:8000000}") int maxChars,
            @Value("${riccon.kb.xlsx.max-segments:5000}") int maxSegments) {
        this(new Limits(bandRows, maxRows, maxColumns, maxSheets, maxChars, maxSegments));
    }

    /**
     * 包内构造器：注入自定义上限，供测试锁定截断边界。
     *
     * @param limits 解析上限
     */
    XlsxDocumentParser(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /** 返回 XLSX 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.XLSX;
    }

    /**
     * 抽取 Excel 文本与结构摘要。
     *
     * @param file 文件元数据（解析器不读取对象存储，仅用于日志/版本语义）
     * @param content XLSX 字节
     * @return 解析结果，携带 POI 引擎、白名单警告与工作表摘要
     * @throws ParseException 内容加密、损坏、资源超限或没有可索引文本
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        validateContainer(content);
        Path staged = stage(content);
        try (OPCPackage pkg = OPCPackage.open(staged.toFile(), PackageAccess.READ)) {
            return parsePackage(pkg);
        } catch (ParseException ex) {
            throw ex;
        } catch (Exception ex) {
            // 不可信文档的 XML/OPC 解析异常一律折叠为稳定失败码；ParseException 已在上面透传。
            throw translate(ex);
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * 解析已打开的 OOXML 包。
     *
     * @param pkg OOXML 包
     * @return 解析结果
     * @throws IOException 读取包内容失败
     * @throws SAXException 工作表 XML 非法
     * @throws XMLStreamException workbook.xml 非法
     * @throws ParserConfigurationException SAX 解析器不可用
     * @throws OpenXML4JException OOXML 容器或关系非法
     */
    private ParsedContent parsePackage(OPCPackage pkg)
            throws IOException, SAXException, XMLStreamException, ParserConfigurationException, OpenXML4JException {
        XSSFReader reader = new XSSFReader(pkg);
        WorkbookInfo info = readWorkbookInfo(reader);
        StylesTable styles = reader.getStylesTable();
        ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
        ParseState state = new ParseState(limits, info.hiddenCount());
        if (info.hiddenCount() > 0) {
            state.warn(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, info.hiddenCount()));
        }

        XSSFReader.SheetIterator iterator = reader.getSheetIterator();
        int visibleIndex = 0;
        while (iterator.hasNext()) {
            try (InputStream sheetStream = iterator.next()) {
                String sheetName = iterator.getSheetName();
                if (info.hiddenSheets().getOrDefault(sheetName, Boolean.FALSE)) {
                    // 隐藏工作表整表跳过，不读取其 XML 流；警告只记数量，不含表内原文。
                    continue;
                }
                boolean indexContent = visibleIndex < limits.maxSheets();
                if (!indexContent) {
                    state.warn(ParseWarningCodes.SHEETS_TRUNCATED);
                }
                SheetCollector collector = new SheetCollector(sheetName, indexContent, limits, state);
                parseSheetXml(
                        sheetStream, styles, sharedStrings, info.use1904(), collector, limits.maxChars());
                collector.endSheet();
                state.addSheet(collector.summary());
                visibleIndex++;
            }
        }

        if (state.segments().isEmpty()) {
            throw new ParseException(ParseErrorCodes.NO_TEXT, false, "xlsx workbook has no indexable text", null);
        }
        return new ParsedContent(
                state.segments(),
                null,
                info.sheets().size(),
                XLSX_PARSER_VERSION,
                ParseEngine.POI,
                state.warnings(),
                state.summary());
    }

    /**
     * 读取 workbook.xml 的工作表顺序、隐藏状态与 1904 日期窗口。
     *
     * <p>该文件很小；XSSFReader 的 SheetIterator 不暴露隐藏状态，因此单独用安全的 StAX
     * 读取一次。禁止为查可见性把整本 workbook 载入 DOM。
     *
     * @param reader 流式读取器
     * @return 工作簿元数据
     * @throws IOException 读取失败
     * @throws XMLStreamException XML 非法
     * @throws OpenXML4JException workbook.xml 关系非法
     */
    private static WorkbookInfo readWorkbookInfo(XSSFReader reader)
            throws IOException, XMLStreamException, OpenXML4JException {
        List<WorkbookSheet> sheets = new ArrayList<>();
        boolean use1904 = false;
        try (InputStream stream = reader.getWorkbookData()) {
            XMLStreamReader xml = XMLHelper.newXMLInputFactory().createXMLStreamReader(stream);
            try {
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String element = xml.getLocalName();
                    if ("workbookPr".equals(element)) {
                        String flag = xml.getAttributeValue(null, "date1904");
                        use1904 = "1".equals(flag) || "true".equalsIgnoreCase(flag);
                    } else if ("sheet".equals(element)) {
                        String name = xml.getAttributeValue(null, "name");
                        if (name != null && !name.isBlank()) {
                            String state = xml.getAttributeValue(null, "state");
                            boolean hidden = "hidden".equalsIgnoreCase(state) || "veryHidden".equalsIgnoreCase(state);
                            sheets.add(new WorkbookSheet(name, hidden));
                        }
                    }
                }
            } finally {
                xml.close();
            }
        }

        int hiddenCount = 0;
        Map<String, Boolean> hiddenByName = new HashMap<>();
        for (WorkbookSheet sheet : sheets) {
            hiddenByName.put(sheet.name(), sheet.hidden());
            if (sheet.hidden()) {
                hiddenCount++;
            }
        }
        return new WorkbookInfo(sheets, hiddenByName, hiddenCount, use1904);
    }

    /**
     * 用 SAX 解析单个工作表 XML。
     *
     * @param sheetStream 工作表 XML 流
     * @param styles 样式表
     * @param sharedStrings 共享字符串表
     * @param use1904 是否使用 1904 日期窗口
     * @param collector 工作表收集器
     * @param maxCellChars 单单元格文本缓存上限
     * @throws IOException 读取失败
     * @throws SAXException XML 非法
     * @throws ParserConfigurationException SAX 解析器不可用
     */
    private static void parseSheetXml(
            InputStream sheetStream,
            StylesTable styles,
            ReadOnlySharedStringsTable sharedStrings,
            boolean use1904,
            SheetCollector collector,
            int maxCellChars)
            throws IOException, SAXException, ParserConfigurationException {
        XMLReader xmlReader = XMLHelper.newXMLReader();
        xmlReader.setContentHandler(
                new SheetXmlHandler(styles, sharedStrings, use1904, collector, maxCellChars));
        xmlReader.parse(new InputSource(sheetStream));
    }

    /**
     * 校验容器签名，区分加密、旧版二进制与普通损坏。
     *
     * @param content 文件字节
     * @throws ParseException 加密、旧版 .xls 或非 ZIP 内容
     */
    private static void validateContainer(byte[] content) {
        if (content == null || content.length < OLE2_MAGIC.length) {
            throw corrupt("xlsx content is empty");
        }
        if (startsWith(content, OLE2_MAGIC)) {
            if (looksEncrypted(content)) {
                throw new ParseException(
                        ParseErrorCodes.PARSE_ENCRYPTED,
                        false,
                        "xlsx document is encrypted or password protected",
                        null);
            }
            // 旧版 .xls 与加密 OOXML 同为 OLE2；不含 EncryptedPackage 时按不支持的二进制内容处理。
            throw corrupt("legacy binary Excel content is not supported");
        }
        if (!startsWith(content, ZIP_MAGIC)) {
            throw corrupt("xlsx content is not an OOXML package");
        }
    }

    /**
     * 判断 OLE2 容器是否携带 OOXML 加密流。
     *
     * @param content OLE2 字节
     * @return 加密 OOXML 返回 true；目录损坏或普通 .xls 返回 false
     */
    private static boolean looksEncrypted(byte[] content) {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            DirectoryEntry root = fileSystem.getRoot();
            return root.hasEntry("EncryptedPackage") || root.hasEntry("EncryptionInfo");
        } catch (IOException | RuntimeException ex) {
            // OLE2 目录本身损坏时按普通损坏文件处理；技术异常不进入 API 文案。
            return false;
        }
    }

    /**
     * 判断字节是否以指定前缀开始。
     *
     * @param content 内容
     * @param prefix 前缀
     * @return 匹配返回 true
     */
    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把字节写入临时文件，供 POI 以随机访问的 ZipFile 方式按需读取条目。
     *
     * <p>直接 {@code OPCPackage.open(InputStream)} 会一次性解压所有条目到内存/临时文件；
     * 落盘后使用 {@code open(File)} 才能保持“工作表按需流式读取”。文件在 finally 中删除；
     * POSIX 下临时文件默认权限为 {@code rw-------}，不向同机其他用户暴露文档内容。
     *
     * @param content 文件字节
     * @return 临时文件路径
     */
    private static Path stage(byte[] content) {
        try {
            Path staged = Files.createTempFile("riccon-xlsx-", ".xlsx");
            Files.write(staged, content);
            return staged;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to stage xlsx content", ex);
        }
    }

    /**
     * 尽力删除临时文件；失败只记录告警，不覆盖解析结果。
     *
     * @param staged 临时文件
     */
    private static void deleteQuietly(Path staged) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException ex) {
            LOG.warn("xlsx.temp_cleanup_failed", ex);
        }
    }

    /**
     * 把 POI 异常折叠为稳定解析错误码。
     *
     * @param ex 解析异常
     * @return 可上抛的解析失败
     */
    private static ParseException translate(Exception ex) {
        if (isResourceLimit(ex)) {
            return new ParseException(
                    ParseErrorCodes.PARSE_RESOURCE_LIMIT, false, "xlsx document exceeds ZIP resource limits", ex);
        }
        if (ex instanceof NotOfficeXmlFileException) {
            return corrupt("xlsx content is not an OOXML workbook");
        }
        return corrupt("xlsx document is corrupt or unreadable");
    }

    /**
     * 沿异常链识别 POI 的 ZIP 资源限制错误。
     *
     * @param ex 异常
     * @return 命中资源限制返回 true
     */
    private static boolean isResourceLimit(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("Zip bomb detected")
                            || message.contains("potentially malicious")
                            || message.contains("MAX_FILE_COUNT")
                            || message.contains("ZIP entry size is too large"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造损坏文档失败。
     *
     * @param message 稳定脱敏原因
     * @return 不可重试解析失败
     */
    private static ParseException corrupt(String message) {
        return new ParseException(ParseErrorCodes.PARSE_CORRUPT, false, message, null);
    }

    /**
     * 应用 ADR-0023 第 3.1 节的 ZIP 资源上限。
     *
     * <p>POI 的这些开关是进程级静态值；类加载时设置一次。上限收紧不会放宽既有安全基线。
     */
    private static void applyZipLimits() {
        ZipSecureFile.setMaxEntrySize(ZIP_MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(ZIP_MAX_TEXT_SIZE);
        ZipSecureFile.setMinInflateRatio(ZIP_MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxFileCount(ZIP_MAX_ENTRY_COUNT);
    }

    /**
     * 解析器上限配置（ADR-0023 第 3.2/3.3 节）。
     *
     * @param bandRows 每个文本段的固定行数
     * @param maxRows 单表最多索引行数
     * @param maxColumns 单表最多索引列数
     * @param maxSheets 单文档最多索引工作表数
     * @param maxChars 单文档最多索引字符数
     * @param maxSegments 单文档最多段落数
     */
    record Limits(int bandRows, int maxRows, int maxColumns, int maxSheets, int maxChars, int maxSegments) {

        /** 校验上限，避免配置错误导致无界扫描或无法前进。 */
        Limits {
            if (bandRows < 1 || bandRows > 10_000) {
                throw new IllegalArgumentException("riccon.kb.xlsx.band-rows must be in [1, 10000]");
            }
            if (maxRows < 1 || maxRows > 1_048_576) {
                throw new IllegalArgumentException("riccon.kb.xlsx.max-rows must be in [1, 1048576]");
            }
            if (maxColumns < 1 || maxColumns > 16_384) {
                throw new IllegalArgumentException("riccon.kb.xlsx.max-columns must be in [1, 16384]");
            }
            if (maxSheets < 1) {
                throw new IllegalArgumentException("riccon.kb.xlsx.max-sheets must be positive");
            }
            if (maxChars < 1) {
                throw new IllegalArgumentException("riccon.kb.xlsx.max-chars must be positive");
            }
            if (maxSegments < 1) {
                throw new IllegalArgumentException("riccon.kb.xlsx.max-segments must be positive");
            }
        }

        /**
         * ADR-0023 默认值。
         *
         * @return 默认上限
         */
        static Limits defaults() {
            return new Limits(20, 20_000, 200, 50, 8_000_000, 5_000);
        }
    }

    /**
     * 工作簿结构元数据。
     *
     * @param sheets 按 workbook.xml 顺序的全部工作表
     * @param hiddenSheets 工作表名到隐藏状态的映射
     * @param hiddenCount 隐藏工作表数量
     * @param use1904 是否使用 1904 日期窗口
     */
    private record WorkbookInfo(
            List<WorkbookSheet> sheets, Map<String, Boolean> hiddenSheets, int hiddenCount, boolean use1904) {

        /** 防御性复制集合。 */
        WorkbookInfo {
            sheets = List.copyOf(sheets);
            hiddenSheets = Map.copyOf(hiddenSheets);
        }
    }

    /**
     * workbook.xml 中的单个工作表条目。
     *
     * @param name 工作表名
     * @param hidden 是否隐藏（hidden/veryHidden）
     */
    private record WorkbookSheet(String name, boolean hidden) {}

    /**
     * 单次文档解析的可变状态：段落、警告、工作表摘要与文档级范围。
     */
    private static final class ParseState {

        /** 解析上限。 */
        private final Limits limits;

        /** 已生成文本段。 */
        private final List<ParsedSegment> segments = new ArrayList<>();

        /** 白名单警告，按首次出现顺序去重。 */
        private final LinkedHashSet<String> warnings = new LinkedHashSet<>();

        /** 工作表摘要。 */
        private final List<ParseSummary.SheetSummary> sheets = new ArrayList<>();

        /** 已索引文档级范围。 */
        private final List<String> indexedRanges = new ArrayList<>();

        /** 未索引文档级范围。 */
        private final List<String> skippedRanges = new ArrayList<>();

        /** 隐藏工作表数量。 */
        private final int hiddenSheetCount;

        /** 已索引字符数。 */
        private int totalChars;

        /** 字符上限是否触发。 */
        private boolean charsTruncated;

        /** 段落上限是否触发。 */
        private boolean segmentsTruncated;

        /**
         * 创建解析状态。
         *
         * @param limits 解析上限
         * @param hiddenSheetCount 隐藏工作表数量
         */
        ParseState(Limits limits, int hiddenSheetCount) {
            this.limits = limits;
            this.hiddenSheetCount = hiddenSheetCount;
        }

        /**
         * 是否已停止生成新段落。
         *
         * @return 字符或段落达到上限返回 true
         */
        boolean indexingStopped() {
            return totalChars >= limits.maxChars() || segments.size() >= limits.maxSegments();
        }

        /**
         * 返回剩余可索引字符数。
         *
         * @return 非负剩余字符数
         */
        int remainingChars() {
            return Math.max(0, limits.maxChars() - totalChars);
        }

        /**
         * 记录因上限而跳过内容，并按已达到的上限补警告。
         */
        void noteCapReached() {
            if (totalChars >= limits.maxChars()) {
                noteCharsTruncated();
            }
            if (segments.size() >= limits.maxSegments()) {
                noteSegmentsTruncated();
            }
        }

        /** 记录字符截断警告（只记一次）。 */
        void noteCharsTruncated() {
            if (!charsTruncated) {
                charsTruncated = true;
                warn(ParseWarningCodes.CHARS_TRUNCATED);
            }
        }

        /** 记录段落截断警告（只记一次）。 */
        void noteSegmentsTruncated() {
            if (!segmentsTruncated) {
                segmentsTruncated = true;
                warn(ParseWarningCodes.SEGMENTS_TRUNCATED);
            }
        }

        /**
         * 累加已索引字符数。
         *
         * @param count 字符数
         */
        void addChars(int count) {
            totalChars += count;
        }

        /**
         * 追加文本段。
         *
         * @param segment 文本段
         */
        void addSegment(ParsedSegment segment) {
            segments.add(segment);
        }

        /**
         * 追加白名单警告。
         *
         * @param warning 警告码
         */
        void warn(String warning) {
            warnings.add(warning);
        }

        /**
         * 追加工作表摘要及其文档级范围。
         *
         * @param summary 工作表摘要
         */
        void addSheet(ParseSummary.SheetSummary summary) {
            sheets.add(summary);
            if (summary.indexedRange() != null) {
                indexedRanges.add(summary.sheetName() + "!" + summary.indexedRange());
            }
            if (summary.skippedRange() != null) {
                skippedRanges.add(summary.sheetName() + "!" + summary.skippedRange());
            }
        }

        /** 返回已生成文本段。 */
        List<ParsedSegment> segments() {
            return segments;
        }

        /** 返回不可变警告列表。 */
        List<String> warnings() {
            return List.copyOf(warnings);
        }

        /** 构造结构摘要。 */
        ParseSummary summary() {
            return new ParseSummary(sheets, null, hiddenSheetCount, indexedRanges, skippedRanges);
        }
    }

    /**
     * 工作表 XML 的 SAX 事件处理器。
     *
     * <p>只读取 {@code <v>} 缓存值，不实例化 {@code FormulaEvaluator}；共享字符串与样式分别由
     * {@link ReadOnlySharedStringsTable} 和 {@link StylesTable} 提供。所有单行状态在 {@code </row>}
     * 时提交，确保内存只与单行宽度相关。
     */
    private static final class SheetXmlHandler extends DefaultHandler {

        /** 样式表。 */
        private final StylesTable styles;

        /** 共享字符串表。 */
        private final ReadOnlySharedStringsTable sharedStrings;

        /** 是否使用 1904 日期窗口。 */
        private final boolean use1904;

        /** 工作表收集器。 */
        private final SheetCollector collector;

        /** 单单元格文本缓存上限；防止超长单元格在 SAX 回调中先撑爆堆。 */
        private final int maxCellChars;

        /** 下一行的隐式索引。 */
        private int nextRowNum;

        /** 当前行索引（0 基）。 */
        private int rowNum = -1;

        /** 当前行是否已开始。 */
        private boolean rowOpen;

        /** 下一列的隐式索引。 */
        private int nextColNum;

        /** 当前单元格列索引。 */
        private int cellCol;

        /** 当前单元格样式索引；-1 表示无样式。 */
        private int cellStyleIndex = -1;

        /** 当前单元格类型属性。 */
        private String cellType;

        /** 当前单元格是否含公式。 */
        private boolean cellHasFormula;

        /** 当前单元格是否含 {@code <v>} 缓存值。 */
        private boolean cellHasValue;

        /** 当前单元格是否含内联字符串。 */
        private boolean cellHasInline;

        /** 当前单元格 {@code <v>} 文本。 */
        private final StringBuilder cellValue = new StringBuilder();

        /** 当前单元格内联字符串文本。 */
        private final StringBuilder cellInline = new StringBuilder();

        /** 是否位于 {@code <v>} 内。 */
        private boolean valueOpen;

        /** 是否位于内联字符串 {@code <t>} 内。 */
        private boolean textOpen;

        /** 拼音/注音文本深度；该区域不进入正文。 */
        private int phoneticDepth;

        /** 当前单元格是否因单格上限被截断。 */
        private boolean cellTruncated;

        /**
         * 创建处理器。
         *
         * @param styles 样式表
         * @param sharedStrings 共享字符串表
         * @param use1904 是否使用 1904 日期窗口
         * @param collector 工作表收集器
         * @param maxCellChars 单单元格文本缓存上限
         */
        SheetXmlHandler(
                StylesTable styles,
                ReadOnlySharedStringsTable sharedStrings,
                boolean use1904,
                SheetCollector collector,
                int maxCellChars) {
            this.styles = styles;
            this.sharedStrings = sharedStrings;
            this.use1904 = use1904;
            this.collector = collector;
            this.maxCellChars = maxCellChars;
        }

        /** {@inheritDoc} */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "row" -> startRow(attributes);
                case "c" -> startCell(attributes);
                case "f" -> cellHasFormula = true;
                case "v" -> {
                    cellHasValue = true;
                    valueOpen = true;
                    cellValue.setLength(0);
                }
                case "is" -> {
                    cellHasInline = true;
                    cellInline.setLength(0);
                }
                case "t" -> {
                    if (cellHasInline) {
                        textOpen = true;
                    }
                }
                case "rPh" -> phoneticDepth++;
                default -> {
                    // 其他工作表元素（样式、合并、页眉页脚等）不进入正文。
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v" -> valueOpen = false;
                case "t" -> textOpen = false;
                case "rPh" -> {
                    if (phoneticDepth > 0) {
                        phoneticDepth--;
                    }
                }
                case "c" -> finishCell();
                case "row" -> {
                    if (rowOpen) {
                        collector.endRow(rowNum);
                        rowOpen = false;
                    }
                }
                default -> {
                    // 非正文元素无需处理。
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public void characters(char[] chars, int start, int length) {
            if (valueOpen) {
                appendCapped(cellValue, chars, start, length);
            }
            if (textOpen && phoneticDepth == 0) {
                appendCapped(cellInline, chars, start, length);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void endDocument() {
            collector.endSheet();
        }

        /**
         * 开始一行。
         *
         * @param attributes 行属性
         */
        private void startRow(Attributes attributes) {
            if (rowOpen) {
                // 防御未闭合的上一行，避免文本串到下一行。
                collector.endRow(rowNum);
            }
            String rowReference = attributes.getValue("r");
            int parsed = rowReference == null ? nextRowNum : parseIndex(rowReference);
            // OOXML 行号从 1 开始；内部统一用 0 基索引，与 cellRange 输出一致。
            rowNum = parsed <= 0 ? nextRowNum : parsed - 1;
            nextRowNum = rowNum + 1;
            nextColNum = 0;
            rowOpen = true;
        }

        /**
         * 开始一个单元格并重置单格状态。
         *
         * @param attributes 单元格属性
         */
        private void startCell(Attributes attributes) {
            cellHasFormula = false;
            cellHasValue = false;
            cellHasInline = false;
            valueOpen = false;
            textOpen = false;
            phoneticDepth = 0;
            cellTruncated = false;
            cellValue.setLength(0);
            cellInline.setLength(0);
            cellType = attributes.getValue("t");
            cellStyleIndex = parseIndex(attributes.getValue("s"));
            String reference = attributes.getValue("r");
            cellCol = reference == null ? nextColNum : columnIndex(reference);
            nextColNum = Math.max(nextColNum, cellCol + 1);
        }

        /**
         * 结束单元格并把非空文本交给收集器。
         */
        private void finishCell() {
            if (!rowOpen || rowNum < 0) {
                return;
            }
            if (cellTruncated) {
                collector.noteCellTruncated();
            }
            String rendered = renderCellValue();
            if (rendered == null) {
                if (cellHasFormula && !cellHasValue) {
                    // 公式只读缓存值；没有缓存值时跳过并记录白名单警告，绝不算公式。
                    collector.formulaNotCached(rowNum, cellCol);
                }
                return;
            }
            String value = rendered.strip();
            if (!value.isEmpty()) {
                collector.cell(rowNum, cellCol, value);
            }
        }

        /**
         * 按单元格类型渲染文本。
         *
         * @return 可读文本；错误值、空值或非法共享字符串返回 null
         */
        private String renderCellValue() {
            String type = cellType == null ? "n" : cellType;
            return switch (type) {
                case "e" -> null;
                case "s" -> sharedStringValue();
                case "inlineStr" -> cellHasInline ? cellInline.toString() : null;
                case "b" -> booleanValue();
                case "str" -> cellHasValue ? cellValue.toString() : null;
                case "d" -> isoDateValue();
                default -> numericValue();
            };
        }

        /**
         * 解析共享字符串。
         *
         * @return 字符串文本；索引非法返回 null
         */
        private String sharedStringValue() {
            String raw = cellValue.toString().strip();
            if (raw.isEmpty() || sharedStrings == null) {
                return null;
            }
            try {
                int index = Integer.parseInt(raw);
                if (index < 0 || index >= sharedStrings.getCount()) {
                    return null;
                }
                var item = sharedStrings.getItemAt(index);
                return item == null ? null : item.toString();
            } catch (NumberFormatException ex) {
                // 非法共享字符串索引视为损坏单元格，跳过而不是中断整表。
                return null;
            }
        }

        /**
         * 解析布尔值。
         *
         * @return TRUE/FALSE；其他值返回 null
         */
        private String booleanValue() {
            String raw = cellValue.toString().strip();
            if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
                return "TRUE";
            }
            if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) {
                return "FALSE";
            }
            return null;
        }

        /**
         * 解析 ISO 8601 日期类型单元格。
         *
         * @return 固定格式日期；无法解析时返回原始文本
         */
        private String isoDateValue() {
            String raw = cellValue.toString().strip();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return LocalDateTime.parse(raw).format(DATE_TIME_FORMAT);
            } catch (DateTimeParseException ignored) {
                // 带偏移的 ISO 时间由下面的分支处理。
            }
            try {
                return OffsetDateTime.parse(raw).toLocalDateTime().format(DATE_TIME_FORMAT);
            } catch (DateTimeParseException ignored) {
                // 日期型单元格可能只带日期，下面按当日零点补齐固定格式。
            }
            try {
                return LocalDate.parse(raw).atStartOfDay().format(DATE_TIME_FORMAT);
            } catch (DateTimeParseException ex) {
                return raw;
            }
        }

        /**
         * 渲染数值或公式缓存值。
         *
         * @return 数值文本；空值返回 null
         */
        private String numericValue() {
            if (!cellHasValue) {
                return null;
            }
            String raw = cellValue.toString().strip();
            if (raw.isEmpty()) {
                return null;
            }
            XSSFCellStyle style = style();
            if (style != null && isDateStyle(style)) {
                try {
                    LocalDateTime dateTime = DateUtil.getLocalDateTime(Double.parseDouble(raw), use1904);
                    if (dateTime != null) {
                        return dateTime.format(DATE_TIME_FORMAT);
                    }
                } catch (NumberFormatException ex) {
                    // 公式字符串结果命中日期样式时回退为原始文本，避免丢失值。
                }
            }
            try {
                // 保留单元格存储精度；显示样式不参与数值截断。
                return new BigDecimal(raw).toPlainString();
            } catch (NumberFormatException ex) {
                return raw;
            }
        }

        /**
         * 有上限地追加字符，避免单个超长单元格在进入行带前占用无界内存。
         *
         * @param builder 目标缓冲
         * @param chars SAX 字符块
         * @param start 起始下标
         * @param length 长度
         */
        private void appendCapped(StringBuilder builder, char[] chars, int start, int length) {
            if (length <= 0) {
                return;
            }
            int remaining = maxCellChars - builder.length();
            if (remaining <= 0) {
                cellTruncated = true;
                return;
            }
            int take = Math.min(length, remaining);
            builder.append(chars, start, take);
            if (take < length) {
                cellTruncated = true;
            }
        }

        /**
         * 返回当前单元格样式。
         *
         * @return 样式；无样式或索引越界返回 null
         */
        private XSSFCellStyle style() {
            if (styles == null) {
                return null;
            }
            int index = cellStyleIndex >= 0 ? cellStyleIndex : 0;
            if (index >= styles.getNumCellStyles()) {
                return null;
            }
            return styles.getStyleAt(index);
        }

        /**
         * 判断样式是否为日期/时间格式。
         *
         * @param style 样式
         * @return 日期格式返回 true
         */
        private static boolean isDateStyle(XSSFCellStyle style) {
            int formatIndex = style.getDataFormat();
            String formatString = style.getDataFormatString();
            if (formatString == null) {
                formatString = BuiltinFormats.getBuiltinFormat(formatIndex);
            }
            if (formatString == null) {
                return false;
            }
            return DateUtil.isADateFormat(formatIndex, formatString);
        }

        /**
         * 解析整数属性；非法值按 -1 处理。
         *
         * @param value 属性值
         * @return 整数或 -1
         */
        private static int parseIndex(String value) {
            if (value == null || value.isBlank()) {
                return -1;
            }
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException ex) {
                return -1;
            }
        }

        /**
         * 从单元格引用提取 0 基列索引。
         *
         * @param reference 例如 B12
         * @return 列索引；无字母返回 0
         */
        private static int columnIndex(String reference) {
            int column = 0;
            for (int index = 0; index < reference.length(); index++) {
                char ch = Character.toUpperCase(reference.charAt(index));
                if (ch < 'A' || ch > 'Z') {
                    break;
                }
                column = column * 26 + (ch - 'A' + 1);
            }
            return Math.max(column - 1, 0);
        }
    }

    /**
     * 单工作表收集器：把行流聚合为行带段落，并计算已索引/未索引范围。
     */
    private static final class SheetCollector {

        /** 工作表名（原样保存）。 */
        private final String sheetName;

        /** 是否索引内容；false 时只扫描范围（用于工作表数超限）。 */
        private final boolean indexContent;

        /** 解析上限。 */
        private final Limits limits;

        /** 文档级状态。 */
        private final ParseState state;

        /** 当前行索引；-1 表示无未提交行。 */
        private int currentRow = -1;

        /** 当前行的非空单元格文本。 */
        private final List<String> rowValues = new ArrayList<>();

        /** 当前行最小列索引。 */
        private int rowMinCol = Integer.MAX_VALUE;

        /** 当前行最大列索引。 */
        private int rowMaxCol = -1;

        /** 首个有内容行（0 基）。 */
        private int firstContentRow = -1;

        /** 最后有内容行（0 基）。 */
        private int lastContentRow = -1;

        /** 首个有内容列（0 基）。 */
        private int firstContentCol = Integer.MAX_VALUE;

        /** 最后有内容列（0 基）。 */
        private int lastContentCol = -1;

        /** 是否有任何内容（含被上限排除的内容）。 */
        private boolean hasContent;

        /** 已生成段落的最小行。 */
        private int indexedMinRow = -1;

        /** 已生成段落的最大行。 */
        private int indexedMaxRow = -1;

        /** 已生成段落的最小列。 */
        private int indexedMinCol = Integer.MAX_VALUE;

        /** 已生成段落的最大列。 */
        private int indexedMaxCol = -1;

        /** 是否存在未索引内容。 */
        private boolean skippedContent;

        /** 未索引内容的最大行。 */
        private int skippedMaxRow = -1;

        /** 未索引内容的最大列。 */
        private int skippedMaxCol = -1;

        /** 是否触发行数上限。 */
        private boolean rowsTruncated;

        /** 是否触发列数上限。 */
        private boolean columnsTruncated;

        /** 当前行带起始行（0 基）。 */
        private int bandStartRow = -1;

        /** 当前行带最小列。 */
        private int bandMinCol = Integer.MAX_VALUE;

        /** 当前行带最大列。 */
        private int bandMaxCol = -1;

        /** 当前行带最后纳入的行。 */
        private int bandLastIncludedRow = -1;

        /** 当前行带文本。 */
        private final StringBuilder bandText = new StringBuilder();

        /** 是否已结束。 */
        private boolean finished;

        /**
         * 创建工作表收集器。
         *
         * @param sheetName 工作表名
         * @param indexContent 是否索引内容
         * @param limits 解析上限
         * @param state 文档级状态
         */
        SheetCollector(String sheetName, boolean indexContent, Limits limits, ParseState state) {
            this.sheetName = sheetName;
            this.indexContent = indexContent;
            this.limits = limits;
            this.state = state;
        }

        /**
         * 接收一个非空单元格。
         *
         * @param rowNum 行索引
         * @param col 列索引
         * @param value 单元格文本
         */
        void cell(int rowNum, int col, String value) {
            markContent(rowNum, col);
            if (!indexContent) {
                return;
            }
            if (state.indexingStopped()) {
                markSkipped(rowNum, col);
                state.noteCapReached();
                return;
            }
            int rowLimit = firstContentRow + limits.maxRows();
            if (rowNum >= rowLimit) {
                rowsTruncated = true;
                markSkipped(rowNum, col);
                state.warn(ParseWarningCodes.withSuffix(ParseWarningCodes.ROWS_TRUNCATED, sheetName));
                return;
            }
            if (col >= limits.maxColumns()) {
                columnsTruncated = true;
                markSkipped(rowNum, col);
                state.warn(ParseWarningCodes.withSuffix(ParseWarningCodes.COLUMNS_TRUNCATED, sheetName));
                return;
            }
            if (rowNum != currentRow) {
                flushRow();
                currentRow = rowNum;
                rowValues.clear();
                rowMinCol = Integer.MAX_VALUE;
                rowMaxCol = -1;
            }
            rowValues.add(value);
            rowMinCol = Math.min(rowMinCol, col);
            rowMaxCol = Math.max(rowMaxCol, col);
        }

        /** 单个单元格文本因单格上限被截断。 */
        void noteCellTruncated() {
            if (indexContent && !state.indexingStopped()) {
                state.noteCharsTruncated();
            }
        }

        /**
         * 公式单元格没有缓存值；跳过并记录警告。
         *
         * @param rowNum 行索引
         * @param col 列索引
         */
        void formulaNotCached(int rowNum, int col) {
            markContent(rowNum, col);
            if (!indexContent || state.indexingStopped()) {
                return;
            }
            if (rowNum >= firstContentRow + limits.maxRows() || col >= limits.maxColumns()) {
                return;
            }
            state.warn(ParseWarningCodes.FORMULA_NOT_CACHED);
        }

        /**
         * 结束一行。
         *
         * @param rowNum 行索引
         */
        void endRow(int rowNum) {
            if (currentRow >= 0 && rowNum >= currentRow) {
                flushRow();
            }
        }

        /** 结束工作表；幂等，重复调用不重复输出。 */
        void endSheet() {
            if (finished) {
                return;
            }
            flushRow();
            flushBand();
            finished = true;
        }

        /**
         * 构造工作表摘要。
         *
         * @return 工作表摘要
         */
        ParseSummary.SheetSummary summary() {
            endSheet();
            if (!indexContent) {
                String range =
                        hasContent ? cellRange(firstContentRow, lastContentRow, firstContentCol, lastContentCol) : null;
                int rowCount = hasContent ? lastContentRow - firstContentRow + 1 : 0;
                int columnCount = hasContent ? lastContentCol - firstContentCol + 1 : 0;
                return new ParseSummary.SheetSummary(sheetName, rowCount, columnCount, !hasContent, null, range);
            }
            int rowCount = indexedMaxRow >= 0 ? indexedMaxRow - indexedMinRow + 1 : 0;
            int columnCount = indexedMaxCol >= 0 ? indexedMaxCol - indexedMinCol + 1 : 0;
            String indexedRange =
                    indexedMaxRow >= 0 ? cellRange(indexedMinRow, indexedMaxRow, indexedMinCol, indexedMaxCol) : null;
            return new ParseSummary.SheetSummary(
                    sheetName, rowCount, columnCount, !hasContent, indexedRange, skippedRange());
        }

        /**
         * 标记单元格内容存在；即使超出索引上限也要记录，供“空表/未索引范围”判断。
         *
         * @param rowNum 行索引
         * @param col 列索引
         */
        private void markContent(int rowNum, int col) {
            hasContent = true;
            if (firstContentRow < 0) {
                firstContentRow = rowNum;
                firstContentCol = col;
            }
            lastContentRow = Math.max(lastContentRow, rowNum);
            lastContentCol = Math.max(lastContentCol, col);
        }

        /**
         * 标记被上限排除的单元格。
         *
         * @param rowNum 行索引
         * @param col 列索引
         */
        private void markSkipped(int rowNum, int col) {
            skippedContent = true;
            skippedMaxRow = Math.max(skippedMaxRow, rowNum);
            skippedMaxCol = Math.max(skippedMaxCol, col);
        }

        /** 提交当前行到行带。 */
        private void flushRow() {
            if (currentRow < 0) {
                return;
            }
            int row = currentRow;
            List<String> values = List.copyOf(rowValues);
            int minCol = rowMinCol;
            int maxCol = rowMaxCol;
            currentRow = -1;
            rowValues.clear();
            rowMinCol = Integer.MAX_VALUE;
            rowMaxCol = -1;
            if (values.isEmpty()) {
                return;
            }
            if (state.indexingStopped()) {
                markSkipped(row, Math.max(minCol, 0));
                state.noteCapReached();
                return;
            }
            if (bandStartRow < 0 || row >= bandStartRow + limits.bandRows()) {
                flushBand();
                int bandIndex = Math.max(0, (row - firstContentRow) / limits.bandRows());
                bandStartRow = firstContentRow + bandIndex * limits.bandRows();
                bandMinCol = Integer.MAX_VALUE;
                bandMaxCol = -1;
                bandLastIncludedRow = -1;
                bandText.setLength(0);
            }

            String line = String.join(" | ", values);
            int separator = bandText.isEmpty() ? 0 : 1;
            int remaining = state.remainingChars();
            int available = remaining - bandText.length() - separator;
            if (available <= 0) {
                state.noteCharsTruncated();
                markSkipped(row, Math.max(maxCol, minCol));
                return;
            }
            if (line.length() > available) {
                int take = available;
                if (take > 0 && Character.isHighSurrogate(line.charAt(take - 1))) {
                    take--;
                }
                if (separator > 0 && take > 0) {
                    bandText.append('\n');
                }
                if (take > 0) {
                    bandText.append(line, 0, take);
                    bandLastIncludedRow = row;
                    bandMinCol = Math.min(bandMinCol, minCol);
                    bandMaxCol = Math.max(bandMaxCol, maxCol);
                }
                state.noteCharsTruncated();
                markSkipped(row, Math.max(maxCol, minCol));
                return;
            }
            if (separator > 0) {
                bandText.append('\n');
            }
            bandText.append(line);
            bandLastIncludedRow = row;
            bandMinCol = Math.min(bandMinCol, minCol);
            bandMaxCol = Math.max(bandMaxCol, maxCol);
        }

        /** 提交当前行带为文本段。 */
        private void flushBand() {
            if (bandText.isEmpty() || bandLastIncludedRow < 0) {
                resetBand();
                return;
            }
            String text = bandText.toString().strip();
            if (text.isEmpty()) {
                resetBand();
                return;
            }
            if (state.segments().size() >= limits.maxSegments()) {
                state.noteSegmentsTruncated();
                markSkippedRange();
                resetBand();
                return;
            }
            String range = cellRange(bandStartRow, bandLastIncludedRow, bandMinCol, bandMaxCol);
            state.addSegment(new ParsedSegment(text, LocatorType.SHEET, null, sheetName, range, null));
            state.addChars(text.length());
            indexedMinRow = indexedMinRow < 0 ? bandStartRow : Math.min(indexedMinRow, bandStartRow);
            indexedMaxRow = Math.max(indexedMaxRow, bandLastIncludedRow);
            indexedMinCol = Math.min(indexedMinCol, bandMinCol);
            indexedMaxCol = Math.max(indexedMaxCol, bandMaxCol);
            resetBand();
        }

        /** 把当前行带标记为未索引。 */
        private void markSkippedRange() {
            skippedContent = true;
            skippedMaxRow = Math.max(skippedMaxRow, bandLastIncludedRow);
            if (bandMaxCol >= 0) {
                skippedMaxCol = Math.max(skippedMaxCol, bandMaxCol);
            }
        }

        /** 重置行带状态。 */
        private void resetBand() {
            bandStartRow = -1;
            bandMinCol = Integer.MAX_VALUE;
            bandMaxCol = -1;
            bandLastIncludedRow = -1;
            bandText.setLength(0);
        }

        /**
         * 计算未索引范围。
         *
         * @return 范围文本；全部内容已索引返回 null
         */
        private String skippedRange() {
            if (!hasContent) {
                return null;
            }
            if (skippedContent) {
                int startRow = indexedMaxRow >= 0 ? indexedMaxRow + 1 : firstContentRow;
                int endRow = Math.max(skippedMaxRow, lastContentRow);
                if (startRow <= endRow) {
                    int startCol = indexedMaxRow >= 0 ? Math.min(indexedMinCol, firstContentCol) : firstContentCol;
                    int endCol = Math.max(skippedMaxCol, lastContentCol);
                    if (endCol >= startCol) {
                        return cellRange(startRow, endRow, startCol, endCol);
                    }
                }
            }
            if (columnsTruncated) {
                int startRow = indexedMaxRow >= 0 ? indexedMinRow : firstContentRow;
                int endRow = indexedMaxRow >= 0 ? indexedMaxRow : lastContentRow;
                int endCol = Math.max(skippedMaxCol, lastContentCol);
                if (endCol >= limits.maxColumns()) {
                    return cellRange(startRow, endRow, limits.maxColumns(), endCol);
                }
            }
            return null;
        }

        /**
         * 构造 0 基坐标的单元格范围。
         *
         * @param startRow 起始行
         * @param endRow 结束行
         * @param startCol 起始列
         * @param endCol 结束列
         * @return 例如 A1:F20
         */
        private static String cellRange(int startRow, int endRow, int startCol, int endCol) {
            return CellReference.convertNumToColString(startCol)
                    + (startRow + 1)
                    + ":"
                    + CellReference.convertNumToColString(endCol)
                    + (endRow + 1);
        }
    }
}
