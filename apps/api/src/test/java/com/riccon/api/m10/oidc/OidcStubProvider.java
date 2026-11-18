package com.riccon.api.m10.oidc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;

/**
 * 本地、确定性的 OIDC 测试 provider。
 *
 * <p>提供 discovery、JWKS、token 与 userinfo 计数；不访问网络、不依赖 V1 的测试 IdP（ADR-0019 第 6 节）：
 *
 * <ul>
 *   <li>同一个 server 可服务多个 realm（每个租户一个 issuer），共享 RSA 密钥；
 *   <li>token 端点校验 client Basic 认证、授权码一次性、PKCE(S256)；
 *   <li>ID Token 的 nonce 使用授权请求中收到的值（Spring 发送的是哈希）；
 *   <li>记录 userinfo 调用次数，供“无 userinfo”断言。
 * </ul>
 */
final class OidcStubProvider implements AutoCloseable {

    /** JSON 序列化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 签名算法。 */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** JWK 密钥 ID。 */
    private static final String KEY_ID = "riccon-test-key";

    /** 授权码记录。 */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    /** realm 到发现请求次数。 */
    private final Map<String, AtomicInteger> discoveryCalls = new ConcurrentHashMap<>();

    /** token 请求次数。 */
    private final AtomicInteger tokenCalls = new AtomicInteger();

    /** userinfo 请求次数。 */
    private final AtomicInteger userInfoCalls = new AtomicInteger();

    /** end-session 请求次数。 */
    private final AtomicInteger logoutCalls = new AtomicInteger();

    /** 最近签发的 access token；用于会话无 token 断言。 */
    private volatile String lastAccessToken;

    /** 最近签发的 ID Token；用于会话无 token 断言。 */
    private volatile String lastIdToken;

    /** 支持的 realm。 */
    private final Set<String> realms;

    /** RSA 密钥对。 */
    private final KeyPair keyPair;

    /** HTTP server。 */
    private final HttpServer server;

    /** 是否让发现端点返回 503。 */
    private volatile boolean discoveryFails;

    /** 发现文档中覆盖的 issuer（用于 issuer_mismatch 测试）。 */
    private volatile String discoveryIssuerOverride;

    /**
     * 启动 stub。
     *
     * @param realms 支持的 realm 列表
     * @throws IOException 端口绑定失败
     */
    OidcStubProvider(String... realms) throws IOException {
        this.realms = Set.of(realms);
        this.keyPair = generateKeyPair();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /**
     * 返回服务端口。
     *
     * @return 端口
     */
    int port() {
        return server.getAddress().getPort();
    }

    /**
     * 返回 realm 的 issuer。
     *
     * @param realm realm 名
     * @return issuer
     */
    String issuer(String realm) {
        return "http://127.0.0.1:" + port() + "/realms/" + realm;
    }

    /**
     * 登记一次性授权码及其 PKCE/nonce 绑定。
     *
     * @param realm realm
     * @param clientId 客户端 ID
     * @param code 授权码
     * @param codeChallenge PKCE 挑战
     * @param nonce 授权请求中的 nonce（Spring 已哈希）
     * @param subject 登录 subject
     */
    void registerCode(String realm, String clientId, String code, String codeChallenge, String nonce, String subject) {
        codes.put(code, new CodeEntry(realm, clientId, codeChallenge, nonce, subject));
    }

    /**
     * 设置发现端点是否失败。
     *
     * @param fails 是否返回 503
     */
    void setDiscoveryFails(boolean fails) {
        this.discoveryFails = fails;
    }

    /**
     * 覆盖发现文档 issuer。
     *
     * @param issuer 覆盖值；{@code null} 表示使用正常值
     */
    void setDiscoveryIssuerOverride(String issuer) {
        this.discoveryIssuerOverride = issuer;
    }

    /**
     * 返回发现请求次数。
     *
     * @param realm realm
     * @return 次数
     */
    int discoveryCalls(String realm) {
        AtomicInteger counter = discoveryCalls.get(realm);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 返回 token 请求次数。
     *
     * @return 次数
     */
    int tokenCalls() {
        return tokenCalls.get();
    }

    /**
     * 返回 userinfo 请求次数。
     *
     * @return 次数
     */
    int userInfoCalls() {
        return userInfoCalls.get();
    }

    /**
     * 返回 end-session 请求次数。
     *
     * @return 次数
     */
    int logoutCalls() {
        return logoutCalls.get();
    }

    /**
     * 返回最近签发的 access token。
     *
     * @return token；尚未签发时为空
     */
    String lastAccessToken() {
        return lastAccessToken;
    }

    /**
     * 返回最近签发的 ID Token。
     *
     * @return token；尚未签发时为空
     */
    String lastIdToken() {
        return lastIdToken;
    }

    /**
     * 清空一次性授权码与计数。
     */
    void reset() {
        codes.clear();
        tokenCalls.set(0);
        userInfoCalls.set(0);
        logoutCalls.set(0);
        lastAccessToken = null;
        lastIdToken = null;
        discoveryCalls.clear();
        discoveryFails = false;
        discoveryIssuerOverride = null;
    }

    /**
     * 关闭 server。
     */
    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 路由请求。
     *
     * @param exchange HTTP 交换
     * @throws IOException 读写失败
     */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String realm = realmOf(path);
            if (realm == null) {
                respond(exchange, 404, "{}");
                return;
            }
            if (path.endsWith("/.well-known/openid-configuration")) {
                handleDiscovery(exchange, realm);
            } else if (path.endsWith("/jwks")) {
                handleJwks(exchange);
            } else if (path.endsWith("/token")) {
                handleToken(exchange, realm);
            } else if (path.endsWith("/userinfo")) {
                userInfoCalls.incrementAndGet();
                respond(exchange, 404, "{}");
            } else if (path.endsWith("/logout")) {
                logoutCalls.incrementAndGet();
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 404, "{}");
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * 输出发现文档。
     *
     * @param exchange HTTP 交换
     * @param realm realm
     * @throws IOException 读写失败
     */
    private void handleDiscovery(HttpExchange exchange, String realm) throws IOException {
        discoveryCalls.computeIfAbsent(realm, ignored -> new AtomicInteger()).incrementAndGet();
        if (discoveryFails) {
            respond(exchange, 503, "{}");
            return;
        }
        String issuer = discoveryIssuerOverride == null ? issuer(realm) : discoveryIssuerOverride;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer(realm) + "/authorize");
        doc.put("token_endpoint", issuer(realm) + "/token");
        doc.put("jwks_uri", issuer(realm) + "/jwks");
        // 故意提供 userinfo_endpoint：生产实现必须忽略它并断言零调用（ADR-0019 F-011）。
        doc.put("userinfo_endpoint", issuer(realm) + "/userinfo");
        doc.put("end_session_endpoint", issuer(realm) + "/logout");
        respond(exchange, 200, MAPPER.writeValueAsString(doc));
    }

    /**
     * 输出 JWKS。
     *
     * @param exchange HTTP 交换
     * @throws IOException 读写失败
     */
    private void handleJwks(HttpExchange exchange) throws IOException {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "RSA");
        key.put("kid", KEY_ID);
        key.put("use", "sig");
        key.put("alg", "RS256");
        key.put("n", base64Url(unsigned(publicKey.getModulus())));
        key.put("e", base64Url(unsigned(publicKey.getPublicExponent())));
        respond(exchange, 200, MAPPER.writeValueAsString(Map.of("keys", List.of(key))));
    }

    /**
     * 处理授权码交换。
     *
     * @param exchange HTTP 交换
     * @param realm realm
     * @throws IOException 读写失败
     */
    private void handleToken(HttpExchange exchange, String realm) throws IOException {
        tokenCalls.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = parseForm(body);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String clientId = basicClientId(authorization);
        CodeEntry entry = codes.remove(form.get("code"));
        if (entry == null
                || clientId == null
                || !entry.realm().equals(realm)
                || !entry.clientId().equals(clientId)
                || !"authorization_code".equals(form.get("grant_type"))
                || !pkceMatches(entry.codeChallenge(), form.get("code_verifier"))) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        String accessToken = "stub-access-token-" + java.util.UUID.randomUUID();
        String idToken = idToken(entry);
        this.lastAccessToken = accessToken;
        this.lastIdToken = idToken;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 300);
        response.put("id_token", idToken);
        respond(exchange, 200, MAPPER.writeValueAsString(response));
    }

    /**
     * 构造并签名 ID Token。
     *
     * @param entry 授权码记录
     * @return 紧凑序列化 JWT
     * @throws IOException 签名失败
     */
    private String idToken(CodeEntry entry) throws IOException {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = Map.of("alg", "RS256", "kid", KEY_ID, "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer(entry.realm()));
        claims.put("aud", entry.clientId());
        claims.put("sub", entry.subject());
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", entry.nonce());
        // 故意带邮箱：生产实现不得读取或用邮箱绑定（ADR-0019 第 3 节）。
        claims.put("email", "shared@example.test");
        String signingInput = base64Url(MAPPER.writeValueAsString(header).getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(MAPPER.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("stub id token signing failed", exception);
        }
    }

    /**
     * 校验 PKCE S256。
     *
     * @param expectedChallenge 授权请求中的 challenge
     * @param verifier token 请求中的 verifier
     * @return 匹配时返回 {@code true}
     */
    private static boolean pkceMatches(String expectedChallenge, String verifier) {
        if (expectedChallenge == null || verifier == null) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String computed = base64Url(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return computed.equals(expectedChallenge);
        } catch (java.security.NoSuchAlgorithmException exception) {
            return false;
        }
    }

    /**
     * 解析 Basic 认证中的 client_id。
     *
     * @param authorization Authorization 头
     * @return client_id；非法时返回 {@code null}
     */
    private static String basicClientId(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return null;
        }
        String decoded = new String(
                Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
        int separator = decoded.indexOf(':');
        if (separator <= 0 || separator == decoded.length() - 1) {
            return null;
        }
        return decoded.substring(0, separator);
    }

    /**
     * 从路径解析 realm。
     *
     * @param path 请求路径
     * @return realm；不匹配时返回 {@code null}
     */
    private String realmOf(String path) {
        String[] segments = path.split("/");
        if (segments.length < 3 || !"realms".equals(segments[1])) {
            return null;
        }
        return realms.contains(segments[2]) ? segments[2] : null;
    }

    /**
     * 解析 form 编码请求体。
     *
     * @param body 请求体
     * @return 参数
     */
    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    /**
     * 生成 RSA 密钥对。
     *
     * @return 密钥对
     */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("stub RSA key generation failed", exception);
        }
    }

    /**
     * 去掉 BigInteger 符号位。
     *
     * @param value 大整数
     * @return 无符号大端字节
     */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /**
     * base64url（无填充）。
     *
     * @param value 字节
     * @return 编码值
     */
    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 写出 JSON 响应。
     *
     * @param exchange HTTP 交换
     * @param status 状态码
     * @param body 响应体
     * @throws IOException 写入失败
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * 授权码记录。
     *
     * @param realm realm
     * @param clientId 客户端 ID
     * @param codeChallenge PKCE 挑战
     * @param nonce 授权请求 nonce
     * @param subject 登录 subject
     */
    private record CodeEntry(String realm, String clientId, String codeChallenge, String nonce, String subject) {
        /** 保证字段存在。 */
        private CodeEntry {
            Objects.requireNonNull(realm, "realm must not be null");
            Objects.requireNonNull(clientId, "clientId must not be null");
            Objects.requireNonNull(codeChallenge, "codeChallenge must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
        }
    }
}
