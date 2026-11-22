package com.riccon.platform.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 知识库共享枚举守卫（M05）。
 *
 * <p>这些枚举与 V12 的 CHECK 约束、OpenAPI 字段和审计 details 一一对应；改名/增删必须先改 ADR 与迁移。
 * 平台层单测覆盖枚举构造、{@code values()} 与 {@code valueOf()}，避免新枚举拉低 platform-domain
 * 覆盖率门槛（合同 constraints.coverage）。
 */
class KnowledgeVocabularyTest {

    /** 读取枚举常量名。 */
    private static <E extends Enum<E>> List<String> names(E[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** 每个枚举的 values/valueOf/name 往返一致，且常量名大写 + 下划线。 */
    private static <E extends Enum<E>> void assertRoundTrip(E[] values, Function<String, E> valueOf) {
        assertThat(values).isNotEmpty();
        for (E value : values) {
            assertThat(valueOf.apply(value.name())).isEqualTo(value);
            assertThat(value.name()).matches("^[A-Z][A-Z0-9_]*$");
        }
    }

    /** 文档可见范围与 ADR-0023 §5.1/§5.2 一致。 */
    @Test
    void knowledgeVisibility_isStable() {
        assertThat(names(KnowledgeVisibility.values())).containsExactly("TENANT", "DEPT", "USERS");
        assertRoundTrip(KnowledgeVisibility.values(), KnowledgeVisibility::valueOf);
    }

    /** 解析引擎与 ADR-0023 §3/§4 一致。 */
    @Test
    void parseEngine_isStable() {
        assertThat(names(ParseEngine.values())).containsExactly("PDFBOX", "POI", "TESSERACT", "RAPIDOCR", "NONE");
        assertRoundTrip(ParseEngine.values(), ParseEngine::valueOf);
    }

    /** 索引模式与 ADR-0023 §7.3 一致。 */
    @Test
    void knowledgeIndexMode_isStable() {
        assertThat(names(KnowledgeIndexMode.values())).containsExactly("FULL", "ACL_ONLY", "DELETE");
        assertRoundTrip(KnowledgeIndexMode.values(), KnowledgeIndexMode::valueOf);
    }

    /** 连接器类型/状态与 ADR-0023 §6.2 的 CHECK 一致。 */
    @Test
    void connectorEnums_areStable() {
        assertThat(names(ConnectorType.values())).containsExactly("S3");
        assertThat(names(ConnectorStatus.values())).containsExactly("ACTIVE", "PAUSED", "ERROR");
        assertThat(names(ConnectorSyncStatus.values())).containsExactly("SUCCESS", "PARTIAL", "ERROR");
        assertThat(names(ConnectorItemStatus.values()))
                .containsExactly("PENDING", "SYNCED", "SKIPPED", "FAILED", "DELETED");
        assertThat(names(ConnectorSyncTrigger.values())).containsExactly("MANUAL", "SCHEDULE", "RETRY");

        assertRoundTrip(ConnectorType.values(), ConnectorType::valueOf);
        assertRoundTrip(ConnectorStatus.values(), ConnectorStatus::valueOf);
        assertRoundTrip(ConnectorSyncStatus.values(), ConnectorSyncStatus::valueOf);
        assertRoundTrip(ConnectorItemStatus.values(), ConnectorItemStatus::valueOf);
        assertRoundTrip(ConnectorSyncTrigger.values(), ConnectorSyncTrigger::valueOf);
    }
}
