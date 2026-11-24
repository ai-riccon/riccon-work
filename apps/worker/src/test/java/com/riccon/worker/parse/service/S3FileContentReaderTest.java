package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.connector.domain.ConnectorContentTypes;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.persistence.UuidBinary;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3 读取器的租户前缀与大小限制测试。 */
class S3FileContentReaderTest extends WorkerIntegrationTestBase {

    /** 生产配置的读取器。 */
    @Autowired
    private S3FileContentReader reader;

    /** 读取器需要 S3 客户端；大小限制测试使用独立实例。 */
    @Autowired
    private software.amazon.awssdk.services.s3.S3Client s3Client;

    /** 跨租户对象 key 必须按不存在处理。 */
    @Test
    void read_crossTenantKey_rejected() {
        FileRecord file = new FileRecord(
                UuidBinary.newV7(),
                "other.pdf",
                FileFormat.PDF,
                "application/pdf",
                10,
                "tenants/tenant-b/file/2026/object");

        assertThatThrownBy(() -> reader.read(TenantId.of("tenant-a"), file))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> {
                    ParseException parseException = (ParseException) ex;
                    assertThat(parseException.errorCode()).isEqualTo("RICCON-TENANT-404");
                    assertThat(parseException.retryable()).isFalse();
                });
    }

    /** 超过配置上限的文件在下载前拒绝。 */
    @Test
    void read_oversizedFile_rejected() {
        String tenant = "tenant-a";
        UUID fileId = UuidBinary.newV7();
        String key = "tenants/" + tenant + "/file/2026/" + fileId;
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(new byte[16]));
        FileRecord file = new FileRecord(fileId, "large.pdf", FileFormat.PDF, "application/pdf", 16, key);
        StorageProperties storage = new StorageProperties(
                storageEndpoint(), "us-east-1", storageAccessKey(), storageSecretKey(), BUCKET, true);
        S3FileContentReader tiny = new S3FileContentReader(s3Client, storage, new ParseProperties(4, 100, 10));

        assertThatThrownBy(() -> tiny.read(TenantId.of(tenant), file))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).errorCode()).isEqualTo("RICCON-FILE-413"));
    }

    /** 按 B6 派生的连接器 key 读回平台桶对象。 */
    @Test
    void readConnector_readsObjectAtB6Key() {
        String tenant = "tenant-conn-read";
        UUID documentId = UuidBinary.newV7();
        Instant createdAt = Instant.parse("2026-03-01T10:15:30Z");
        byte[] bytes = "connector body".getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(ConnectorContentTypes.objectKey(tenant, documentId, createdAt))
                        .build(),
                RequestBody.fromBytes(bytes));

        assertThat(reader.readConnector(TenantId.of(tenant), documentId, createdAt))
                .isEqualTo(bytes);
    }

    /** 连接器对象缺失按不存在处理，不泄露 bucket/key。 */
    @Test
    void readConnector_missingObject_notFound() {
        assertThatThrownBy(() ->
                        reader.readConnector(TenantId.of("tenant-conn-missing"), UuidBinary.newV7(), Instant.now()))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> {
                    ParseException parseException = (ParseException) ex;
                    assertThat(parseException.errorCode()).isEqualTo("RICCON-TENANT-404");
                    assertThat(parseException.retryable()).isFalse();
                    assertThat(parseException.getMessage()).doesNotContain("tenants/");
                });
    }

    /** 连接器对象同样受解析大小上限约束。 */
    @Test
    void readConnector_oversizedObject_rejected() {
        String tenant = "tenant-conn-large";
        UUID documentId = UuidBinary.newV7();
        Instant createdAt = Instant.now();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(ConnectorContentTypes.objectKey(tenant, documentId, createdAt))
                        .build(),
                RequestBody.fromBytes(new byte[16]));
        StorageProperties storage = new StorageProperties(
                storageEndpoint(), "us-east-1", storageAccessKey(), storageSecretKey(), BUCKET, true);
        S3FileContentReader tiny = new S3FileContentReader(s3Client, storage, new ParseProperties(4, 100, 10));

        assertThatThrownBy(() -> tiny.readConnector(TenantId.of(tenant), documentId, createdAt))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).errorCode()).isEqualTo("RICCON-FILE-413"));
    }

    /** key 带租户前缀：同一文档 ID 在 B 租户的对象不能被 A 租户读取。 */
    @Test
    void readConnector_crossTenantIsNotReadable() {
        String tenantB = "tenant-conn-b";
        UUID documentId = UuidBinary.newV7();
        Instant createdAt = Instant.now();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(ConnectorContentTypes.objectKey(tenantB, documentId, createdAt))
                        .build(),
                RequestBody.fromBytes("other tenant".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> reader.readConnector(TenantId.of("tenant-conn-a"), documentId, createdAt))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).errorCode()).isEqualTo("RICCON-TENANT-404"));
    }

    /** 存储不可用按可重试错误返回，由 outbox 退避重试而不是标记文档永久失败。 */
    @Test
    void readConnector_storageUnavailable_retryable() {
        StorageProperties storage = new StorageProperties(
                storageEndpoint(), "us-east-1", storageAccessKey(), storageSecretKey(), BUCKET, true);
        try (S3Client unreachable = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:1"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storageAccessKey(), storageSecretKey())))
                .forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(2))
                        .build())
                .build()) {
            S3FileContentReader failing =
                    new S3FileContentReader(unreachable, storage, new ParseProperties(52428800L, 800, 80));

            assertThatThrownBy(() ->
                            failing.readConnector(TenantId.of("tenant-conn-down"), UuidBinary.newV7(), Instant.now()))
                    .isInstanceOf(ParseException.class)
                    .satisfies(ex -> {
                        ParseException parseException = (ParseException) ex;
                        assertThat(parseException.errorCode()).isEqualTo("RICCON-FILE-503");
                        assertThat(parseException.retryable()).isTrue();
                    });
        }
    }
}
