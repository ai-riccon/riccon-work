package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.domain.ModelCredential;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ModelTarget;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.repository.ModelRepository;
import com.riccon.worker.persistence.PlatformTenant;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型解析与客户端创建。
 *
 * <p>路由层先解析出 {@link ModelTarget}（不含明文），每次尝试再按需读取平台 key 池并解密；
 * 任一步缺失都返回可诊断但不含密钥的错误。端点必须为 HTTPS（本地测试可显式允许 HTTP），
 * 且不能包含用户信息、查询或片段，防止目录被篡改后把凭据发送到任意地址（SSRF 与凭据保护）。
 */
@Component
public class ModelRegistry {

    /** 模型凭据用途。 */
    static final String CREDENTIAL_PURPOSE = "model-credential";

    /** 模型仓储。 */
    private final ModelRepository modelRepository;

    /** 信封解密器。 */
    private final EnvelopeDecryptor envelopeDecryptor;

    /** 网关配置。 */
    private final ModelGatewayProperties properties;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建模型解析器。
     *
     * @param modelRepository 模型仓储
     * @param envelopeDecryptor 信封解密器
     * @param properties 网关配置
     * @param objectMapper JSON 序列化器
     */
    public ModelRegistry(
            ModelRepository modelRepository,
            EnvelopeDecryptor envelopeDecryptor,
            ModelGatewayProperties properties,
            ObjectMapper objectMapper) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository must not be null");
        this.envelopeDecryptor = Objects.requireNonNull(envelopeDecryptor, "envelopeDecryptor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 解析当前租户可用的第一个模型（M02 兼容入口）。
     *
     * <p>凭据只来自平台托管池；请求租户的遗留 BYO 凭据被忽略（ADR-0021 §6.1）。
     *
     * @param tenantId 当前租户
     * @return 已解密并校验的模型端点
     * @throws ModelException 目录、凭据或密钥不可用
     */
    public ResolvedModel resolve(TenantId tenantId) {
        ModelCatalogEntry catalog = modelRepository
                .findActiveCatalog()
                .orElseThrow(
                        () -> new ModelException(ModelException.CONFIGURATION, false, "no active model is configured"));
        URI baseUrl = validateBaseUrl(catalog.provider().baseUrl());
        CredentialRecord credential = modelRepository
                .findActivePlatformCredential(catalog.provider().id())
                .orElseThrow(() -> new ModelException(
                        ModelException.CONFIGURATION, false, "no active model credential is configured"));
        TenantDataKeyRecord dataKey = modelRepository
                .findDataKey(credential.tenantId(), credential.keyId())
                .orElseThrow(() -> new ModelException(
                        ModelException.CONFIGURATION, false, "model credential data key is missing"));
        requireCredentialPurpose(dataKey);
        SecretValue apiKey = decrypt(credential, dataKey);
        return new ResolvedModel(
                catalog.model(),
                catalog.provider(),
                baseUrl,
                apiKey,
                maxOutputTokens(catalog.model().maxOutputTokens()));
    }

    /**
     * 用路由选择的具体 key 解析一次尝试的端点与明文。
     *
     * <p>每次尝试都重新解密，不在 Agent 会话或路由链里缓存明文（ADR-0021 §6.5）。
     *
     * @param target 路由选择的模型端点
     * @param credential 平台池中的 key
     * @return 已解密并校验的端点
     * @throws ModelException 凭据或密钥不可用
     */
    public ResolvedModel resolve(ModelTarget target, ModelCredential credential) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        if (!target.provider().id().equals(credential.providerId())) {
            throw new ModelException(ModelException.CONFIGURATION, false, "model credential provider mismatch");
        }
        URI baseUrl = validateBaseUrl(target.baseUrl());
        CredentialRecord record = new CredentialRecord(
                credential.id(),
                PlatformTenant.TENANT_ID,
                credential.providerId(),
                ModelRepository.CREDENTIAL_TYPE,
                credential.ciphertext(),
                credential.keyId(),
                credential.masterKeyId(),
                credential.version());
        TenantDataKeyRecord dataKey = modelRepository
                .findDataKey(record.tenantId(), record.keyId())
                .orElseThrow(() -> new ModelException(
                        ModelException.CONFIGURATION,
                        false,
                        "model credential data key is missing",
                        ModelFailureKind.CONFIGURATION));
        requireCredentialPurpose(dataKey);
        SecretValue apiKey = decrypt(record, dataKey);
        return new ResolvedModel(target.model(), target.provider(), baseUrl, apiKey, target.maxOutputTokens());
    }

    /**
     * 把目录条目转换为不含明文的模型端点快照。
     *
     * @param catalog 目录条目
     * @return 模型端点快照
     */
    public ModelTarget target(ModelCatalogEntry catalog) {
        URI baseUrl = validateBaseUrl(catalog.provider().baseUrl());
        return new ModelTarget(
                catalog.model(),
                catalog.provider(),
                baseUrl,
                maxOutputTokens(catalog.model().maxOutputTokens()));
    }

    /**
     * 创建一次调用的模型客户端。
     *
     * <p>内层客户端固定只发一次 HTTP；所有重试/换 key/换模型由 {@code FailoverChatModel} 拥有
     * （ADR-0021 §5.2）。
     *
     * @param resolved 已解析模型与凭据
     * @return OpenAI 兼容流式模型
     */
    public OpenAiCompatibleChatModel createClient(ResolvedModel resolved) {
        return new OpenAiCompatibleChatModel(resolved, properties, objectMapper);
    }

    /**
     * 校验凭据 DEK 的用途，防止把其他用途的密钥用于模型凭据。
     *
     * @param dataKey 数据密钥
     */
    private static void requireCredentialPurpose(TenantDataKeyRecord dataKey) {
        if (!CREDENTIAL_PURPOSE.equals(dataKey.purpose())) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model credential data key has unexpected purpose",
                    ModelFailureKind.CONFIGURATION);
        }
    }

    /**
     * 解密凭据；底层原因只用于服务端排障，错误事件只暴露稳定错误码。
     *
     * @param credential 凭据
     * @param dataKey 数据密钥
     * @return 明文包装
     */
    private SecretValue decrypt(CredentialRecord credential, TenantDataKeyRecord dataKey) {
        try {
            return envelopeDecryptor.decrypt(credential, dataKey);
        } catch (RuntimeException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model credential could not be decrypted",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
    }

    /**
     * 计算本次调用的输出上限。
     *
     * @param catalogMaxOutput 目录配置；0 表示未配置
     * @return 生效上限
     */
    private int maxOutputTokens(int catalogMaxOutput) {
        return catalogMaxOutput > 0 ? catalogMaxOutput : properties.defaultMaxOutputTokens();
    }

    /**
     * 校验目录中的基础 URL。
     *
     * @param baseUrl 目录 URL
     * @return 校验后的 URI
     * @throws ModelException 协议或结构不合法
     */
    public URI validateBaseUrl(URI baseUrl) {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getHost().isBlank()) {
            throw new ModelException(ModelException.CONFIGURATION, false, "model base url has no host");
        }
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean localHttp = "http".equals(scheme) && properties.allowInsecureHttp();
        if (!https && !localHttp) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model base url must use https unless insecure http is allowed");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new ModelException(ModelException.CONFIGURATION, false, "model base url contains forbidden parts");
        }
        return baseUrl;
    }
}
