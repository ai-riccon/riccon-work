package com.riccon.api.audit.service;

import java.io.IOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计导出 CSV 编码（ADR-0020 第 2 节）。
 *
 * <p>UTF-8 BOM、4 行 {@code #} 元数据、空行、固定表头与 RFC 4180 数据行；字段白名单固定，不含
 * {@code source_ip}、密码、令牌、凭据、原文、邮箱或姓名。所有字符串单元执行公式注入防护：
 * 以 {@code =}/{@code +}/{@code -}/{@code @}/制表符/回车开头时前置 {@code '}。
 */
public final class AuditCsvWriter {

    /** 表头；与 ADR-0020 第 2 节一致。 */
    private static final List<String> HEADER = List.of(
            "eventId",
            "occurredAt",
            "tenantId",
            "actorType",
            "actorId",
            "action",
            "targetType",
            "targetId",
            "outcome",
            "traceId",
            "details");

    /** 时间格式：ISO 8601 带偏移。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** UTF-8 BOM。 */
    private static final char BOM = '\uFEFF';

    /** 行分隔符；RFC 4180 使用 CRLF。 */
    private static final String CRLF = "\r\n";

    /** 工具类禁止实例化。 */
    private AuditCsvWriter() {}

    /**
     * 写入 BOM、4 行元数据与空行。
     *
     * @param writer 输出
     * @param download 已校验的导出描述
     * @throws IOException 写入失败
     */
    public static void writeMetadata(Writer writer, AuditDownload download) throws IOException {
        writer.write(BOM);
        writer.write("# exportId: " + download.exportId() + CRLF);
        writer.write("# generatedAt: " + TIME_FORMAT.format(download.generatedAt()) + CRLF);
        writer.write("# filters: " + download.filtersJson() + CRLF);
        writer.write("# rowCount: " + download.rowCount() + CRLF);
        writer.write(CRLF);
    }

    /**
     * 写入表头。
     *
     * @param writer 输出
     * @throws IOException 写入失败
     */
    public static void writeHeader(Writer writer) throws IOException {
        writer.write(String.join(",", HEADER) + CRLF);
    }

    /**
     * 写入一条事件数据行。
     *
     * @param writer 输出
     * @param event 已通过 details 白名单的事件
     * @param objectMapper details 序列化
     * @throws IOException 写入失败
     */
    public static void writeRow(Writer writer, AuditEventView event, ObjectMapper objectMapper) throws IOException {
        String details = objectMapper.writeValueAsString(event.details() == null ? Map.of() : event.details());
        List<String> cells = List.of(
                event.eventId().toString(),
                TIME_FORMAT.format(event.occurredAt()),
                event.tenantId(),
                event.actorType().name(),
                event.actorId(),
                event.action(),
                event.targetType() == null ? "" : event.targetType().name(),
                event.targetId() == null ? "" : event.targetId(),
                event.outcome().name(),
                event.traceId(),
                details);
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(escape(cells.get(index)));
        }
        writer.write(line.append(CRLF).toString());
    }

    /**
     * 转义单个单元：先做公式注入防护，再按 RFC 4180 加引号。
     *
     * @param value 原值，可为空
     * @return 安全单元
     */
    public static String escape(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && isFormulaPrefix(text.charAt(0))) {
            // 前置单引号让表格软件按文本处理；details 以 '{' 开头，不受影响。
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * 判断是否属于公式注入危险前缀。
     *
     * @param first 首字符
     * @return 危险时返回 {@code true}
     */
    private static boolean isFormulaPrefix(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
