package com.riccon.worker.model.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.crypto.domain.CredentialRecord;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import com.riccon.worker.crypto.service.EnvelopeDecryptor;
import com.riccon.worker.model.domain.ModelCatalogEntry;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.repository.ModelRepository;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型解析与客户端创建。
 *
 * <p>按顺序读取全局目录、凭据、DEK 并解密；任一步缺失都返回可诊断但不含密钥的错误。
 * 端点必须为 HTTPS（本地测试可显式允许 HTTP），且不能包含用户信息、查询或片段，
 * 防止目录被篡改后把凭据发送到任意地址（SSRF 与凭据保护）。
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
     * 解析当前租户可用的模型。
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
                .findActiveCredential(tenantId, catalog.provider().id())
                .orElseThrow(() -> new ModelException(
                        ModelException.CONFIGURATION, false, "no active model credential is configured"));
        TenantDataKeyRecord dataKey = modelRepository
                .findDataKey(credential.tenantId(), credential.keyId())
                .orElseThrow(() -> new ModelException(
                        ModelException.CONFIGURATION, false, "model credential data key is missing"));
        if (!CREDENTIAL_PURPOSE.equals(dataKey.purpose())) {
            throw new ModelException(
                    ModelException.CONFIGURATION, false, "model credential data key has unexpected purpose");
        }
        SecretValue apiKey;
        try {
            apiKey = envelopeDecryptor.decrypt(credential, dataKey);
        } catch (RuntimeException ex) {
            // 底层原因只用于服务端日志排障；错误事件只暴露稳定错误码，不包含密文或密钥。
            throw new ModelException(
                    ModelException.CONFIGURATION, false, "model credential could not be decrypted", ex);
        }
        int maxOutputTokens = catalog.model().maxOutputTokens() > 0
                ? catalog.model().maxOutputTokens()
                : properties.defaultMaxOutputTokens();
        return new ResolvedModel(catalog.model(), catalog.provider(), baseUrl, apiKey, maxOutputTokens);
    }

    /**
     * 创建一次调用的模型客户端。
     *
     * @param resolved 已解析模型与凭据
     * @return OpenAI 兼容流式模型
     */
    public OpenAiCompatibleChatModel createClient(ResolvedModel resolved) {
        return new OpenAiCompatibleChatModel(resolved, properties, objectMapper);
    }

    /**
     * 校验目录中的基础 URL。
     *
     * @param baseUrl 目录 URL
     * @return 校验后的 URI
     * @throws ModelException 协议或结构不合法
     */
    private URI validateBaseUrl(URI baseUrl) {
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
