package com.riccon.worker.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.research.service.ApprovalService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 审批参数脱敏预览：敏感键/值、键数、长度与字节预算。 */
class ApprovalParamsPreviewTest {

    /** 测试 JSON 序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 嵌套敏感键与常见凭据形态必须脱敏。 */
    @Test
    void preview_redactsNestedSecretsAndCredentialValues() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", "Bearer sk-live-12345678");
        input.put("headers", Map.of("authorization", "Bearer abc", "accept", "application/json"));
        input.put("items", List.of(Map.of("password", "pw", "name", "ok")));

        Map<String, Object> preview = ArgumentDigest.preview(input, 3800, 16, 200);

        assertThat(preview.get("text")).isEqualTo("[redacted]");
        Map<?, ?> headers = (Map<?, ?>) preview.get("headers");
        assertThat(headers.get("authorization")).isEqualTo("[redacted]");
        assertThat(headers.get("accept")).isEqualTo("application/json");
        assertThat(ArgumentDigest.hasSensitiveKey(input)).isTrue();
    }

    /** 顶层键、字符串长度与序列化字节数都必须受限。 */
    @Test
    void preview_capsKeysStringsAndBytes() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int index = 0; index < 24; index++) {
            input.put("field" + index, "x".repeat(600));
        }
        List<Object> nested = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            nested.add(Map.of("value", "y".repeat(400)));
        }
        input.put("nested", nested);

        Map<String, Object> preview = ArgumentDigest.preview(
                input,
                ApprovalService.MAX_PREVIEW_BYTES,
                ApprovalService.MAX_PREVIEW_KEYS,
                ApprovalService.MAX_PREVIEW_STRING);

        assertThat(preview).hasSizeLessThanOrEqualTo(ApprovalService.MAX_PREVIEW_KEYS);
        assertThat(MAPPER.writeValueAsBytes(preview).length).isLessThanOrEqualTo(ApprovalService.MAX_PREVIEW_BYTES);
        for (Object value : preview.values()) {
            if (value instanceof String text) {
                assertThat(text.length()).isLessThanOrEqualTo(ApprovalService.MAX_PREVIEW_STRING);
            }
        }
    }

    /** 普通参数不触发敏感判定，预览保留可读摘要。 */
    @Test
    void preview_normalArgumentsRemainReadable() {
        Map<String, Object> input = Map.of("text", "market-data", "limit", 5);

        Map<String, Object> preview = ArgumentDigest.preview(input, 3800, 16, 200);

        assertThat(preview).containsEntry("text", "market-data");
        assertThat(preview).containsEntry("limit", 5);
        assertThat(ArgumentDigest.hasSensitiveKey(input)).isFalse();
        assertThat(new String(MAPPER.writeValueAsBytes(preview), StandardCharsets.UTF_8).startsWith("{"))
                .isTrue();
    }

    /** 空入参与极小预算返回空 JSON 对象，仍满足预览列约束。 */
    @Test
    void preview_emptyAndTinyBudgetReturnsObject() {
        assertThat(ArgumentDigest.preview(null, 3800, 16, 200)).isEmpty();
        assertThat(ArgumentDigest.preview(Map.of("a", "b"), 2, 16, 200)).isEmpty();
        assertThat(ArgumentDigest.preview(Map.of("a", "b"), 3800, 0, 200)).isEmpty();
        assertThat(ArgumentDigest.preview(Map.of("a", "b"), 3800, 16, 0)).isEmpty();
    }
}
