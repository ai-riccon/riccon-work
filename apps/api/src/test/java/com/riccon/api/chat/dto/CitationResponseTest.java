package com.riccon.api.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息引用响应的四态与遮蔽规则（M05-AC-07/M05-AC-12、TM-68）。
 */
class CitationResponseTest {

    /** AVAILABLE 与 UPDATED 保留快照与定位。 */
    @Test
    @DisplayName("响应：AVAILABLE/UPDATED 保留标题、片段与定位")
    void from_keepsSnapshotWhenAccessible() {
        CitationRecord updated = citation(CitationSourceStatus.UPDATED);

        CitationResponse response = CitationResponse.from(updated);

        assertThat(response.sourceStatus()).isEqualTo(CitationSourceStatus.UPDATED);
        assertThat(response.title()).isEqualTo("销售数据.xlsx");
        assertThat(response.snippet()).isEqualTo("B2:D10 区域摘要");
        assertThat(response.sheetName()).isEqualTo("销售数据");
        assertThat(response.cellRange()).isEqualTo("B2:D10");
        assertThat(response.locatorType()).isEqualTo(LocatorType.SHEET);
        assertThat(response.pageNo()).isEqualTo(2);
        assertThat(response.paragraphNo()).isEqualTo(3);
        assertThat(response.knowledgeDocumentId()).isNotNull();
        assertThat(response.chunkId()).isNotNull();
    }

    /** NO_ACCESS/DELETED 保持 required 字段但清空正文与定位。 */
    @Test
    @DisplayName("响应：NO_ACCESS/DELETED 空串与 null 定位")
    void from_masksWhenHidden() {
        for (CitationSourceStatus status :
                new CitationSourceStatus[] {CitationSourceStatus.NO_ACCESS, CitationSourceStatus.DELETED}) {
            CitationResponse response = CitationResponse.from(citation(status));

            assertThat(response.sourceStatus()).isEqualTo(status);
            assertThat(response.title()).isEmpty();
            assertThat(response.snippet()).isEmpty();
            assertThat(response.locatorType()).isNull();
            assertThat(response.pageNo()).isNull();
            assertThat(response.sheetName()).isNull();
            assertThat(response.cellRange()).isNull();
            assertThat(response.paragraphNo()).isNull();
            // 来源 ID 与序号用于前端关联“不可访问”占位，不构成新的存在性泄露。
            assertThat(response.knowledgeDocumentId()).isNotNull();
            assertThat(response.ordinal()).isEqualTo(1);
        }
    }

    /**
     * 构造知识库引用。
     *
     * @param status 可用性
     * @return 引用快照
     */
    private static CitationRecord citation(CitationSourceStatus status) {
        return new CitationRecord(
                UUID.randomUUID(),
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocatorType.SHEET,
                2,
                "销售数据",
                "B2:D10",
                3,
                "销售数据.xlsx",
                "B2:D10 区域摘要",
                new BigDecimal("0.9"),
                Instant.parse("2026-11-23T00:00:00Z"),
                "a".repeat(64),
                status);
    }
}
