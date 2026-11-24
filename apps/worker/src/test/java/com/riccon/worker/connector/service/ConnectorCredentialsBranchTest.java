package com.riccon.worker.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.worker.connector.domain.ConnectorConfig;
import com.riccon.worker.connector.domain.ConnectorCredential;
import com.riccon.worker.connector.domain.ConnectorDataKeyRow;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 凭据解密分支矩阵：未配置、DEK 缺失/不可用、密文 JSON 非法、字段不完整与成功路径。
 */
class ConnectorCredentialsBranchTest {

    /** 测试租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-cred");

    /** 测试 keyId。 */
    private static final String KEY_ID = "cn-test";

    /** 测试 DEK。 */
    private static final byte[] DEK = new byte[32];

    static {
        for (int index = 0; index < DEK.length; index++) {
            DEK[index] = (byte) index;
        }
    }

    @Test
    void decrypt_rejectsMissingCredentialAndDataKey() {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        MasterKeyProvider master = mock(MasterKeyProvider.class);
        ConnectorCredentials credentials = new ConnectorCredentials(repository, master, new ObjectMapper());

        assertThatThrownBy(() -> credentials.decrypt(TENANT, record(null, null)))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(repository, master);

        ConnectorRecord withCredential = record(ciphertext("{}"), KEY_ID);
        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> credentials.decrypt(TENANT, withCredential))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("data key is not available");
    }

    @Test
    void decrypt_rejectsUnusableDataKey() {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        MasterKeyProvider master = mock(MasterKeyProvider.class);
        ConnectorCredentials credentials = new ConnectorCredentials(repository, master, new ObjectMapper());
        ConnectorRecord connector = record(ciphertext("{}"), KEY_ID);

        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.of(new ConnectorDataKeyRow(new byte[] {1}, new byte[] {2}, "AES-128", "mk")));
        assertThatThrownBy(() -> credentials.decrypt(TENANT, connector))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("not usable");

        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.of(new ConnectorDataKeyRow(null, new byte[] {2}, "AES-256-GCM", "mk")));
        assertThatThrownBy(() -> credentials.decrypt(TENANT, connector))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class);

        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.of(new ConnectorDataKeyRow(new byte[] {1}, null, "AES-256-GCM", "mk")));
        assertThatThrownBy(() -> credentials.decrypt(TENANT, connector))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class);
    }

    @Test
    void decrypt_rejectsInvalidAndIncompletePayload() {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        MasterKeyProvider master = mock(MasterKeyProvider.class);
        when(master.unwrap(any(), any(), any())).thenReturn(DEK);
        ConnectorCredentials credentials = new ConnectorCredentials(repository, master, new ObjectMapper());
        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.of(new ConnectorDataKeyRow(new byte[] {1}, new byte[] {2}, "AES-256-GCM", "mk")));

        assertThatThrownBy(() -> credentials.decrypt(TENANT, record(ciphertext("not-json"), KEY_ID)))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> credentials.decrypt(TENANT, record(ciphertext("{\"accessKeyId\":\"a\"}"), KEY_ID)))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("incomplete");
        assertThatThrownBy(() -> credentials.decrypt(
                        TENANT, record(ciphertext("{\"accessKeyId\":\"\",\"secretAccessKey\":\"secret\"}"), KEY_ID)))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("incomplete");
        assertThatThrownBy(() -> credentials.decrypt(
                        TENANT, record(ciphertext("{\"accessKeyId\":\"access\",\"secretAccessKey\":\"\"}"), KEY_ID)))
                .isInstanceOf(ConnectorCredentials.ConnectorCredentialException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void decrypt_returnsPlainCredentialAndCloses() {
        ConnectorRepository repository = mock(ConnectorRepository.class);
        MasterKeyProvider master = mock(MasterKeyProvider.class);
        when(master.unwrap(any(), any(), any())).thenReturn(DEK);
        ConnectorCredentials credentials = new ConnectorCredentials(repository, master, new ObjectMapper());
        when(repository.findDataKey(TENANT, KEY_ID, ConnectorCredentials.PURPOSE))
                .thenReturn(Optional.of(new ConnectorDataKeyRow(new byte[] {1}, new byte[] {2}, "AES-256-GCM", "mk")));

        try (ConnectorCredential plain = credentials.decrypt(
                TENANT,
                record(ciphertext("{\"accessKeyId\":\"access-key\",\"secretAccessKey\":\"secret-key\"}"), KEY_ID))) {
            assertThat(new String(plain.accessKeyId())).isEqualTo("access-key");
            assertThat(new String(plain.secretAccessKey())).isEqualTo("secret-key");
        }
    }

    /**
     * 加密测试 payload。
     *
     * @param plaintext 明文
     * @return 密文字节
     */
    private static byte[] ciphertext(String plaintext) {
        return CredentialEnvelope.encrypt(
                        TENANT.value(),
                        ConnectorCredentials.PURPOSE,
                        KEY_ID,
                        DEK,
                        plaintext.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造连接器快照。
     *
     * @param ciphertext 密文
     * @param keyId keyId
     * @return 连接器
     */
    private static ConnectorRecord record(byte[] ciphertext, String keyId) {
        return new ConnectorRecord(
                UUID.randomUUID(),
                "connector",
                ConnectorType.S3,
                ConnectorStatus.PAUSED,
                "endpoint",
                new ConnectorConfig("bucket", "prefix", "us-east-1", null, null),
                KnowledgeVisibility.TENANT,
                null,
                ciphertext,
                keyId,
                false,
                null,
                ConnectorSyncStatus.SUCCESS,
                null,
                Instant.now(),
                null);
    }
}
