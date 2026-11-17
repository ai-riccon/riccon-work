package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.persistence.UuidBinary;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
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
                storageEndpoint(), "us-east-1", "riccon_test_only", "riccon_test_only_secret", BUCKET, true);
        S3FileContentReader tiny = new S3FileContentReader(s3Client, storage, new ParseProperties(4, 100, 10));

        assertThatThrownBy(() -> tiny.read(TenantId.of(tenant), file))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> assertThat(((ParseException) ex).errorCode()).isEqualTo("RICCON-FILE-413"));
    }
}
