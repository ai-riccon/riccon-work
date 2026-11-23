package com.riccon.worker.connector.service;

import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.connector.domain.ConnectorCredential;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 连接器凭据解密。
 *
 * <p>密文格式与 AAD 由 {@link CredentialEnvelope} 统一：{@code tenantId|connector-credential|keyId|1}。
 * DEK 从 {@code tenant_data_key} 读取并由 Worker 主密钥解开；明文以 byte[]/char[] 传递，使用后清零，
 * 不写日志、不进入异常消息（ADR-0023 第 6.4 节、SEC-CONN-001）。
 */
@Component
public class ConnectorCredentials {

    /** 凭据用途。 */
    public static final String PURPOSE = "connector-credential";

    /** 算法。 */
    private static final String ALGORITHM = "AES-256-GCM";

    /** 仓储。 */
    private final ConnectorRepository connectorRepository;

    /** 主密钥提供者。 */
    private final MasterKeyProvider masterKeyProvider;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建解密组件。
     *
     * @param connectorRepository 仓储
     * @param masterKeyProvider 主密钥提供者
     * @param objectMapper JSON 解析
     */
    public ConnectorCredentials(
            ConnectorRepository connectorRepository, MasterKeyProvider masterKeyProvider, ObjectMapper objectMapper) {
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.masterKeyProvider = Objects.requireNonNull(masterKeyProvider, "masterKeyProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 解密连接器凭据。
     *
     * @param tenantId 租户
     * @param connector 连接器
     * @return 明文凭据；调用方必须关闭
     * @throws ConnectorCredentialException 未配置、DEK 不可用或密文不可解
     */
    public ConnectorCredential decrypt(TenantId tenantId, ConnectorRecord connector) {
        if (!connector.hasCredential()) {
            throw new ConnectorCredentialException("connector credential is not configured");
        }
        var key = connectorRepository
                .findDataKey(tenantId, connector.credentialKeyId(), PURPOSE)
                .orElseThrow(() -> new ConnectorCredentialException("connector data key is not available"));
        if (!ALGORITHM.equals(key.algorithm()) || key.wrappedDek() == null || key.wrapNonce() == null) {
            throw new ConnectorCredentialException("connector data key is not usable");
        }
        byte[] aad = CredentialEnvelope.aad(tenantId.value(), PURPOSE, connector.credentialKeyId());
        byte[] dek = masterKeyProvider.unwrap(key.wrapNonce(), key.wrappedDek(), aad);
        byte[] plaintext = null;
        try {
            String ciphertext = new String(connector.credentialCiphertext(), StandardCharsets.UTF_8);
            plaintext =
                    CredentialEnvelope.decrypt(tenantId.value(), PURPOSE, connector.credentialKeyId(), dek, ciphertext);
            CredentialPayload payload = objectMapper.readValue(plaintext, CredentialPayload.class);
            if (payload.accessKeyId() == null
                    || payload.accessKeyId().isEmpty()
                    || payload.secretAccessKey() == null
                    || payload.secretAccessKey().isEmpty()) {
                throw new ConnectorCredentialException("connector credential payload is incomplete");
            }
            return new ConnectorCredential(
                    payload.accessKeyId().toCharArray(),
                    payload.secretAccessKey().toCharArray());
        } catch (JacksonException exception) {
            throw new ConnectorCredentialException("connector credential payload is invalid");
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * 凭据 JSON 结构。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     */
    private record CredentialPayload(String accessKeyId, String secretAccessKey) {}

    /**
     * 凭据不可用；不输出底层细节。
     */
    public static class ConnectorCredentialException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param message 脱敏说明
         */
        public ConnectorCredentialException(String message) {
            super(message);
        }
    }
}
