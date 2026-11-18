package com.riccon.api.research.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 阶段详情响应映射单元测试。
 *
 * <p>重点验证 {@code details.sources} 不再被白名单丢弃，未知键按契约 {@code additionalProperties: true}
 * 原样保留，并且来源条目的标题、定位与片段正确映射；这些字段决定 J2 检索阶段能否在审批前展示引用。
 */
class RunStageDetailsTest {

    /** 测试用 JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 测试用合法 SHA-256。 */
    private static final String SHA256 = "0123456789abcdef".repeat(4);

    /** 来源数组映射为引用，未知键平铺到响应且不出现内部 extra 字段。 */
    @Test
    void from_sourcesAndUnknownKeys_arePreserved() throws Exception {
        String knowledgeId = "01930000-0000-7000-8000-000000000101";
        String fileId = "01930000-0000-7000-8000-000000000102";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("query", "市场规模");
        details.put("querySha256", SHA256);
        details.put("hitCount", 2);
        details.put("retrievalMode", "hybrid");
        details.put(
                "sources",
                List.of(
                        Map.of(
                                "ordinal",
                                1,
                                "sourceType",
                                "KNOWLEDGE",
                                "knowledgeDocumentId",
                                knowledgeId,
                                "title",
                                "市场报告",
                                "locatorType",
                                "PAGE",
                                "pageNo",
                                3),
                        Map.of(
                                "ordinal", 2,
                                "sourceType", "FILE",
                                "fileId", fileId,
                                "title", "数据表",
                                "locatorType", "SHEET",
                                "sheetName", "Sheet1",
                                "cellRange", "A1:B2",
                                "snippet", "片段")));

        RunStageDetails mapped = RunStageDetails.from(details);

        assertThat(mapped).isNotNull();
        assertThat(mapped.hitCount()).isEqualTo(2);
        assertThat(mapped.sources()).hasSize(2);
        assertThat(mapped.sources().get(0).title()).isEqualTo("市场报告");
        assertThat(mapped.sources().get(0).knowledgeDocumentId().toString()).isEqualTo(knowledgeId);
        assertThat(mapped.sources().get(0).snippet()).isEmpty();
        assertThat(mapped.sources().get(1).fileId().toString()).isEqualTo(fileId);
        assertThat(mapped.sources().get(1).cellRange()).isEqualTo("A1:B2");
        assertThat(mapped.sources().get(1).snippet()).isEqualTo("片段");
        assertThat(mapped.unknownDetails()).containsEntry("retrievalMode", "hybrid");

        String json = MAPPER.writeValueAsString(mapped);
        assertThat(json).contains("\"retrievalMode\":\"hybrid\"");
        assertThat(json).contains("\"title\":\"市场报告\"");
        assertThat(json).doesNotContain("\"extra\"");
    }

    /** 无效来源被跳过，缺失序号时使用兜底序号，未知来源类型不产生伪造引用。 */
    @Test
    void from_invalidSources_areSkipped() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
                "sources",
                List.of(
                        Map.of("sourceType", "KNOWLEDGE", "title", "无序号来源"),
                        Map.of("title", "缺少来源类型"),
                        Map.of("sourceType", "UNKNOWN", "title", "未知类型"),
                        Map.of("ordinal", 0, "sourceType", "FILE", "title", "零序号")));

        RunStageDetails mapped = RunStageDetails.from(details);

        assertThat(mapped).isNotNull();
        assertThat(mapped.sources()).hasSize(2);
        assertThat(mapped.sources().get(0).ordinal()).isEqualTo(1);
        assertThat(mapped.sources().get(1).ordinal()).isEqualTo(4);
    }

    /** 未知键的 JSON null 值必须保留；不能用 Map.copyOf 做防御性复制。 */
    @Test
    void from_unknownNullValue_isPreserved() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("note", null);
        details.put("hitCount", 1);

        RunStageDetails mapped = RunStageDetails.from(details);

        assertThat(mapped).isNotNull();
        assertThat(mapped.unknownDetails()).containsEntry("note", null);
        assertThat(mapped.hitCount()).isEqualTo(1);
    }

    /** 空 details 映射为 {@code null}，保持既有时间线空摘要语义。 */
    @Test
    void from_emptyDetails_returnsNull() {
        assertThat(RunStageDetails.from(null)).isNull();
        assertThat(RunStageDetails.from(Map.of())).isNull();
    }
}
