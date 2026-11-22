package com.riccon.worker.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M04 指标约定测试：冻结 7 个 Worker 指标的名称/类型/标签，并防止 Java 契约与抓取脚本漂移。
 *
 * <p>本测试不注册指标也不访问网络（B4 负责注册、B2 负责暴露、V2 负责抓取），只回答一个可机械核对的问题：
 * “ADR-0021 §10.6 的 7 个指标是否在代码与 {@code scripts/ops/metrics-check} 中保持同一份清单”。
 */
class M04MetricsContractTest {

    /** ADR-0021 §10.6 的 7 个指标名，顺序与 ADR 一致。 */
    private static final List<String> ADR_METRIC_NAMES = List.of(
            "riccon_model_requests_total",
            "riccon_model_failover_total",
            "riccon_model_attempt_duration_seconds",
            "riccon_model_credential_invalid_total",
            "riccon_model_credential_cooldown_total",
            "riccon_model_chain_empty_total",
            "riccon_agent_runs_inflight");

    /** 契约必须恰好覆盖 ADR 的 7 个指标，不多不少。 */
    @Test
    @DisplayName("OPS-M04-02a：契约恰好定义 ADR-0021 §10.6 的 7 个指标")
    void contractDefinesExactlyTheAdrMetrics() {
        assertThat(M04MetricsContract.METRICS)
                .extracting(M04MetricsContract.Metric::name)
                .containsExactlyElementsOf(ADR_METRIC_NAMES);
    }

    /** 指标名必须是 riccon_ 前缀且唯一，避免与框架指标或彼此冲突。 */
    @Test
    void metricNamesArePrefixedAndUnique() {
        List<String> names = M04MetricsContract.METRICS.stream()
                .map(M04MetricsContract.Metric::name)
                .toList();

        assertThat(names).allSatisfy(name -> assertThat(name).startsWith("riccon_"));
        assertThat(names).doesNotHaveDuplicates();
    }

    /** 必需标签不得包含 tenant/userId/runId 等高基数键（ADR-0008、ADR-0021 §10.6）。 */
    @Test
    void requiredLabelsNeverUseHighCardinalityKeys() {
        for (M04MetricsContract.Metric metric : M04MetricsContract.METRICS) {
            assertThat(metric.requiredLabels())
                    .as("%s 的标签", metric.name())
                    .doesNotContainAnyElementsOf(M04MetricsContract.FORBIDDEN_LABELS);
        }
    }

    /** 抓取脚本与 Java 契约必须是同一份清单；任一侧单改都会在这里失败。 */
    @Test
    @DisplayName("OPS-M04-02a：metrics-check 脚本与 Java 契约一致")
    void scriptMatchesJavaContract() throws IOException {
        assertThat(parseScriptSpecs()).containsExactlyElementsOf(M04MetricsContract.METRICS);
    }

    /** 解析 `scripts/ops/metrics-check` 里的冻结清单（`'name|type|labels'` 行）。 */
    private static List<M04MetricsContract.Metric> parseScriptSpecs() throws IOException {
        List<M04MetricsContract.Metric> specs = new ArrayList<>();
        for (String raw : Files.readAllLines(resolveScript(), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (!line.startsWith("'riccon_")) {
                continue;
            }
            String spec = line.substring(1, line.lastIndexOf('\''));
            String[] parts = spec.split("\\|", -1);
            assertThat(parts).as("脚本清单格式：%s", line).hasSize(3);
            Set<String> labels = parts[2].isEmpty() ? Set.of() : Set.of(parts[2].split(","));
            specs.add(new M04MetricsContract.Metric(
                    parts[0], M04MetricsContract.Type.valueOf(parts[1].toUpperCase(Locale.ROOT)), labels));
        }
        return specs;
    }

    /** 兼容从模块目录（Maven surefire 默认）或仓库根运行两种工作目录。 */
    private static Path resolveScript() {
        Path fromModule = Path.of("..", "..", "scripts", "ops", "metrics-check");
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("scripts", "ops", "metrics-check");
        assertThat(Files.isRegularFile(fromRoot))
                .as("找不到 scripts/ops/metrics-check；工作目录=%s", Path.of("").toAbsolutePath())
                .isTrue();
        return fromRoot;
    }
}
