package com.riccon.worker.research.service;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.research.domain.ReportCitation;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ReportToolSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 报告 Markdown 组装。
 *
 * <p>把模型生成的分节正文与检索命中、已审批工具调用摘要组装为可交付报告：
 * 标题、摘要、分析正文、引用清单与数据来源。引用序号与 {@code artifact_citation} 一致，
 * 每条引用都指向真实的知识库文档或 MCP 调用，便于 M02-AC-10 在任务时间线中抽查。
 *
 * <p>正文不包含系统提示、完整工具参数或工具结果原文；工具调用只展示调用 ID 与 SHA-256 摘要。
 */
@Component
public class ReportComposer {

    /** 产物标题最大长度，与 {@code artifact.title} 一致。 */
    public static final int MAX_TITLE_LENGTH = 300;

    /** 摘要最大长度，保证 {@code artifact_version.summary} 可读且不超列宽。 */
    public static final int MAX_SUMMARY_LENGTH = 500;

    /** 引用片段最大长度，与 {@code artifact_citation.snippet} 的 1000 字节上限留出余量。 */
    public static final int MAX_SNIPPET_LENGTH = 500;

    /** 报告标题后缀。 */
    private static final String TITLE_SUFFIX = " · 市场调研报告";

    /** 主题为空时的默认标题。 */
    private static final String DEFAULT_TITLE = "市场调研报告";

    /**
     * 组装报告。
     *
     * @param topic 调研主题
     * @param generatedText 模型生成的分节正文
     * @param chunks 生成阶段使用的检索命中
     * @param toolSource 已审批工具调用摘要；未使用时为空
     * @param modelKey 生成模型标识
     * @param querySha256 检索查询 SHA-256
     * @param hitCount 检索命中数
     * @return 报告文档
     */
    public ReportDocument compose(
            String topic,
            String generatedText,
            List<RetrievedChunk> chunks,
            ReportToolSource toolSource,
            String modelKey,
            String querySha256,
            int hitCount) {
        Objects.requireNonNull(topic, "topic must not be null");
        String bodyText = generatedText == null ? "" : generatedText.strip();
        if (bodyText.isEmpty()) {
            throw new IllegalArgumentException("generated report body must not be blank");
        }
        String title = title(topic);
        List<ReportCitation> citations = citations(chunks, toolSource);
        String summary = summarize(bodyText, citations.size(), toolSource != null);
        String body = body(title, summary, bodyText, citations, toolSource, modelKey, querySha256, hitCount);
        return new ReportDocument(title, body, summary, citations);
    }

    /**
     * 构造产物标题。
     *
     * @param topic 调研主题
     * @return 标题
     */
    String title(String topic) {
        String normalized = singleLine(topic);
        String base = normalized.isEmpty() ? DEFAULT_TITLE : normalized + TITLE_SUFFIX;
        return truncate(base, MAX_TITLE_LENGTH);
    }

    /**
     * 把检索命中与工具调用转换为引用清单。
     *
     * @param chunks 检索命中
     * @param toolSource 工具调用；可为空
     * @return 引用清单
     */
    List<ReportCitation> citations(List<RetrievedChunk> chunks, ReportToolSource toolSource) {
        List<ReportCitation> citations = new ArrayList<>();
        int ordinal = 1;
        for (RetrievedChunk chunk : chunks == null ? List.<RetrievedChunk>of() : chunks) {
            String sourceType = chunk.sourceType() == CitationSourceType.FILE
                    ? CitationSourceType.FILE.name()
                    : CitationSourceType.KNOWLEDGE.name();
            citations.add(new ReportCitation(
                    ordinal++,
                    sourceType,
                    chunk.fileId(),
                    chunk.knowledgeDocumentId(),
                    null,
                    fallbackTitle(chunk.title()),
                    chunk.snippet(MAX_SNIPPET_LENGTH),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo()));
        }
        if (toolSource != null) {
            citations.add(new ReportCitation(
                    ordinal,
                    "MCP",
                    null,
                    null,
                    toolSource.invocationId(),
                    "MCP 工具 " + singleLine(toolSource.toolName()),
                    "结果摘要 sha256=" + toolSource.resultSha256() + "，" + toolSource.sizeBytes() + " 字节，耗时 "
                            + toolSource.durationMs() + " ms",
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return List.copyOf(citations);
    }

    /**
     * 从生成正文提取可读摘要。
     *
     * <p>取第一段非标题文字并压成单行；没有可提取内容时使用基于来源数量的固定说明，保证摘要列非空。
     *
     * @param generatedText 生成正文
     * @param citationCount 引用数量
     * @param hasTool 是否使用了 MCP 工具
     * @return 摘要
     */
    String summarize(String generatedText, int citationCount, boolean hasTool) {
        StringBuilder paragraph = new StringBuilder();
        for (String line : generatedText.split("\\R")) {
            String candidate = line.strip();
            if (candidate.isEmpty()) {
                if (paragraph.length() > 0) {
                    break;
                }
                continue;
            }
            if (candidate.startsWith("#")) {
                if (paragraph.length() > 0) {
                    break;
                }
                continue;
            }
            candidate = candidate.replaceFirst("^[-*+>]\\s*", "").strip();
            if (candidate.isEmpty()) {
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(candidate);
        }
        if (paragraph.length() > 0) {
            return truncate(singleLine(paragraph.toString()), MAX_SUMMARY_LENGTH);
        }
        String source = citationCount > 0 ? "知识库检索结果" : "已提供的资料";
        String tool = hasTool ? "与一次经人工审批的只读工具调用" : "";
        return truncate("本报告基于" + source + tool + "生成，结论仅依据受托资料，供内部调研参考。", MAX_SUMMARY_LENGTH);
    }

    /**
     * 组装完整 Markdown 正文。
     *
     * @param title 标题
     * @param summary 摘要
     * @param bodyText 模型正文
     * @param citations 引用清单
     * @param toolSource 工具调用
     * @param modelKey 生成模型
     * @param querySha256 检索查询摘要
     * @param hitCount 检索命中数
     * @return Markdown
     */
    private String body(
            String title,
            String summary,
            String bodyText,
            List<ReportCitation> citations,
            ReportToolSource toolSource,
            String modelKey,
            String querySha256,
            int hitCount) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");
        markdown.append("## 摘要\n\n").append(summary).append("\n\n");
        markdown.append("## 分析\n\n").append(bodyText).append("\n\n");
        markdown.append("## 引用清单\n\n");
        if (citations.isEmpty()) {
            markdown.append("本次生成未引用外部资料。\n\n");
        } else {
            for (ReportCitation citation : citations) {
                markdown.append(citation.ordinal())
                        .append(". [")
                        .append(citation.ordinal())
                        .append("] ")
                        .append(citation.title())
                        .append(locator(citation))
                        .append("：")
                        .append(citation.snippet().isBlank() ? "（无片段）" : citation.snippet())
                        .append('\n');
            }
            markdown.append('\n');
        }
        markdown.append("## 数据来源\n\n");
        markdown.append("- 知识库来源：")
                .append(hitCount)
                .append(" 条，查询摘要 SHA-256 `")
                .append(querySha256 == null ? "" : querySha256)
                .append("`\n");
        for (ReportCitation citation : citations) {
            if (!"MCP".equals(citation.sourceType())) {
                markdown.append("- 知识库来源 [")
                        .append(citation.ordinal())
                        .append("] ")
                        .append(citation.title())
                        .append(locator(citation))
                        .append('\n');
            }
        }
        if (toolSource != null) {
            markdown.append("- MCP 工具 `")
                    .append(singleLine(toolSource.toolName()))
                    .append("`：调用 `")
                    .append(toolSource.invocationId())
                    .append("`，耗时 ")
                    .append(toolSource.durationMs())
                    .append(" ms，结果 SHA-256 `")
                    .append(toolSource.resultSha256())
                    .append("`，")
                    .append(toolSource.sizeBytes())
                    .append(" 字节\n");
        }
        markdown.append("- 生成模型：`")
                .append(modelKey == null || modelKey.isBlank() ? "unknown" : singleLine(modelKey))
                .append("`（调用记录见任务时间线）\n");
        return markdown.toString();
    }

    /**
     * 生成引用定位后缀。
     *
     * @param citation 引用
     * @return 定位文本；无定位时为空串
     */
    private static String locator(ReportCitation citation) {
        LocatorType locatorType = citation.locatorType();
        if (locatorType == null) {
            return "";
        }
        return switch (locatorType) {
            case PAGE -> citation.pageNo() == null ? "" : "（第 " + citation.pageNo() + " 页）";
            case SHEET -> {
                StringBuilder value = new StringBuilder();
                if (citation.sheetName() != null && !citation.sheetName().isBlank()) {
                    value.append("（工作表 ").append(singleLine(citation.sheetName()));
                } else {
                    value.append("（工作表");
                }
                if (citation.cellRange() != null && !citation.cellRange().isBlank()) {
                    value.append("，").append(singleLine(citation.cellRange()));
                }
                value.append("）");
                yield value.toString();
            }
            case PARAGRAPH -> citation.paragraphNo() == null ? "" : "（第 " + citation.paragraphNo() + " 段）";
        };
    }

    /**
     * 来源标题兜底。
     *
     * @param title 原始标题
     * @return 非空单行标题
     */
    private static String fallbackTitle(String title) {
        String normalized = singleLine(title);
        return normalized.isEmpty() ? "未命名来源" : truncate(normalized, MAX_TITLE_LENGTH);
    }

    /**
     * 压成单行：折叠空白字符，避免破坏 Markdown 列表结构。
     *
     * @param value 原始文本
     * @return 单行文本
     */
    private static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    /**
     * 截断文本，避免超过数据库列宽。
     *
     * @param value 原始文本
     * @param maxLength 最大字符数
     * @return 截断文本
     */
    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
