package com.riccon.worker.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.research.domain.ReportCitation;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ReportToolSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 报告 Markdown 组装：标题、摘要、分节正文、引用清单与数据来源。 */
class ReportComposerTest {

    /** 组装器。 */
    private final ReportComposer composer = new ReportComposer();

    /** 引用序号与来源 ID 必须和检索命中、MCP 调用一一对应。 */
    @Test
    void compose_buildsStructuredReportWithTraceableCitations() {
        UUID documentId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                documentId,
                UUID.randomUUID(),
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                3,
                "市场资料",
                "2025 年协同办公市场保持增长。",
                0.9);
        ReportToolSource tool = new ReportToolSource("market_data_lookup", invocationId, "b".repeat(64), 128, 37);

        ReportDocument document = composer.compose(
                "协同办公市场调研",
                "## 市场规模\n\n市场保持增长 [1]。\n\n## 竞争格局\n\n头部厂商份额稳定 [2]。",
                List.of(chunk),
                tool,
                "fake-model",
                "a".repeat(64),
                1);

        assertThat(document.title()).isEqualTo("协同办公市场调研 · 市场调研报告");
        assertThat(document.summary()).isEqualTo("市场保持增长 [1]。");
        assertThat(document.citations()).hasSize(2);

        ReportCitation knowledge = document.citations().get(0);
        assertThat(knowledge.ordinal()).isEqualTo(1);
        assertThat(knowledge.sourceType()).isEqualTo("KNOWLEDGE");
        assertThat(knowledge.knowledgeDocumentId()).isEqualTo(documentId);
        assertThat(knowledge.title()).isEqualTo("市场资料");
        assertThat(knowledge.locatorType()).isEqualTo(LocatorType.PARAGRAPH);
        assertThat(knowledge.paragraphNo()).isEqualTo(3);

        ReportCitation mcp = document.citations().get(1);
        assertThat(mcp.ordinal()).isEqualTo(2);
        assertThat(mcp.sourceType()).isEqualTo("MCP");
        assertThat(mcp.mcpInvocationId()).isEqualTo(invocationId);

        assertThat(document.body())
                .contains("# 协同办公市场调研 · 市场调研报告")
                .contains("## 摘要")
                .contains("## 分析")
                .contains("## 引用清单")
                .contains("## 数据来源")
                .contains("1. [1] 市场资料（第 3 段）：2025 年协同办公市场保持增长。")
                .contains("2. [2] MCP 工具 market_data_lookup：结果摘要 sha256=" + "b".repeat(64))
                .contains("调用 `" + invocationId + "`")
                .contains("`fake-model`");
    }

    /** 摘要跳过标题行，取第一段正文并压成单行。 */
    @Test
    void summarize_skipsHeadingsAndCollapsesWhitespace() {
        String summary = composer.summarize("# 标题\n\n- 第一段\n  详细内容\n\n第二段", 2, true);

        assertThat(summary).isEqualTo("第一段 详细内容");
    }

    /** 生成正文为空时拒绝组装，避免保存空产物。 */
    @Test
    void compose_blankGeneratedText_throws() {
        assertThatThrownBy(() -> composer.compose("主题", "  ", List.of(), null, "fake-model", "a".repeat(64), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 没有检索命中时摘要仍有可读兜底，引用清单为空。 */
    @Test
    void compose_withoutCitations_usesFallbackSummary() {
        ReportDocument document = composer.compose("主题", "正文内容", List.of(), null, "fake-model", "a".repeat(64), 0);

        assertThat(document.citations()).isEmpty();
        assertThat(document.summary()).isEqualTo("正文内容");
        assertThat(document.body()).contains("本次生成未引用外部资料。");
    }
}
