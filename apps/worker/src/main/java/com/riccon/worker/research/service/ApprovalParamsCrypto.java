package com.riccon.worker.research.service;

import com.riccon.platform.common.crypto.AesGcmEnvelope;
import com.riccon.platform.common.crypto.EnvelopeException;
import com.riccon.platform.common.crypto.EnvelopeKey;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.research.persistence.ApprovalParamsKeyJdbc;
import com.riccon.worker.research.repository.ApprovalParamsKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批参数信封加密与恢复。
 *
 * <p>审批创建时把规范化完整参数用租户 DEK（{@code purpose = approval-params}）做 AES-256-GCM
 * 加密，AAD 固定为 {@code tenantId|approvalId}；审批通过后按密文记录的 key id 选钥解密，并重新
 * 校验 SHA-256、长度与敏感键。密文缺失、key 未知、认证失败或哈希不符都必须失败关闭，
 * 禁止退回 {@code params_preview} 明文执行（ADR-0017）。
 *
 * <p>本类不写日志，不把密钥、nonce、密文或明文放入异常消息。DEK 由主密钥包裹后存放在
 * {@code tenant_data_key}；M02 没有独立密钥管理 API，缺省时按租户懒生成。
 */
@Component
public class ApprovalParamsCrypto {

    /** 规范化 JSON 的 UTF-8 字节上限；密文列 8192 字节扣除 29 字节信封头后仍有余量。 */
    public static final int MAX_PARAMS_BYTES = 8000;

    /** 租户 DEK 用途。 */
    public static final String PURPOSE = ApprovalParamsKeyJdbc.PURPOSE;

    /** 数据密钥长度。 */
    private static final int DEK_BYTES = 32;

    /** 随机源；DEK 与 key id 都使用安全随机数。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 审批参数 DEK 仓储。 */
    private final ApprovalParamsKeyRepository keyRepository;

    /** 主密钥提供者。 */
    private final MasterKeyProvider masterKeyProvider;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审批参数加密服务。
     *
     * @param keyRepository DEK 仓储
     * @param masterKeyProvider 主密钥提供者
     * @param objectMapper JSON 解析
     */
    public ApprovalParamsCrypto(
            ApprovalParamsKeyRepository keyRepository, MasterKeyProvider masterKeyProvider, ObjectMapper objectMapper) {
        this.keyRepository = Objects.requireNonNull(keyRepository, "keyRepository must not be null");
        this.masterKeyProvider = Objects.requireNonNull(masterKeyProvider, "masterKeyProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 加密审批参数。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param canonicalJson 规范化参数 JSON
     * @return key id 与密文
     * @throws AgentException 参数为空、超限或加密不可用
     */
    public Sealed seal(TenantId tenantId, UUID approvalId, String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            throw new AgentException(AgentException.INVALID, false, "approval parameters must not be empty");
        }
        byte[] plaintext = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_PARAMS_BYTES) {
            throw new AgentException(AgentException.INVALID, false, "approval parameters exceed the replay budget");
        }
        TenantDataKeyRecord record = activeKey(tenantId);
        byte[] dek = unwrap(record);
        try {
            EnvelopeKey key = new EnvelopeKey(record.keyId(), dek);
            AesGcmEnvelope.Sealed sealed =
                    AesGcmEnvelope.seal(key, plaintext, AesGcmEnvelope.aad(tenantId.value(), approvalId.toString()));
            return new Sealed(sealed.keyId(), sealed.ciphertext());
        } catch (EnvelopeException | IllegalArgumentException ex) {
            throw new AgentException(AgentException.CRYPTO, false, "approval parameters cannot be encrypted", ex);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * 解密并校验审批参数。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param keyId 密文记录中的 key id
     * @param ciphertext 密文
     * @param expectedSha256 规范化参数的 SHA-256
     * @return 完整参数
     * @throws AgentException 密文缺失、key 不可用、认证失败、哈希不符或参数不合法
     */
    public Map<String, Object> open(
            TenantId tenantId, UUID approvalId, String keyId, byte[] ciphertext, String expectedSha256) {
        if (ciphertext == null || ciphertext.length < AesGcmEnvelope.MIN_PAYLOAD_BYTES) {
            throw unreplayable("approval parameters have no ciphertext");
        }
        if (keyId == null || keyId.isBlank()) {
            throw unreplayable("approval parameters have no key id");
        }
        if (expectedSha256 == null || !expectedSha256.matches("^[0-9a-f]{64}$")) {
            throw unreplayable("approval parameters have no digest");
        }
        TenantDataKeyRecord record = keyRepository
                .find(tenantId, keyId)
                .orElseThrow(() -> unreplayable("approval parameter key is not available"));
        requireUsableKey(record);
        byte[] dek = unwrap(record);
        try {
            byte[] plaintext = AesGcmEnvelope.open(
                    new EnvelopeKey(record.keyId(), dek),
                    ciphertext,
                    AesGcmEnvelope.aad(tenantId.value(), approvalId.toString()));
            String json = new String(plaintext, StandardCharsets.UTF_8);
            if (plaintext.length > MAX_PARAMS_BYTES) {
                throw unreplayable("approval parameters exceed the replay budget");
            }
            if (!Digests.sha256Hex(json).equals(expectedSha256)) {
                throw unreplayable("approval parameters do not match their digest");
            }
            Map<String, Object> arguments = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (arguments == null || ArgumentDigest.hasSensitiveKey(arguments)) {
                throw unreplayable("approval parameters contain unsupported fields");
            }
            return arguments;
        } catch (EnvelopeException | IllegalArgumentException | JacksonException ex) {
            // 认证失败、格式错误与哈希不符统一为不可重放；异常消息不携带密文或明文。
            throw new AgentException(AgentException.CRYPTO, false, "approval parameters cannot be replayed", ex);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * 读取当前 DEK；租户首次创建审批时懒生成。
     *
     * @param tenantId 租户
     * @return 可用的 DEK 记录
     */
    private TenantDataKeyRecord activeKey(TenantId tenantId) {
        Optional<TenantDataKeyRecord> existing = keyRepository.findActive(tenantId);
        if (existing.isPresent()) {
            requireUsableKey(existing.get());
            return existing.get();
        }
        byte[] dek = new byte[DEK_BYTES];
        RANDOM.nextBytes(dek);
        String keyId = "ap-" + UUID.randomUUID().toString().replace("-", "");
        byte[] aad = EnvelopeDecryptor.aad(tenantId.value(), PURPOSE, keyId);
        MasterKeyProvider.WrappedDek wrapped = masterKeyProvider.wrap(dek, aad);
        Arrays.fill(dek, (byte) 0);
        TenantDataKeyRecord record = new TenantDataKeyRecord(
                tenantId.value(),
                keyId,
                PURPOSE,
                wrapped.wrappedDek(),
                wrapped.nonce(),
                "AES-256-GCM",
                masterKeyProvider.masterKeyId());
        keyRepository.insert(tenantId, record);
        return record;
    }

    /**
     * 校验 DEK 记录用途、算法与租户。
     *
     * @param record DEK 记录
     */
    private static void requireUsableKey(TenantDataKeyRecord record) {
        if (!PURPOSE.equals(record.purpose())
                || !"AES-256-GCM".equals(record.algorithm())
                || record.wrappedDek() == null
                || record.wrapNonce() == null) {
            throw unreplayable("approval parameter key is not usable");
        }
    }

    /**
     * 解开被主密钥包裹的 DEK。
     *
     * @param record DEK 记录
     * @return 明文 DEK；调用方负责清零
     */
    private byte[] unwrap(TenantDataKeyRecord record) {
        try {
            byte[] aad = EnvelopeDecryptor.aad(record.tenantId(), record.purpose(), record.keyId());
            byte[] dek = masterKeyProvider.unwrap(record.wrapNonce(), record.wrappedDek(), aad);
            if (dek.length != DEK_BYTES) {
                Arrays.fill(dek, (byte) 0);
                throw unreplayable("approval parameter key has invalid length");
            }
            return dek;
        } catch (EnvelopeException | IllegalArgumentException ex) {
            throw new AgentException(AgentException.CRYPTO, false, "approval parameters cannot be replayed", ex);
        } catch (RuntimeException ex) {
            throw new AgentException(AgentException.CRYPTO, false, "approval parameters cannot be replayed", ex);
        }
    }

    /**
     * 构造不可重放错误；消息固定，不包含密钥、密文或明文。
     *
     * @param reason 内部说明
     * @return 不可重试异常
     */
    private static AgentException unreplayable(String reason) {
        return new AgentException(AgentException.CRYPTO, false, reason);
    }

    /**
     * 加密结果。
     *
     * @param keyId 加密使用的 key id
     * @param ciphertext 完整信封载荷
     */
    public record Sealed(String keyId, byte[] ciphertext) {

        /** 防御性复制密文。 */
        public Sealed {
            Objects.requireNonNull(keyId, "keyId must not be null");
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            ciphertext = ciphertext.clone();
        }

        /** 返回密文副本。 */
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
