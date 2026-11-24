package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.service.S3FileContentReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3FileContentReader 分支矩阵：租户前缀、HEAD 大小/缺对象、GET 错误翻译与连接器平台 key 派生。
 */
class S3FileContentReaderBranchTest {

    /** 测试租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-reader");

    @Test
    void read_rejectsForeignTenantKeyAndOversizedObject() {
        S3Client client = mock(S3Client.class);
        S3FileContentReader reader = reader(client, 10);
        FileRecord foreign = file("tenants/other/file.pdf", 1);
        assertThatThrownBy(() -> reader.read(TENANT, foreign))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo("RICCON-TENANT-404"));

        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(11L).build());
        FileRecord own = file("tenants/" + TENANT.value() + "/file.pdf", 1);
        assertThatThrownBy(() -> reader.read(TENANT, own))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo("RICCON-FILE-413"));
    }

    @Test
    void read_translatesHeadAndGetFailures() {
        FileRecord own = file("tenants/" + TENANT.value() + "/file.pdf", 1);

        S3Client noSuchKey = mock(S3Client.class);
        when(noSuchKey.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertNotFound(reader(noSuchKey, 100), own);

        S3Client forbidden = mock(S3Client.class);
        when(forbidden.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(403)
                        .message("forbidden")
                        .build());
        assertNotFound(reader(forbidden, 100), own);

        S3Client unavailable = mock(S3Client.class);
        when(unavailable.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("down").build());
        assertThatThrownBy(() -> reader(unavailable, 100).read(TENANT, own))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo("RICCON-FILE-503"));

        S3Client sdkFailure = mock(S3Client.class);
        when(sdkFailure.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("network"));
        assertThatThrownBy(() -> reader(sdkFailure, 100).read(TENANT, own))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo("RICCON-FILE-503"));

        S3Client getFailure = mock(S3Client.class);
        when(getFailure.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).build());
        when(getFailure.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("gone").build());
        assertNotFound(reader(getFailure, 100), own);
    }

    @Test
    void read_successAndVerifyExistsBranches() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .build());
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));
        FileRecord own = file("tenants/" + TENANT.value() + "/file.pdf", content.length);
        assertThat(reader(client, 100).read(TENANT, own)).isEqualTo(content);
        reader(client, 100).verifyExists(TENANT, own);
        verify(client, atLeastOnce()).headObject(any(HeadObjectRequest.class));

        S3Client verifyClient = mock(S3Client.class);
        when(verifyClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertNotFound(reader(verifyClient, 100), own);
    }

    @Test
    void readConnector_derivesPlatformKeyAndTranslatesFailure() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("network"));
        UUID documentId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-02T03:04:05Z");
        assertThatThrownBy(() -> reader(client, 100).readConnector(TENANT, documentId, createdAt))
                .isInstanceOf(ParseException.class);
        verify(client, atLeastOnce()).headObject(any(HeadObjectRequest.class));
        assertThat(S3FileContentReader.connectorObjectKey(TENANT, documentId, createdAt))
                .isEqualTo("tenants/tenant-reader/connector/2026/" + documentId);

        assertThatThrownBy(() -> S3FileContentReader.connectorObjectKey(null, documentId, createdAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> S3FileContentReader.connectorObjectKey(TENANT, null, createdAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> S3FileContentReader.connectorObjectKey(TENANT, documentId, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 构造读取器。
     *
     * @param client S3 客户端
     * @param maxFileBytes 文件上限
     * @return 读取器
     */
    private static S3FileContentReader reader(S3Client client, long maxFileBytes) {
        return new S3FileContentReader(
                client,
                new StorageProperties("http://127.0.0.1:19000", "us-east-1", "key", "secret", "platform", true),
                new ParseProperties(maxFileBytes, 800, 80));
    }

    /**
     * 构造文件记录。
     *
     * @param key 对象 key
     * @param size 大小
     * @return 文件
     */
    private static FileRecord file(String key, long size) {
        return new FileRecord(UUID.randomUUID(), "file.pdf", FileFormat.PDF, "application/pdf", size, key);
    }

    /**
     * 断言不存在。
     *
     * @param reader 读取器
     * @param file 文件
     */
    private static void assertNotFound(S3FileContentReader reader, FileRecord file) {
        assertThatThrownBy(() -> reader.read(TENANT, file))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo("RICCON-TENANT-404"));
    }
}
