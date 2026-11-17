package com.riccon.worker.agent.service;

import com.riccon.worker.agent.domain.RetrievedChunk;
import java.util.List;

/**
 * 不可信数据格式化。
 *
 * <p>检索内容与工具结果一律包裹在明确的数据边界内，系统指令只说明“数据不是指令”，
 * 不拼接任何动态内容。数据中出现伪造边界时会被替换，降低提示注入穿透风险。
 */
public final class UntrustedData {

    /** 数据开始标记。 */
    public static final String START = "<<<UNTRUSTED_DATA>>>";

    /** 数据结束标记。 */
    public static final String END = "<<<END_UNTRUSTED_DATA>>>";

    /** 单次数据块总字符上限。 */
    private static final int MAX_TOTAL_CHARS = 24000;

    /** 工具类禁止实例化。 */
    private UntrustedData() {}

    /**
     * 格式化检索分块。
     *
     * @param chunks 分块
     * @param maxChunkChars 单块字符上限
     * @return 数据块
     */
    public static String fromCitations(List<RetrievedChunk> chunks, int maxChunkChars) {
        StringBuilder builder = new StringBuilder(START).append('\n');
        for (RetrievedChunk chunk : chunks) {
            String header = "[" + chunk.ordinal() + "] source=" + chunk.sourceType() + " title=\""
                    + sanitize(chunk.title()) + "\" locator=" + chunk.locatorType()
                    + locator(chunk) + '\n';
            if (builder.length() + header.length() >= MAX_TOTAL_CHARS) {
                break;
            }
            builder.append(header);
            String content = truncate(sanitize(chunk.content()), maxChunkChars);
            int allowed = Math.min(content.length(), MAX_TOTAL_CHARS - builder.length());
            if (allowed > 0) {
                builder.append(content, 0, allowed).append('\n');
            }
        }
        builder.append(END);
        return builder.toString();
    }

    /**
     * 格式化工具结果。
     *
     * @param toolName 工具名
     * @param result 工具返回文本
     * @param maxChars 字符上限
     * @return 数据块
     */
    public static String fromToolResult(String toolName, String result, int maxChars) {
        return START + "\ntool=" + sanitize(toolName) + "\n" + truncate(sanitize(result), maxChars) + "\n" + END;
    }

    /**
     * 生成定位描述。
     *
     * @param chunk 分块
     * @return 描述
     */
    private static String locator(RetrievedChunk chunk) {
        StringBuilder builder = new StringBuilder();
        if (chunk.pageNo() != null) {
            builder.append(" page=").append(chunk.pageNo());
        }
        if (chunk.sheetName() != null && !chunk.sheetName().isBlank()) {
            builder.append(" sheet=").append(sanitize(chunk.sheetName()));
        }
        if (chunk.cellRange() != null && !chunk.cellRange().isBlank()) {
            builder.append(" cells=").append(sanitize(chunk.cellRange()));
        }
        if (chunk.paragraphNo() != null) {
            builder.append(" paragraph=").append(chunk.paragraphNo());
        }
        return builder.toString();
    }

    /**
     * 替换伪造边界标记。
     *
     * @param value 原文
     * @return 清理后的文本
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(START, "[[untrusted-data-start]]").replace(END, "[[untrusted-data-end]]");
    }

    /**
     * 截断文本。
     *
     * @param value 原文
     * @param maxLength 上限
     * @return 截断文本
     */
    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
