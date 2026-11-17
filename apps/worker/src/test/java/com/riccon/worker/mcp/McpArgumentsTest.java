package com.riccon.worker.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.mcp.domain.StdioCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 工具参数规范化、脱敏预览与 stdio 命令拆分。 */
class McpArgumentsTest {

    /** 规范化按递归键排序并排除 null。 */
    @Test
    void canonical_sortsAndDropsNull() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", 1);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", "last");
        input.put("nested", nested);
        input.put("empty", null);

        Map<String, Object> canonical = ArgumentDigest.canonical(input);

        assertThat(canonical.keySet()).containsExactly("nested", "z");
        assertThat(((Map<?, ?>) canonical.get("nested"))
                        .keySet().stream().map(String::valueOf).toList())
                .containsExactly("a", "b");
        assertThat(Digests.sha256Hex("abc")).hasSize(64);
        assertThat(Digests.sha256Hex("abc")).isEqualTo(Digests.sha256Hex("abc"));
    }

    /** 预览脱敏敏感键并截断长值。 */
    @Test
    void preview_redactsSensitiveKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "很长的文本".repeat(100));
        input.put("apiKey", "secret");
        input.put("password", "pw");

        Map<String, Object> preview = ArgumentDigest.preview(input, 100);

        assertThat(preview.get("apiKey")).isEqualTo("[redacted]");
        assertThat(preview.get("password")).isEqualTo("[redacted]");
        assertThat((String) preview.get("query")).endsWith("...");
        assertThat(ArgumentDigest.hasSensitiveKey(input)).isTrue();
        assertThat(ArgumentDigest.hasSensitiveKey(Map.of("query", "hello"))).isFalse();
    }

    /** stdio 命令支持引号与转义，拒绝未闭合引号。 */
    @Test
    void stdioCommand_splitsQuotedArguments() {
        assertThat(StdioCommand.split("java -cp \"a b\" Main")).containsExactly("java", "-cp", "a b", "Main");
        assertThat(StdioCommand.split("'single quoted' plain")).containsExactly("single quoted", "plain");
        assertThat(StdioCommand.split("a\\ b c")).containsExactly("a b", "c");
        assertThat(StdioCommand.split("mix'inside'quote")).containsExactly("mixinsidequote");
        assertThatThrownByUnterminated();
    }

    /** 断言未闭合引号失败。 */
    private static void assertThatThrownByUnterminated() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StdioCommand.split("\"open"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StdioCommand.split("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 空参数预览返回空映射。 */
    @Test
    void preview_empty_returnsEmpty() {
        assertThat(ArgumentDigest.preview(null, 10)).isEmpty();
        assertThat(ArgumentDigest.canonical(null)).isEmpty();
        assertThat(StdioCommand.split("cmd")).isEqualTo(List.of("cmd"));
    }
}
