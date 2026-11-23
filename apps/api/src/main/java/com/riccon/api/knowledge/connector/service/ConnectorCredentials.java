package com.riccon.api.knowledge.connector.service;

import com.riccon.api.crypto.CredentialCipher;
import com.riccon.api.crypto.MasterKeyProvider;
import com.riccon.api.knowledge.connector.domain.ConnectorDataKeyRow;
import com.riccon.api.knowledge.connector.domain.ConnectorRecord;
import com.riccon.api.knowledge.connector.repository.ConnectorRepository;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接器凭据信封加密与解密。
 *
 * <p>凭据以 S3 access key JSON 存储：{@code purpose=connector-credential}，AAD 由
 * {@link CredentialEnvelope#aad(String, String, String)} 生成 {@code tenantId|connector-credential|keyId|1}；
 * 密文以 UTF-8 字节写入 {@code kb_connector.credential_ciphertext}，DEK 由主密钥包裹后写入
 * {@code tenant_data_key}（ADR-0006、ADR-0023 第 6.4 节）。
 *
 * <p>API 只加密与连接测试解密；Worker 使用同一格式解密。明文只在一次调用内存在，返回的
 * {@link PlainCredential} 由调用方用后清零；日志与异常不包含明文、密文、DEK 或 keyId 之外的材料。
 */
@Component
public class ConnectorCredentials {

    /** 凭据用途。 */
    public static final String PURPOSE = "connector-credential";

    /** DEK 算法。 */
    private static final String DEK_ALGORITHM = "AES-256-GCM";

    /** DEK 长度。 */
    private static final int DEK_BYTES = 32;

    /** 掩码最短可展示长度。 */
    private static final int MIN_MASK_LENGTH = 8;

    /** 进程级安全随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 主密钥提供者。 */
    private final MasterKeyProvider masterKeyProvider;

    /** 信封加密器。 */
    private final CredentialCipher credentialCipher;

    /** 连接器仓储。 */
    private final ConnectorRepository connectorRepository;

    /** JSON 编解码。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建凭据组件。
     *
     * @param masterKeyProvider 主密钥提供者
     * @param credentialCipher 信封加密器
     * @param connectorRepository 连接器仓储
     * @param objectMapper JSON 编解码
     */
    public ConnectorCredentials(
            MasterKeyProvider masterKeyProvider,
            CredentialCipher credentialCipher,
            ConnectorRepository connectorRepository,
            ObjectMapper objectMapper) {
        this.masterKeyProvider = Objects.requireNonNull(masterKeyProvider, "masterKeyProvider must not be null");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher must not be null");
        this.connectorRepository = Objects.requireNonNull(connectorRepository, "connectorRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 加密凭据并返回密文、keyId 与掩码。
     *
     * @param tenantId 当前租户
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     * @return 加密结果
     */
    public Sealed encrypt(TenantId tenantId, String accessKeyId, String secretAccessKey) {
        byte[] plaintext = credentialJson(accessKeyId, secretAccessKey);
        ActiveKey key = activeDataKey(tenantId);
        try {
            String ciphertext = credentialCipher.encrypt(
                    tenantId.value(), PURPOSE, key.keyId(), key.dek(), new String(plaintext, StandardCharsets.UTF_8));
            return new Sealed(ciphertext.getBytes(StandardCharsets.UTF_8), key.keyId(), maskedHint(accessKeyId));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key.dek(), (byte) 0);
        }
    }

    /**
     * 解密连接器凭据。
     *
     * @param tenantId 当前租户
     * @param connector 连接器
     * @return 明文凭据；调用方必须 {@link PlainCredential#close()} 清零
     * @throws CredentialUnavailableException 未配置凭据或密文不可解
     */
    public PlainCredential decrypt(TenantId tenantId, ConnectorRecord connector) {
        if (!connector.hasCredential()) {
            throw new CredentialUnavailableException("connector credential is not configured");
        }
        ConnectorDataKeyRow key = connectorRepository
                .findDataKey(tenantId, PURPOSE, connector.credentialKeyId())
                .orElseThrow(() -> new CredentialUnavailableException("connector data key is not available"));
        if (!DEK_ALGORITHM.equals(key.algorithm()) || key.wrappedDek() == null || key.wrapNonce() == null) {
            throw new CredentialUnavailableException("connector data key is not usable");
        }
        byte[] aad = CredentialEnvelope.aad(tenantId.value(), PURPOSE, key.keyId());
        byte[] dek = masterKeyProvider.unwrap(key.wrapNonce(), key.wrappedDek(), aad);
        byte[] plaintext = null;
        try {
            String ciphertext = new String(connector.credentialCiphertext(), StandardCharsets.UTF_8);
            plaintext = CredentialEnvelope.decrypt(tenantId.value(), PURPOSE, key.keyId(), dek, ciphertext);
            CredentialPayload payload = objectMapper.readValue(plaintext, CredentialPayload.class);
            if (payload.accessKeyId() == null
                    || payload.accessKeyId().isEmpty()
                    || payload.secretAccessKey() == null
                    || payload.secretAccessKey().isEmpty()) {
                throw new CredentialUnavailableException("connector credential payload is incomplete");
            }
            return new PlainCredential(
                    payload.accessKeyId().toCharArray(),
                    payload.secretAccessKey().toCharArray());
        } catch (JacksonException exception) {
            throw new CredentialUnavailableException("connector credential payload is invalid");
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * 计算掩码：前 4 位 + 省略号 + 后 3 位；过短时只返回 {@code ****}。
     *
     * @param accessKeyId 访问键
     * @return 掩码
     */
    public static String maskedHint(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.length() < MIN_MASK_LENGTH) {
            return "****";
        }
        return accessKeyId.substring(0, 4) + "…" + accessKeyId.substring(accessKeyId.length() - 3);
    }

    /**
     * 读取或懒生成连接器凭据 DEK。
     *
     * @param tenantId 当前租户
     * @return 活跃 DEK
     */
    private ActiveKey activeDataKey(TenantId tenantId) {
        var existing = connectorRepository.findActiveDataKey(tenantId, PURPOSE);
        if (existing.isPresent()) {
            ConnectorDataKeyRow row = existing.get();
            if (!DEK_ALGORITHM.equals(row.algorithm()) || row.wrappedDek() == null || row.wrapNonce() == null) {
                throw new IllegalStateException("connector credential data key is not usable");
            }
            byte[] aad = CredentialEnvelope.aad(tenantId.value(), PURPOSE, row.keyId());
            byte[] dek = masterKeyProvider.unwrap(row.wrapNonce(), row.wrappedDek(), aad);
            return new ActiveKey(row.keyId(), dek);
        }
        byte[] dek = new byte[DEK_BYTES];
        RANDOM.nextBytes(dek);
        String keyId = "cn-" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] aad = CredentialEnvelope.aad(tenantId.value(), PURPOSE, keyId);
            var wrapped = masterKeyProvider.wrap(dek, aad);
            connectorRepository.insertDataKey(
                    tenantId,
                    keyId,
                    PURPOSE,
                    wrapped.wrappedDek(),
                    wrapped.nonce(),
                    DEK_ALGORITHM,
                    masterKeyProvider.masterKeyId());
            return new ActiveKey(keyId, dek);
        } catch (RuntimeException exception) {
            Arrays.fill(dek, (byte) 0);
            throw exception;
        }
    }

    /**
     * 生成凭据 JSON 字节。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     * @return UTF-8 JSON
     */
    private byte[] credentialJson(String accessKeyId, String secretAccessKey) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accessKeyId", accessKeyId);
        values.put("secretAccessKey", secretAccessKey);
        try {
            return objectMapper.writeValueAsBytes(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize connector credential", exception);
        }
    }

    /**
     * 活跃 DEK 与明文；只在一次加密调用内存在。
     *
     * @param keyId DEK 标识
     * @param dek 明文 DEK
     */
    private record ActiveKey(String keyId, byte[] dek) {}

    /**
     * 加密结果。
     *
     * @param ciphertext 密文字节
     * @param keyId DEK 标识
     * @param maskedHint 掩码
     */
    public record Sealed(byte[] ciphertext, String keyId, String maskedHint) {

        /** 防御性复制。 */
        public Sealed {
            ciphertext = ciphertext.clone();
        }

        /** 返回密文副本，避免调用方修改内部状态。 */
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    /**
     * 解密后的明文凭据；关闭时清零字符数组。
     */
    public static final class PlainCredential implements AutoCloseable {

        /** 访问键字符。 */
        private final char[] accessKeyId;

        /** 访问密钥字符。 */
        private final char[] secretAccessKey;

        /**
         * 创建明文凭据。
         *
         * @param accessKeyId 访问键
         * @param secretAccessKey 访问密钥
         */
        PlainCredential(char[] accessKeyId, char[] secretAccessKey) {
            this.accessKeyId = accessKeyId.clone();
            this.secretAccessKey = secretAccessKey.clone();
            Arrays.fill(accessKeyId, '\0');
            Arrays.fill(secretAccessKey, '\0');
        }

        /** 返回访问键副本。 */
        public char[] accessKeyId() {
            return accessKeyId.clone();
        }

        /** 返回访问密钥副本。 */
        public char[] secretAccessKey() {
            return secretAccessKey.clone();
        }

        /** 清零内部字符数组。 */
        @Override
        public void close() {
            Arrays.fill(accessKeyId, '\0');
            Arrays.fill(secretAccessKey, '\0');
        }
    }

    /**
     * 凭据不可用（未配置或不可解）；不向客户端输出细节。
     */
    public static class CredentialUnavailableException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param message 脱敏说明
         */
        public CredentialUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * 凭据 JSON 结构。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     */
    private record CredentialPayload(String accessKeyId, String secretAccessKey) {}
}
