package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.OpenSearchProperties;
import com.riccon.worker.index.domain.IndexException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** OpenSearch HTTP 错误分类测试（不启动容器）。 */
class OpenSearchIndexClientErrorTest {

    /** 本地假 OpenSearch 服务。 */
    private HttpServer server;

    /** 每个测试后关闭假服务。 */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 5xx 必须是可重试错误，交给 outbox 退避。 */
    @Test
    void ensureIndex_5xx_isRetryable() {
        OpenSearchIndexClient client = clientReturning(503);

        assertThatThrownBy(() -> client.ensureIndex(TenantId.of("tenant-a"), 64))
                .isInstanceOf(IndexException.class)
                .satisfies(ex -> {
                    IndexException indexException = (IndexException) ex;
                    assertThat(indexException.retryable()).isTrue();
                    assertThat(indexException.errorCode()).isEqualTo("RICCON-INDEX-503");
                });
    }

    /** 4xx 表示请求/映射错误，不可重试。 */
    @Test
    void ensureIndex_4xx_isNotRetryable() {
        OpenSearchIndexClient client = clientReturning(400);

        assertThatThrownBy(() -> client.ensureIndex(TenantId.of("tenant-a"), 64))
                .isInstanceOf(IndexException.class)
                .satisfies(ex -> {
                    IndexException indexException = (IndexException) ex;
                    assertThat(indexException.retryable()).isFalse();
                    assertThat(indexException.errorCode()).isEqualTo("RICCON-INDEX-400");
                });
    }

    /**
     * 启动固定状态码的假服务。
     *
     * @param status HTTP 状态码
     * @return 指向假服务的客户端
     */
    private OpenSearchIndexClient clientReturning(int status) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start fake opensearch", ex);
        }
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        OpenSearchProperties properties = new OpenSearchProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "riccon-kb",
                "v1");
        return new OpenSearchIndexClient(properties, new ObjectMapper());
    }
}
