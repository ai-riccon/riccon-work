package com.riccon.api.research.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * J2 引用响应：Excel 定位透传与四态遮蔽。
 */
class CitationMaskingTest {

    /** 产物引用必须透传 cellRange 与 sourceStatus，供 M05-AC-02 展示区域。 */
    @Test
    @DisplayName("产物引用：保留 cellRange 与 UPDATED 快照")
    void artifactCitation_keepsCellRangeAndSnapshot() {
        ArtifactCitationResponse response =
                ArtifactCitationResponse.from(citation(ArtifactSourceType.KNOWLEDGE, CitationSourceStatus.UPDATED));

        assertThat(response.cellRange()).isEqualTo("B2:D10");
        assertThat(response.sheetName()).isEqualTo("销售数据");
        assertThat(response.sourceStatus()).isEqualTo(CitationSourceStatus.UPDATED);
        assertThat(response.title()).isEqualTo("销售数据.xlsx");
        assertThat(response.snippet()).isEqualTo("区域摘要");
    }

    /** NO_ACCESS/DELETED 遮蔽产物引用正文、定位与 cellRange。 */
    @Test
    @DisplayName("产物引用：NO_ACCESS/DELETED 遮蔽正文与定位")
    void artifactCitation_masksWhenHidden() {
        for (CitationSourceStatus status :
                new CitationSourceStatus[] {CitationSourceStatus.NO_ACCESS, CitationSourceStatus.DELETED}) {
            ArtifactCitationResponse response =
                    ArtifactCitationResponse.from(citation(ArtifactSourceType.KNOWLEDGE, status));

            assertThat(response.sourceStatus()).isEqualTo(status);
            assertThat(response.title()).isEmpty();
            assertThat(response.snippet()).isEmpty();
            assertThat(response.locatorType()).isNull();
            assertThat(response.sheetName()).isNull();
            assertThat(response.cellRange()).isNull();
            assertThat(response.paragraphNo()).isNull();
        }
    }

    /** 任务级来源同样应用遮蔽；检索阶段来源默认 AVAILABLE。 */
    @Test
    @DisplayName("任务来源：withSourceStatus 遮蔽且阶段来源默认 AVAILABLE")
    void taskCitation_masksAndDefaultsAvailable() {
        CitationResponse stageSource = CitationResponse.fromStageSource(
                Map.of(
                        "ordinal", 1,
                        "sourceType", "KNOWLEDGE",
                        "knowledgeDocumentId", UUID.randomUUID().toString(),
                        "title", "制度.pdf",
                        "snippet", "片段",
                        "locatorType", "PAGE",
                        "pageNo", 2),
                1);
        assertThat(stageSource).isNotNull();
        assertThat(stageSource.sourceStatus()).isEqualTo(CitationSourceStatus.AVAILABLE);

        CitationResponse hidden = stageSource.withSourceStatus(CitationSourceStatus.NO_ACCESS);
        assertThat(hidden.sourceStatus()).isEqualTo(CitationSourceStatus.NO_ACCESS);
        assertThat(hidden.title()).isEmpty();
        assertThat(hidden.snippet()).isEmpty();
        assertThat(hidden.locatorType()).isNull();
        assertThat(hidden.pageNo()).isNull();
        assertThat(hidden.knowledgeDocumentId()).isEqualTo(stageSource.knowledgeDocumentId());
    }

    /** 产物快照转任务来源时保留 Excel 区域。 */
    @Test
    @DisplayName("任务来源：产物 cellRange 透传")
    void taskCitation_fromArtifactKeepsCellRange() {
        CitationResponse response =
                CitationResponse.from(citation(ArtifactSourceType.KNOWLEDGE, CitationSourceStatus.AVAILABLE));

        assertThat(response.cellRange()).isEqualTo("B2:D10");
        assertThat(response.sheetName()).isEqualTo("销售数据");
    }

    /**
     * 构造产物引用快照。
     *
     * @param sourceType 来源类型
     * @param status 可用性
     * @return 快照
     */
    private static ArtifactCitationSnapshot citation(ArtifactSourceType sourceType, CitationSourceStatus status) {
        return new ArtifactCitationSnapshot(
                1,
                sourceType,
                null,
                UUID.randomUUID(),
                null,
                "销售数据.xlsx",
                "区域摘要",
                LocatorType.SHEET,
                null,
                "销售数据",
                "B2:D10",
                null,
                "a".repeat(64),
                status);
    }
}
