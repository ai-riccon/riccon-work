package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.connector.config.ConnectorProperties;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorCredential;
import com.riccon.worker.connector.domain.ConnectorEndpointConfig;
import com.riccon.worker.connector.service.ConnectorS3ClientFactory;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接器 S3 客户端不跟随重定向（SEC-CONN-003、TM-61）。
 *
 * <p>用一个真实本地 HTTP 端点返回 {@code 302} 到第二个本地主机；如果 AWS SDK 自动跟随，第二台主机会收到请求，
 * 生产中等价于凭据被发往未列入部署允许列表的主机。测试只断言“第二个主机零请求”，不把 302 当作成功。
 */
class ConnectorS3RedirectSafetyTest {

    /** 响应体上限：重定向目标不需要真实对象。 */
    private static final String LIST_BUCKET_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Name>bucket</Name>
              <Prefix></Prefix>
              <KeyCount>0</KeyCount>
              <MaxKeys>1000</MaxKeys>
              <IsTruncated>false</IsTruncated>
            </ListBucketResult>
            """;

    @Test
    void listObjects_doesNotFollowRedirectToUnlistedHost() throws IOException {
        AtomicInteger redirectTargetRequests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            redirectTargetRequests.incrementAndGet();
            byte[] body = LIST_BUCKET_XML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();

        AtomicInteger allowlistedRequests = new AtomicInteger();
        HttpServer allowlisted = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        allowlisted.createContext("/", exchange -> {
            allowlistedRequests.incrementAndGet();
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/bucket");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        allowlisted.start();

        ConnectorProperties properties = new ConnectorProperties(new ObjectMapper(), new OutboxLaneProperties());
        properties.setObjectTimeout(Duration.ofSeconds(2));
        ConnectorS3ClientFactory factory = new ConnectorS3ClientFactory(properties);
        ConnectorEndpointConfig endpoint = new ConnectorEndpointConfig(
                "allowlisted", "http://127.0.0.1:" + allowlisted.getAddress().getPort(), "本地重定向测试", true, false);
        ConnectorConfig config = new ConnectorConfig("bucket", "", "us-east-1", List.of(), List.of());
        ConnectorCredential credential =
                new ConnectorCredential("v2b-access".toCharArray(), "v2b-secret".toCharArray());
        try (S3Client client = factory.build(endpoint, config, credential)) {
            assertThatThrownBy(() -> client.listObjectsV2(builder -> builder.bucket("bucket")))
                    .as("302 必须以错误返回，不能被 SDK 透明跟随")
                    .isInstanceOf(RuntimeException.class);
        } finally {
            allowlisted.stop(0);
            target.stop(0);
        }

        assertThat(allowlistedRequests.get()).as("允许列表端点应收到原始请求").isPositive();
        assertThat(redirectTargetRequests.get())
                .as("未列入允许列表的重定向目标必须零请求，不能只靠 ConnectorEndpointPolicy")
                .isZero();
    }
}
