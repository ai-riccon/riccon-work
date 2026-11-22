package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 内层模型客户端重试基线的配置漂移测试。
 *
 * <p>ADR-0021 §5.2 要求内层 OpenAI 客户端固定 {@code riccon.worker.model.max-attempts=1}：
 * 所有重试/换 key/换模型由 B4 的 FailoverChatModel 拥有，保证 HTTP 调用数 = attempt 行数 = 降级审计条数。
 * 本测试直接读取 Worker 的 {@code application.yml}，防止后续任务误把内层重试加回来。
 */
class InnerModelRetryConfigTest {

    /** 内层模型客户端不得自行重试。 */
    @Test
    @DisplayName("M04 内层模型客户端 max-attempts=1")
    void workerYamlFixesInnerModelMaxAttemptsToOne() throws IOException {
        Path configuration = Path.of("src/main/resources/application.yml");
        if (!Files.isRegularFile(configuration)) {
            configuration = Path.of("apps/worker/src/main/resources/application.yml");
        }
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(configuration, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        Map<String, Object> riccon = map(root.get("riccon"));
        Map<String, Object> worker = map(riccon.get("worker"));
        Map<String, Object> model = map(worker.get("model"));
        assertThat(model.get("max-attempts")).isEqualTo(1);
        // 路由/降级预算由外层尝试次数控制，不能误改成 1。
        Map<String, Object> routing = map(model.get("routing"));
        assertThat(routing.get("max-attempts")).isEqualTo("${RICCON_MODEL_ROUTING_MAX_ATTEMPTS:3}");
    }

    /** 安全转换为字符串键映射。 */
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }
}
