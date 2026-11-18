package com.riccon.api.oidc.infrastructure;

import com.riccon.api.oidc.domain.OidcConfigurationParser;
import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcProviderMetadata;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OIDC 发现文档客户端（进程内缓存）。
 *
 * <p>按 issuer 懒加载 {@code {issuer}/.well-known/openid-configuration}：成功缓存 10 分钟（可配置），
 * 失败退避 30 秒；失败只返回 {@code idp_unavailable}/{@code issuer_mismatch}，不阻塞应用启动，也不影响内置账号。
 * 发现文档的 issuer 必须与配置规范化后完全一致（ADR-0019 第 2 节）。
 *
 * <p>日志只记录 issuer 与错误类型，不记录响应体、token、code 或密钥。
 */
public class OidcDiscoveryClient {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OidcDiscoveryClient.class);

    /** 发现失败后的退避时长（ADR-0019 第 2 节）。 */
    static final Duration NEGATIVE_CACHE_TTL = Duration.ofSeconds(30);

    /** 发现路径。 */
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /** HTTP 客户端。 */
    private final HttpClient httpClient;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /** 可注入时钟，保证缓存过期可测试。 */
    private final Clock clock;

    /** 成功缓存时长。 */
    private final Duration cacheTtl;

    /** 单次请求读取超时。 */
    private final Duration readTimeout;

    /** 是否允许 http 端点。 */
    private final boolean allowHttp;

    /** issuer 到缓存项的映射。 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 创建发现客户端。
     *
     * @param objectMapper JSON 解析器
     * @param clock 可注入时钟
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     * @param cacheTtl 成功缓存时长
     * @param allowHttp 是否允许 http 端点
     */
    public OidcDiscoveryClient(
            ObjectMapper objectMapper,
            Clock clock,
            Duration connectTimeout,
            Duration readTimeout,
            Duration cacheTtl,
            boolean allowHttp) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.cacheTtl = requirePositive(cacheTtl, "discoveryCacheTtl");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
        Duration connect = requirePositive(connectTimeout, "connectTimeout");
        this.allowHttp = allowHttp;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
    }

    /**
     * 解析发现文档；命中缓存时不发起网络请求。
     *
     * @param idp IdP 配置
     * @return 发现元数据
     * @throws OidcDiscoveryException 发现失败或 issuer 不一致
     */
    public OidcProviderMetadata resolve(OidcIdpConfig idp) {
        Instant now = clock.instant();
        CacheEntry cached = cache.get(idp.issuer());
        if (cached != null && cached.validUntil().isAfter(now)) {
            if (cached.metadata() != null) {
                return cached.metadata();
            }
            throw new OidcDiscoveryException(reason(cached.failureReasonCode()));
        }
        try {
            OidcProviderMetadata metadata = fetch(idp);
            cache.put(idp.issuer(), new CacheEntry(metadata, null, now.plus(cacheTtl)));
            return metadata;
        } catch (OidcDiscoveryException exception) {
            cache.put(idp.issuer(), new CacheEntry(null, exception.reason().code(), now.plus(NEGATIVE_CACHE_TTL)));
            LOG.warn(
                    "oidc.discovery.failed issuer={} reason={}",
                    idp.issuer(),
                    exception.reason().code());
            throw exception;
        }
    }

    /**
     * 判断 issuer 是否处于失败退避窗口。
     *
     * @param issuer 规范化 issuer
     * @return 处于失败窗口时返回 {@code true}
     */
    public boolean isUnavailable(String issuer) {
        CacheEntry cached = cache.get(issuer);
        return cached != null
                && cached.metadata() == null
                && cached.validUntil().isAfter(clock.instant());
    }

    /**
     * 返回失败窗口内的原因码。
     *
     * @param issuer 规范化 issuer
     * @return 原因码；不在失败窗口时为空
     */
    public Optional<String> failureReason(String issuer) {
        CacheEntry cached = cache.get(issuer);
        if (cached == null || cached.metadata() != null || !cached.validUntil().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(cached.failureReasonCode());
    }

    /**
     * 拉取并校验发现文档。
     *
     * @param idp IdP 配置
     * @return 发现元数据
     */
    private OidcProviderMetadata fetch(OidcIdpConfig idp) {
        URI uri;
        try {
            uri = URI.create(idp.issuer() + DISCOVERY_PATH);
        } catch (IllegalArgumentException exception) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException exception) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        if (response.statusCode() != 200) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        return parse(response.body(), idp);
    }

    /**
     * 解析发现文档并校验 issuer 与端点。
     *
     * @param body 响应体
     * @param idp 配置
     * @return 发现元数据
     */
    private OidcProviderMetadata parse(String body, OidcIdpConfig idp) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        if (root == null || !root.isObject()) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        String discoveredIssuer = text(root, "issuer");
        String normalizedIssuer;
        try {
            normalizedIssuer = OidcConfigurationParser.normalizeIssuer(discoveredIssuer, allowHttp);
        } catch (IllegalArgumentException exception) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        if (!idp.issuer().equals(normalizedIssuer)) {
            // 发现文档与配置不一致时按 issuer_mismatch 失败，禁止静默采用 IdP 的 issuer。
            throw new OidcDiscoveryException(OidcLoginFailureReason.ISSUER_MISMATCH);
        }
        String authorizationEndpoint = endpoint(root, "authorization_endpoint");
        String tokenEndpoint = endpoint(root, "token_endpoint");
        String jwkSetUri = endpoint(root, "jwks_uri");
        return new OidcProviderMetadata(normalizedIssuer, authorizationEndpoint, tokenEndpoint, jwkSetUri);
    }

    /**
     * 读取必填文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本值
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
        return value.asText();
    }

    /**
     * 读取并校验绝对端点。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 端点 URL
     */
    private String endpoint(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return OidcConfigurationParser.requireAbsoluteEndpoint(value, field, allowHttp);
        } catch (IllegalStateException exception) {
            throw new OidcDiscoveryException(OidcLoginFailureReason.IDP_UNAVAILABLE);
        }
    }

    /**
     * 把缓存中的原因码还原为枚举。
     *
     * @param code 原因码
     * @return 失败原因
     */
    private static OidcLoginFailureReason reason(String code) {
        return OidcLoginFailureReason.fromCode(code).orElse(OidcLoginFailureReason.IDP_UNAVAILABLE);
    }

    /**
     * 校验时长必须为正数。
     *
     * @param value 时长
     * @param name 配置名
     * @return 原值
     */
    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * 发现缓存项：成功时 metadata 非空，失败时 failureReasonCode 非空。
     *
     * @param metadata 发现元数据
     * @param failureReasonCode 失败原因码
     * @param validUntil 缓存有效截止时间
     */
    private record CacheEntry(OidcProviderMetadata metadata, String failureReasonCode, Instant validUntil) {}
}
