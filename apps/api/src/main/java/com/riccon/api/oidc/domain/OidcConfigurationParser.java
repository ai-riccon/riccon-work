package com.riccon.api.oidc.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code RICCON_OIDC_IDPS} 配置解析与启动校验。
 *
 * <p>安全约束（ADR-0019 第 1 节、OPS-003）：原始 JSON 只作为 {@link String} 进入本类，绝不交给 Spring 绑定到
 * 类型化对象；解析/字段错误只报告配置项序号、字段路径、错误类型与行列，**不包含原始 JSON、字段值或
 * clientSecret**，异常也不携带可能带原文的 cause。任何非法配置都让应用启动失败（fail-fast）；完全未配置时返回空列表。
 *
 * <p>校验规则：JSON 数组；每项含 tenantCode/issuer/clientId/clientSecret；tenantCode 唯一且符合
 * {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}}；issuer 绝对 URL、非 HTTPS 仅在 allow-http 时允许、规范化后全局唯一；
 * scope 含 openid 且不含 offline_access；end-session 端点可选但必须是绝对 URL。
 */
public final class OidcConfigurationParser {

    /** 租户代码格式；与 OpenAPI 的 tenantCode 约束一致。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 默认 scope。 */
    private static final String OPENID_SCOPE = "openid";

    /** 明确禁止的 scope：不保存 refresh token（ADR-0019 第 2 节）。 */
    private static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    /** 缺省 provider 展示名。 */
    private static final String DEFAULT_PROVIDER_NAME = "企业账号";

    /** provider 展示名最大长度。 */
    private static final int MAX_PROVIDER_NAME_LENGTH = 100;

    /** 配置项字段名，仅用于错误信息。 */
    private static final String IDPS_FIELD = "riccon.oidc.idps";

    /** 工具类禁止实例化。 */
    private OidcConfigurationParser() {}

    /**
     * 解析并校验 IdP 配置。
     *
     * @param rawJson 原始 JSON；空白视为未配置
     * @param allowHttp 是否允许 http（仅本地/测试）
     * @param objectMapper JSON 解析器
     * @return 按配置顺序排列的 IdP 配置；未配置时为空列表
     * @throws IllegalStateException 配置非法；消息已脱敏
     */
    public static List<OidcIdpConfig> parse(String rawJson, boolean allowHttp, ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (JacksonException exception) {
            // 只保留异常类型与行列；不保留 message/cause，避免把原始片段写入启动日志。
            throw new IllegalStateException(invalidJsonMessage(exception));
        }
        if (root == null || !root.isArray()) {
            throw new IllegalStateException(IDPS_FIELD + " must be a JSON array");
        }
        List<OidcIdpConfig> idps = new ArrayList<>();
        Set<String> tenantCodes = new HashSet<>();
        Set<String> issuers = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            String path = IDPS_FIELD + "[" + index + "]";
            if (item == null || !item.isObject()) {
                throw new IllegalStateException(path + " must be a JSON object");
            }
            String tenantCode = requiredText(item, "tenantCode", path);
            if (!TENANT_CODE.matcher(tenantCode).matches()) {
                throw new IllegalStateException(path + ".tenantCode has an invalid format");
            }
            if (!tenantCodes.add(tenantCode)) {
                throw new IllegalStateException(path + ".tenantCode is duplicated");
            }
            String issuer;
            try {
                issuer = normalizeIssuer(requiredText(item, "issuer", path), allowHttp);
            } catch (IllegalArgumentException exception) {
                // 只拼接字段路径与规则原因，保留脱敏边界。
                throw new IllegalStateException(path + ".issuer: " + exception.getMessage());
            }
            if (!issuers.add(issuer)) {
                throw new IllegalStateException(path + ".issuer is already used by another tenant");
            }
            String clientId = requiredText(item, "clientId", path);
            String clientSecret = requiredText(item, "clientSecret", path);
            String providerName = optionalText(item, "providerName", path, DEFAULT_PROVIDER_NAME);
            if (providerName.length() > MAX_PROVIDER_NAME_LENGTH) {
                throw new IllegalStateException(path + ".providerName is too long");
            }
            List<String> scopes = scopes(item, path);
            String endSessionEndpoint = optionalText(item, "endSessionEndpoint", path, null);
            if (endSessionEndpoint != null) {
                endSessionEndpoint =
                        requireAbsoluteEndpoint(endSessionEndpoint, path + ".endSessionEndpoint", allowHttp);
            }
            boolean enabled = optionalBoolean(item, "enabled", path, true);
            idps.add(new OidcIdpConfig(
                    tenantCode, issuer, clientId, clientSecret, providerName, scopes, endSessionEndpoint, enabled));
        }
        return List.copyOf(idps);
    }

    /**
     * 规范化 issuer：小写 scheme/host、去默认端口、去尾部斜杠。
     *
     * <p>返回的字符串用于配置存储、发现文档比对与 ID Token issuer 校验；同一 issuer 的两种写法必须得到同一结果，
     * 否则无法保证“全局唯一”。错误消息不包含原始值。
     *
     * @param rawIssuer 原始 issuer
     * @param allowHttp 是否允许 http
     * @return 规范化 issuer
     * @throws IllegalArgumentException issuer 非法；消息已脱敏
     */
    public static String normalizeIssuer(String rawIssuer, boolean allowHttp) {
        URI uri;
        try {
            uri = new URI(rawIssuer.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("issuer must be an absolute URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException("issuer must be an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("issuer must use http or https");
        }
        if (!allowHttp && !"https".equals(scheme)) {
            throw new IllegalArgumentException("issuer must use https");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("issuer must not contain user info, query or fragment");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String authority = port == -1 ? host : host + ":" + port;
        return scheme + "://" + authority + path;
    }

    /**
     * 校验绝对端点（end-session 等）。
     *
     * @param value 端点
     * @param path 字段路径（用于错误信息）
     * @param allowHttp 是否允许 http
     * @return 原值（去首尾空白）
     */
    public static String requireAbsoluteEndpoint(String value, String path, boolean allowHttp) {
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(path + " must be an absolute URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalStateException(path + " must be an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalStateException(path + " must use http or https");
        }
        if (!allowHttp && !"https".equals(scheme)) {
            throw new IllegalStateException(path + " must use https");
        }
        return value.trim();
    }

    /**
     * 读取必填文本字段；只报告字段路径，不报告值。
     *
     * @param node 配置项
     * @param field 字段名
     * @param path 配置项路径
     * @return 去首尾空白后的值
     */
    private static String requiredText(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(path + "." + field + " is required");
        }
        return value.asText().trim();
    }

    /**
     * 读取可选文本字段。
     *
     * @param node 配置项
     * @param field 字段名
     * @param path 配置项路径
     * @param defaultValue 缺省值
     * @return 文本值或缺省值
     */
    private static String optionalText(JsonNode node, String field, String path, String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(path + "." + field + " must be a non-blank string");
        }
        return value.asText().trim();
    }

    /**
     * 读取可选布尔字段。
     *
     * @param node 配置项
     * @param field 字段名
     * @param path 配置项路径
     * @param defaultValue 缺省值
     * @return 布尔值或缺省值
     */
    private static boolean optionalBoolean(JsonNode node, String field, String path, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalStateException(path + "." + field + " must be a boolean");
        }
        return value.asBoolean();
    }

    /**
     * 解析 scope 列表并执行最小权限校验。
     *
     * @param node 配置项
     * @param path 配置项路径
     * @return scope 列表
     */
    private static List<String> scopes(JsonNode node, String path) {
        JsonNode value = node.get("scopes");
        if (value == null || value.isNull()) {
            return List.of(OPENID_SCOPE);
        }
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalStateException(path + ".scopes must be a non-empty string array");
        }
        List<String> scopes = new ArrayList<>();
        for (JsonNode scopeNode : value) {
            if (!scopeNode.isTextual() || scopeNode.asText().isBlank()) {
                throw new IllegalStateException(path + ".scopes must be a non-empty string array");
            }
            scopes.add(scopeNode.asText().trim());
        }
        if (!scopes.contains(OPENID_SCOPE)) {
            throw new IllegalStateException(path + ".scopes must contain openid");
        }
        if (scopes.contains(OFFLINE_ACCESS_SCOPE)) {
            throw new IllegalStateException(path + ".scopes must not contain offline_access");
        }
        return List.copyOf(scopes);
    }

    /**
     * 构造脱敏的 JSON 解析失败消息。
     *
     * @param exception Jackson 异常
     * @return 只含类型与位置的消息
     */
    private static String invalidJsonMessage(JacksonException exception) {
        StringBuilder message = new StringBuilder(IDPS_FIELD)
                .append(" is not valid JSON (type=")
                .append(exception.getClass().getSimpleName());
        TokenStreamLocation location = exception.getLocation();
        if (location != null && location.getLineNr() > 0) {
            message.append(", line=")
                    .append(location.getLineNr())
                    .append(", column=")
                    .append(location.getColumnNr());
        }
        return message.append(')').toString();
    }
}
