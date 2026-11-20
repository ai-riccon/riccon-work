package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.service.AuditDetailsInvalidException;
import com.riccon.api.audit.service.AuditDetailsReader;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计 details 读取策略单元测试（不启动 Spring）。
 *
 * <p>读取与写入共用 action-aware 白名单：模型 token 指标必须通过，敏感键/未知动作非空 details/损坏 JSON
 * fail-closed。
 */
class AuditDetailsReaderTest {

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 模型事件的 promptTokens/completionTokens 是合法键，不能被 token 子串规则误杀。 */
    @Test
    @DisplayName("模型 token 指标通过白名单")
    void read_allowsModelTokenMetrics() {
        Map<String, Object> details = AuditDetailsReader.read(
                objectMapper,
                "model.invocation.completed",
                "{\"modelId\":\"fake-model\",\"durationMs\":12,\"promptTokens\":10,\"completionTokens\":20}");
        assertThat(details).containsEntry("promptTokens", 10).containsEntry("completionTokens", 20);
    }

    /** Worker 链空失败事件带 reason=CHAIN_EMPTY；读取必须通过与 Worker 策略对称的白名单。 */
    @Test
    @DisplayName("Worker 链空失败 reason 可通过读取策略")
    void read_allowsWorkerModelFailureReason() {
        Map<String, Object> details = AuditDetailsReader.read(
                objectMapper,
                "model.invocation.failed",
                "{\"durationMs\":0,\"errorCode\":\"RICCON-MODEL-503\",\"retryable\":true,"
                        + "\"emitted\":false,\"reason\":\"CHAIN_EMPTY\"}");
        assertThat(details)
                .containsEntry("reason", "CHAIN_EMPTY")
                .containsEntry("emitted", false)
                .containsEntry("retryable", true);
    }

    /** reason 只对 failed 放行；其他模型动作继续 fail-closed。 */
    @Test
    @DisplayName("reason 未放宽到其他模型动作")
    void read_rejectsReasonOnOtherModelActions() {
        assertThatThrownBy(() -> AuditDetailsReader.read(
                        objectMapper, "model.invocation.completed", "{\"modelId\":\"m\",\"reason\":\"CHAIN_EMPTY\"}"))
                .isInstanceOf(AuditDetailsInvalidException.class);
        assertThatThrownBy(() -> AuditDetailsReader.read(
                        objectMapper, "model.invocation.started", "{\"modelId\":\"m\",\"reason\":\"CHAIN_EMPTY\"}"))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }

    /** 敏感键返回 500 语义异常，不返回内容。 */
    @Test
    @DisplayName("敏感键 fail-closed")
    void read_rejectsSensitiveKey() {
        assertThatThrownBy(() -> AuditDetailsReader.read(objectMapper, "auth.login.succeeded", "{\"token\":\"abc\"}"))
                .isInstanceOf(AuditDetailsInvalidException.class);
        assertThatThrownBy(() -> AuditDetailsReader.read(objectMapper, "file.uploaded", "{\"email\":\"a@b.c\"}"))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }

    /** 未知动作的非空 details 失败；空 details 允许。 */
    @Test
    @DisplayName("未知动作非空 details 失败，空 details 允许")
    void read_unknownActionRules() {
        assertThatThrownBy(() -> AuditDetailsReader.read(objectMapper, "custom.unknown", "{\"foo\":\"bar\"}"))
                .isInstanceOf(AuditDetailsInvalidException.class);
        assertThat(AuditDetailsReader.read(objectMapper, "custom.unknown", null))
                .isEmpty();
        assertThat(AuditDetailsReader.read(objectMapper, "custom.unknown", "null"))
                .isEmpty();
    }

    /** 损坏 JSON 失败而不是静默跳过。 */
    @Test
    @DisplayName("损坏 JSON fail-closed")
    void read_rejectsCorruptJson() {
        assertThatThrownBy(() -> AuditDetailsReader.read(objectMapper, "file.uploaded", "{not-json"))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }
}
