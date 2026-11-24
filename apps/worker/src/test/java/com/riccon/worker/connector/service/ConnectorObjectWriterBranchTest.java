package com.riccon.worker.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.connector.domain.ConnectorErrorCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * ConnectorObjectWriter 分支矩阵：成功写入、认证/服务端/客户端/IO 失败分类与平台桶派生。
 */
class ConnectorObjectWriterBranchTest {

    @Test
    void write_successUsesPlatformBucketAndReturnsBucketName() throws Exception {
        S3Client client = mock(S3Client.class);
        ConnectorObjectWriter writer = writer(client);
        Path file = Files.createTempFile("connector-writer-", ".tmp");
        try {
            Files.writeString(file, "payload");
            writer.write("tenants/t/connector/2026/doc", file, "text/plain");
            verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            assertThat(writer.platformBucket()).isEqualTo("platform-bucket");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void write_mapsAuthAndServiceFailures() throws Exception {
        Path file = Files.createTempFile("connector-writer-", ".tmp");
        Files.writeString(file, "payload");
        try {
            assertFailure(
                    clientThrowing(S3Exception.builder()
                            .statusCode(403)
                            .message("denied")
                            .build()),
                    file,
                    ConnectorErrorCodes.AUTH);
            assertFailure(
                    clientThrowing(S3Exception.builder()
                            .statusCode(401)
                            .message("unauthorized")
                            .build()),
                    file,
                    ConnectorErrorCodes.AUTH);
            assertFailure(
                    clientThrowing(S3Exception.builder()
                            .statusCode(500)
                            .message("server")
                            .build()),
                    file,
                    ConnectorErrorCodes.OBJECT_FAILED);
            assertFailure(clientThrowing(SdkClientException.create("client")), file, ConnectorErrorCodes.OBJECT_FAILED);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void write_mapsMissingFileIoFailure() {
        S3Client client = mock(S3Client.class);
        ConnectorObjectWriter writer = writer(client);
        Path missing = Path.of("/tmp/riccon-connector-missing-" + System.nanoTime());
        assertThatThrownBy(() -> writer.write("tenants/t/connector/2026/doc", missing, "text/plain"))
                .isInstanceOf(ConnectorObjectWriter.ObjectWriteException.class)
                .hasMessage(ConnectorErrorCodes.OBJECT_FAILED);
    }

    /**
     * 构造写入器。
     *
     * @param client S3 客户端
     * @return 写入器
     */
    private static ConnectorObjectWriter writer(S3Client client) {
        return new ConnectorObjectWriter(
                client,
                new StorageProperties("http://127.0.0.1:19000", "us-east-1", "key", "secret", "platform-bucket", true));
    }

    /**
     * 构造抛异常的客户端。
     *
     * @param exception 异常
     * @return 客户端
     */
    private static S3Client clientThrowing(RuntimeException exception) {
        S3Client client = mock(S3Client.class);
        doThrow(exception).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        return client;
    }

    /**
     * 断言写入失败错误码。
     *
     * @param client 客户端
     * @param file 文件
     * @param expectedCode 期望错误码
     */
    private static void assertFailure(S3Client client, Path file, String expectedCode) {
        assertThatThrownBy(() -> writer(client).write("tenants/t/connector/2026/doc", file, "text/plain"))
                .isInstanceOf(ConnectorObjectWriter.ObjectWriteException.class)
                .hasMessage(expectedCode);
    }
}
