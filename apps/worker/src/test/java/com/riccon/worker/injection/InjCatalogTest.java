package com.riccon.worker.injection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `INJ-001..012` 目录契约：防止有人删样本或缩小覆盖面后 harness 仍然“全绿”。
 *
 * <p>ADR-0023 §8 要求 M05-B10 覆盖 Excel 可见单元格、隐藏表、公式缓存值与 OCR 中英文注入；
 * 编号、语种、输入表面与预期索引行为是验收的一部分，不能只靠参数化执行时“跑到几条算几条”。
 */
class InjCatalogTest {

    /** 12 条编号必须连续且稳定。 */
    @Test
    @DisplayName("INJ-001..012 编号连续且每条只有一条")
    void catalog_hasStableTwelveIds() {
        List<String> ids =
                InjectionSamples.ALL.stream().map(InjectionSample::id).toList();

        assertThat(ids)
                .containsExactly(
                        "INJ-001", "INJ-002", "INJ-003", "INJ-004", "INJ-005", "INJ-006", "INJ-007", "INJ-008",
                        "INJ-009", "INJ-010", "INJ-011", "INJ-012");
        assertThat(ids).doesNotHaveDuplicates();
    }

    /** Excel/OCR 表面与中英文覆盖必须同时存在。 */
    @Test
    @DisplayName("样本覆盖 Excel 单元格/隐藏表/公式与 OCR 中英文")
    void catalog_coversExcelOcrAndLocales() {
        assertThat(InjectionSamples.ALL.stream()
                        .map(InjectionSample::surface)
                        .collect(java.util.stream.Collectors.toSet()))
                .containsAll(Set.of(
                        InjectionSurface.XLSX_VISIBLE_CELL,
                        InjectionSurface.XLSX_HIDDEN_SHEET,
                        InjectionSurface.XLSX_FORMULA_CELL,
                        InjectionSurface.OCR_LINE,
                        InjectionSurface.OCR_FORGED_BOUNDARY));
        assertThat(InjectionSamples.ALL.stream()
                        .map(InjectionSample::locale)
                        .collect(java.util.stream.Collectors.toSet()))
                .contains("zh-CN", "en", "zh-en");
        assertThat(InjectionSamples.ALL.stream()
                        .filter(sample -> sample.locale().startsWith("zh"))
                        .count())
                .as("中文样本至少 3 条")
                .isGreaterThanOrEqualTo(3);
        assertThat(InjectionSamples.ALL.stream()
                        .filter(sample -> sample.locale().startsWith("en"))
                        .count())
                .as("英文样本至少 3 条")
                .isGreaterThanOrEqualTo(3);
    }

    /** 至少各有一条 canary、内网地址、未授权工具、伪造边界样本。 */
    @Test
    @DisplayName("攻击目标覆盖 canary/内网地址/未授权工具/边界伪造")
    void catalog_coversAttackGoals() {
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.payload()).contains(InjectionSamples.CANARY_KEY_NAME));
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.payload()).contains(InjectionSamples.INTERNAL_ADDRESS));
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.payload()).contains(ForbiddenToolStub.TOOL_NAME));
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.expectForgedMarkers()).isTrue());
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.expectToolCall()).isTrue());
        assertThat(InjectionSamples.ALL)
                .anySatisfy(sample -> assertThat(sample.expectIndexed()).isFalse());
    }
}
