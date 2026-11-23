package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.riccon.worker.config.StorageProperties;
import com.riccon.worker.connector.service.ConnectorObjectWriter;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 平台对象写入硬上限：超过 100 MB 在调用 S3 前中止。
 */
class ConnectorObjectWriterTest {

    @Test
    void write_rejectsObjectOverHardLimitBeforeUpload() throws Exception {
        S3Client client = mock(S3Client.class);
        ConnectorObjectWriter writer = new ConnectorObjectWriter(
                client,
                new StorageProperties("http://127.0.0.1:19000", "us-east-1", "key", "secret", "platform-bucket", true));
        Path file = Files.createTempFile("connector-writer-", ".tmp");
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "rw")) {
            randomAccessFile.setLength(ConnectorObjectWriter.MAX_OBJECT_BYTES + 1);
        }
        try {
            assertThatThrownBy(() -> writer.write("tenants/t/connector/2026/doc", file, "application/pdf"))
                    .isInstanceOf(ConnectorObjectWriter.ObjectWriteException.class)
                    .hasMessage("KB_CONNECTOR_OBJECT_TOO_LARGE");
            verifyNoInteractions(client);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
