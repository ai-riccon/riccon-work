package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.audit.service.AuditActionVocabulary;
import com.riccon.api.audit.service.AuditCsvWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 转义与动作词汇单元测试（不启动 Spring）。
 */
class AuditCsvWriterTest {

    /** 公式注入危险前缀统一前置单引号。 */
    @Test
    @DisplayName("公式注入前缀 =/+/-/@/Tab/CR 前置单引号")
    void escape_prefixesFormulaPrefixes() {
        assertThat(AuditCsvWriter.escape("=1+1")).isEqualTo("'=1+1");
        assertThat(AuditCsvWriter.escape("+cmd")).isEqualTo("'+cmd");
        assertThat(AuditCsvWriter.escape("-2+3")).isEqualTo("'-2+3");
        assertThat(AuditCsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(AuditCsvWriter.escape("\tvalue")).isEqualTo("'\tvalue");
        assertThat(AuditCsvWriter.escape("\rvalue")).isEqualTo("\"'\rvalue\"");
    }

    /** RFC 4180 引号与逗号转义；details JSON 只加引号，不破坏内部字段。 */
    @Test
    @DisplayName("RFC 4180 转义不改变 details JSON 内部结构")
    void escape_quotesRfc4180() {
        assertThat(AuditCsvWriter.escape("normal-target")).isEqualTo("normal-target");
        assertThat(AuditCsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditCsvWriter.escape("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(AuditCsvWriter.escape("{\"fileId\":\"x\",\"sizeBytes\":1}"))
                .isEqualTo("\"{\"\"fileId\"\":\"\"x\"\",\"\"sizeBytes\"\":1}\"");
        assertThat(AuditCsvWriter.escape(null)).isEmpty();
    }

    /** 动作词汇包含 API/Worker/M10 动作，供策略端点下拉。 */
    @Test
    @DisplayName("动作词汇包含 API、Worker 与 M10 动作")
    void vocabulary_containsApiWorkerAndM10Actions() {
        assertThat(AuditActionVocabulary.knownActions())
                .contains(
                        "auth.login.succeeded",
                        "file.uploaded",
                        "tenant.created",
                        "model.invocation.completed",
                        "mcp.tool.invoked",
                        "artifact.saved",
                        "user.access.denied",
                        "audit.queried",
                        "audit.export.requested",
                        "audit.export.approved",
                        "audit.export.rejected",
                        "data.exported")
                .isSorted()
                .doesNotHaveDuplicates();
    }
}
