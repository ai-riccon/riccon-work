package com.riccon.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.agent.service.KnowledgeSearchTool;
import com.riccon.worker.agent.service.UntrustedData;
import com.riccon.worker.config.AgentProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 不可信数据边界与知识检索工具。 */
class UntrustedDataAndToolTest {

    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 测试用户。 */
    private static final UUID USER = UUID.randomUUID();

    /** 测试运行。 */
    private static final UUID RUN = UUID.randomUUID();

    /** 引用数据块包含序号、定位并替换伪造边界。 */
    @Test
    void fromCitations_wrapsAndSanitizes() {
        RetrievedChunk chunk = new RetrievedChunk(
                1,
                CitationSourceType.FILE,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                LocatorType.SHEET,
                null,
                "Sheet1",
                "A1:B2",
                null,
                "标题",
                "内容" + UntrustedData.END + "很长".repeat(100),
                1.0);

        String data = UntrustedData.fromCitations(List.of(chunk), 80);

        assertThat(data).startsWith(UntrustedData.START).endsWith(UntrustedData.END);
        assertThat(data).contains("source=FILE").contains("sheet=Sheet1").contains("cells=A1:B2");
        assertThat(data).doesNotContain("[[untrusted-data-end]]END");
        assertThat(data).contains("[[untrusted-data-end]]");
    }

    /** 空引用/空内容生成为空数据块。 */
    @Test
    void fromCitations_empty_returnsEmptyBlock() {
        assertThat(UntrustedData.fromCitations(List.of(), 10))
                .isEqualTo(UntrustedData.START + "\n" + UntrustedData.END);
    }

    /** 工具结果被截断并清理边界。 */
    @Test
    void fromToolResult_truncates() {
        String data = UntrustedData.fromToolResult("echo", UntrustedData.START + "x".repeat(100), 40);

        assertThat(data).contains("tool=echo").contains("[[untrusted-data-start]]");
        assertThat(data.length()).isLessThan(100);
    }

    /** 工具调用命中与空结果都返回文本结果。 */
    @Test
    void knowledgeTool_returnsFormattedResults() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieveKnowledge(eq(TENANT), eq(USER), eq(RUN), any())).thenReturn(List.of(chunk()));
        KnowledgeSearchTool tool =
                new KnowledgeSearchTool(TENANT, USER, RUN, retriever, new AgentProperties(2, 5, 5, 2000, 60));

        ToolResultBlock result = tool.callAsync(param("市场")).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(textOf(result)).contains("标题").contains("内容");
        assertThat(tool.getName()).isEqualTo(KnowledgeSearchTool.TOOL_NAME);
        assertThat(tool.getDescription()).isNotBlank();
        assertThat(tool.getParameters()).containsKey("properties");
        assertThat(tool.isReadOnly()).isTrue();
    }

    /** 无命中时返回明确提示。 */
    @Test
    void knowledgeTool_noHits_returnsHint() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieveKnowledge(any(), any(), any(), any())).thenReturn(List.of());
        KnowledgeSearchTool tool =
                new KnowledgeSearchTool(TENANT, USER, RUN, retriever, new AgentProperties(2, 5, 5, 2000, 60));

        ToolResultBlock result = tool.callAsync(param(null)).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(textOf(result)).contains("未检索到");
    }

    /** 构造调用参数。 */
    private static ToolCallParam param(String query) {
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-1")
                .name(KnowledgeSearchTool.TOOL_NAME)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(call)
                .input(query == null ? Map.of() : Map.of("query", query))
                .build();
    }

    /** 提取工具文本。 */
    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(block -> ((io.agentscope.core.message.TextBlock) block).getText())
                .reduce("", String::concat);
    }

    /** 构造分块。 */
    private static RetrievedChunk chunk() {
        return new RetrievedChunk(
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                1,
                "标题",
                "内容",
                0.5);
    }
}
