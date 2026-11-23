package com.riccon.api.knowledge.connector.domain;

import com.riccon.platform.common.model.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 连接器端点 URL 与 SSRF 策略（ADR-0023 第 6.5 节、TM-61）。
 *
 * <p>连接器端点只来自部署允许列表，本类负责启动时与运行期共用的结构校验、DNS 解析校验与重定向目标校验：
 *
 * <ul>
 *   <li>只允许 HTTPS；{@code allowHttp=true} 的内部端点显式例外，生产端点禁止 HTTP；
 *   <li>禁止 userinfo、query、fragment 与空白/控制字符；
 *   <li>永久拒绝云元数据、链路本地（{@code 169.254.0.0/16}、{@code ::1}、{@code fe80::/10}）；
 *   <li>允许列表条目本身可以解析到 RFC1918 或 IPv4 回环（内部 S3 的常态），但未列入的主机名或
 *       重定向目标解析到私有地址一律拒绝；
 *   <li>重定向目标必须仍是允许列表中的主机；S3 客户端同时禁用自动重定向，形成双重防护。
 * </ul>
 *
 * <p>本类不执行网络 I/O；DNS 解析通过 {@link HostResolver} 接缝注入，测试使用固定映射。
 */
public final class ConnectorEndpointPolicy {

    /** 端点 ID 规则；长度与 OpenAPI {@code endpointId} 上限 64 一致。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    /** 云元数据主机名（归一化后比较）。 */
    private static final Set<String> METADATA_HOSTNAMES = Set.of("metadata.google.internal", "metadata");

    /** 阿里云元数据地址 100.100.100.200。 */
    private static final byte[] ALIYUN_METADATA = new byte[] {100, 100, 100, (byte) 200};

    /** Oracle Cloud 元数据地址 192.0.0.192。 */
    private static final byte[] ORACLE_METADATA = new byte[] {(byte) 192, 0, 0, (byte) 192};

    /** AWS ECS/EKS IPv6 元数据地址 fd00:ec2::254。 */
    private static final byte[] AWS_IPV6_METADATA =
            new byte[] {(byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};

    /** 主机解析接缝。 */
    private final HostResolver hostResolver;

    /**
     * 创建策略。
     *
     * @param hostResolver 主机解析器
     */
    public ConnectorEndpointPolicy(HostResolver hostResolver) {
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver must not be null");
    }

    /**
     * 校验部署配置并生成不可变允许列表。
     *
     * @param configs 原始端点条目；为空表示零出网/未配置
     * @return 允许列表
     * @throws IllegalArgumentException 配置非法
     */
    public ConnectorEndpointRegistry load(List<ConnectorEndpointConfig> configs) {
        List<ConnectorEndpointConfig> entries = configs == null ? List.of() : configs;
        List<ConnectorEndpointConfig> validated = new ArrayList<>(entries.size());
        Set<String> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (ConnectorEndpointConfig config : entries) {
            if (config == null) {
                throw new IllegalArgumentException("connector endpoint entry must not be null");
            }
            String id = config.id() == null ? "" : config.id().strip();
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("connector endpoint id must match " + ID_PATTERN.pattern());
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate connector endpoint id: " + id);
            }
            String label = config.label() == null ? "" : config.label().strip();
            if (label.isEmpty() || label.length() > 64) {
                throw new IllegalArgumentException("connector endpoint label must be 1-64 characters");
            }
            if (config.production() && config.allowHttp()) {
                throw new IllegalArgumentException("production connector endpoint must not allow http: " + id);
            }
            URI uri = validateUrl(config.url(), id, config.allowHttp());
            verifyResolvedHost(uri, id);
            String normalized = normalizeUrl(uri);
            if (!urls.add(normalized)) {
                throw new IllegalArgumentException("duplicate connector endpoint url: " + id);
            }
            validated.add(new ConnectorEndpointConfig(id, normalized, label, config.allowHttp(), config.production()));
        }
        return new ConnectorEndpointRegistry(validated);
    }

    /**
     * 校验 URL 结构、协议、userinfo/query/fragment 与字面量地址。
     *
     * @param rawUrl 原始 URL
     * @param endpointId 端点 ID，仅用于错误说明
     * @return 解析后的 URI
     * @throws IllegalArgumentException URL 非法
     */
    public URI validateUrl(String rawUrl, String endpointId) {
        return validateUrl(rawUrl, endpointId, false);
    }

    /**
     * 校验 URL 结构，允许调用方显式声明 HTTP 例外。
     *
     * @param rawUrl 原始 URL
     * @param endpointId 端点 ID
     * @param allowHttp 是否允许 HTTP
     * @return 解析后的 URI
     */
    public URI validateUrl(String rawUrl, String endpointId, boolean allowHttp) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("connector endpoint url must not be empty: " + endpointId);
        }
        for (int index = 0; index < rawUrl.length(); index++) {
            char current = rawUrl.charAt(index);
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                throw new IllegalArgumentException(
                        "connector endpoint url must not contain whitespace or control characters: " + endpointId);
            }
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("connector endpoint url is not a valid URI: " + endpointId, exception);
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw new IllegalArgumentException("connector endpoint url must be absolute: " + endpointId);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("connector endpoint scheme must be https: " + endpointId);
        }
        if ("http".equals(scheme) && !allowHttp) {
            throw new IllegalArgumentException(
                    "connector endpoint must use https unless allowHttp is set: " + endpointId);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("connector endpoint url must not contain userinfo: " + endpointId);
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("connector endpoint url must not contain a query: " + endpointId);
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("connector endpoint url must not contain a fragment: " + endpointId);
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new IllegalArgumentException("connector endpoint url has an invalid port: " + endpointId);
        }
        String host = extractHost(uri);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("connector endpoint url must contain a host: " + endpointId);
        }
        String normalizedHost = normalizeHost(host);
        byte[] literal = parseIpLiteral(normalizedHost);
        if (literal != null) {
            rejectMetadataOrLinkLocal(literal, endpointId);
        } else if (METADATA_HOSTNAMES.contains(normalizedHost)) {
            throw new IllegalArgumentException("connector endpoint host is a cloud metadata service: " + endpointId);
        }
        return uri;
    }

    /**
     * 校验主机解析结果；允许列表条目可以解析到 RFC1918/回环，但元数据与链路本地永远拒绝。
     *
     * @param uri 已通过结构校验的 URI
     * @param endpointId 端点 ID
     * @throws IllegalArgumentException 解析失败或命中禁止地址
     */
    public void verifyResolvedHost(URI uri, String endpointId) {
        String host = extractHost(uri);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("connector endpoint url must contain a host: " + endpointId);
        }
        String normalizedHost = normalizeHost(host);
        if (parseIpLiteral(normalizedHost) != null) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(normalizedHost);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(
                    "connector endpoint host could not be resolved: " + endpointId, exception);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("connector endpoint host resolved to no address: " + endpointId);
        }
        for (InetAddress address : addresses) {
            if (address != null) {
                rejectMetadataOrLinkLocal(address.getAddress(), endpointId);
            }
        }
    }

    /**
     * 校验重定向目标：必须是允许列表中的主机，且解析结果不得命中元数据/链路本地。
     *
     * <p>S3 客户端已禁用自动重定向；本方法用于运行期防御与测试，确保未来更换客户端时仍 fail-closed。
     *
     * @param target 重定向目标
     * @param registry 当前允许列表
     * @throws IllegalArgumentException 目标不在允许列表或命中禁止地址
     */
    public void verifyRedirectTarget(URI target, ConnectorEndpointRegistry registry) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("connector redirect target scheme is not allowed");
        }
        String host = extractHost(target);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("connector redirect target has no host");
        }
        String normalizedHost = normalizeHost(host);
        if (!registry.containsHost(normalizedHost)) {
            // 未列入的主机名即使解析到公网也拒绝，避免 SDK 自行切换主机。
            throw new IllegalArgumentException("connector redirect target is not in the endpoint allowlist");
        }
        byte[] literal = parseIpLiteral(normalizedHost);
        if (literal != null) {
            rejectMetadataOrLinkLocal(literal, "redirect");
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(normalizedHost);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("connector redirect target could not be resolved", exception);
        }
        for (InetAddress address : addresses) {
            if (address != null) {
                rejectMetadataOrLinkLocal(address.getAddress(), "redirect");
            }
        }
    }

    /**
     * 规范化 URL 供去重与注册表比较：scheme/host 小写、去默认端口与末尾点、空路径补 {@code /}。
     *
     * @param uri 已校验 URI
     * @return 规范化字符串
     */
    public static String normalizeUrl(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = extractHost(uri);
        String normalizedHost = host == null ? "" : normalizeHost(host);
        int port = uri.getPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(scheme).append("://");
        if (normalizedHost.contains(":")) {
            builder.append('[').append(normalizedHost).append(']');
        } else {
            builder.append(normalizedHost);
        }
        if (!defaultPort && port >= 0) {
            builder.append(':').append(port);
        }
        builder.append(path);
        return builder.toString();
    }

    /**
     * 提取并归一化 URI 主机；非法 host 语法回退解析 authority。
     *
     * @param uri URI
     * @return 主机；无法提取时为空
     */
    public static String extractHost(URI uri) {
        String host = uri.getHost();
        if (host != null) {
            return normalizeHost(host);
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String candidate = authority;
        int userInfo = candidate.lastIndexOf('@');
        if (userInfo >= 0) {
            candidate = candidate.substring(userInfo + 1);
        }
        if (candidate.startsWith("[")) {
            int closing = candidate.indexOf(']');
            return closing > 0 ? candidate.substring(1, closing) : null;
        }
        int colon = candidate.lastIndexOf(':');
        if (colon >= 0 && candidate.indexOf(':') == colon) {
            String port = candidate.substring(colon + 1);
            if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                candidate = candidate.substring(0, colon);
            }
        }
        return candidate;
    }

    /**
     * 归一化主机名：小写、去方括号与末尾点。
     *
     * @param host 原始主机
     * @return 归一化主机
     */
    public static String normalizeHost(String host) {
        String value = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            value = value.substring(1);
        }
        if (value.endsWith("]")) {
            value = value.substring(0, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 拒绝云元数据与链路本地地址（含 IPv6 回环与链路本地）。
     *
     * @param address 4 或 16 字节地址
     * @param endpointId 端点 ID
     */
    private static void rejectMetadataOrLinkLocal(byte[] address, String endpointId) {
        if (isMetadataAddress(address)
                || isLinkLocal(address)
                || isIpv6Loopback(address)
                || isIpv4Mapped(address, true)) {
            throw new IllegalArgumentException(
                    "connector endpoint must not resolve to metadata, link-local or IPv6 loopback: " + endpointId);
        }
    }

    /**
     * 解析 IPv4/IPv6 字面量；不是字面量时返回 {@code null}。
     *
     * @param host 已归一化主机
     * @return 4 或 16 字节地址；非字面量返回 null
     */
    private static byte[] parseIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException exception) {
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length == 4) {
            byte[] address = new byte[4];
            for (int index = 0; index < 4; index++) {
                int value = parseIpv4Part(parts[index]);
                if (value < 0) {
                    return null;
                }
                address[index] = (byte) value;
            }
            return address;
        }
        // inet_aton 兼容形式（单整数/十六进制/八进制）也会被 JDK 解析为 IPv4；只对全数字/十六进制点串尝试，
        // 避免把普通主机名（含字母）误当字面量。
        if (host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == 'x' || ch == 'X')) {
            try {
                byte[] address = InetAddress.getByName(host).getAddress();
                return address.length == 4 ? address : null;
            } catch (UnknownHostException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析 IPv4 单段（十进制/十六进制/八进制）。
     *
     * @param part 段文本
     * @return 0–255；非法返回 -1
     */
    private static int parseIpv4Part(String part) {
        if (part.isEmpty() || part.length() > 10) {
            return -1;
        }
        int radix = 10;
        int start = 0;
        if (part.length() > 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
            radix = 16;
            start = 2;
        } else if (part.length() > 1 && part.startsWith("0")) {
            radix = 8;
            start = 1;
        }
        int value = 0;
        for (int index = start; index < part.length(); index++) {
            int digit = Character.digit(part.charAt(index), radix);
            if (digit < 0) {
                return -1;
            }
            value = value * radix + digit;
            if (value > 255) {
                return -1;
            }
        }
        return value;
    }

    /**
     * 判断是否为云元数据地址。
     *
     * @param address 4 或 16 字节地址
     * @return 元数据地址返回 true
     */
    private static boolean isMetadataAddress(byte[] address) {
        if (address == null) {
            return false;
        }
        if (address.length == 4) {
            return (address[0] & 0xff) == 169 && (address[1] & 0xff) == 254
                    || bytesEqual(address, ALIYUN_METADATA)
                    || bytesEqual(address, ORACLE_METADATA);
        }
        if (address.length == 16) {
            if (isIpv4Mapped(address, false)) {
                return isMetadataAddress(java.util.Arrays.copyOfRange(address, 12, 16));
            }
            return bytesEqual(address, AWS_IPV6_METADATA);
        }
        return false;
    }

    /**
     * 判断是否为链路本地地址。
     *
     * @param address 4 或 16 字节地址
     * @return 链路本地返回 true
     */
    private static boolean isLinkLocal(byte[] address) {
        if (address == null) {
            return false;
        }
        if (address.length == 4) {
            return (address[0] & 0xff) == 169 && (address[1] & 0xff) == 254;
        }
        if (address.length == 16) {
            if (isIpv4Mapped(address, false)) {
                return isLinkLocal(java.util.Arrays.copyOfRange(address, 12, 16));
            }
            return (address[0] & 0xff) == 0xfe && (address[1] & 0xc0) == 0x80;
        }
        return false;
    }

    /**
     * 判断 IPv6 回环 {@code ::1}。
     *
     * @param address 地址字节
     * @return 回环返回 true
     */
    private static boolean isIpv6Loopback(byte[] address) {
        if (address == null || address.length != 16) {
            return false;
        }
        for (int index = 0; index < 15; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return address[15] == 1;
    }

    /**
     * 判断 IPv4-mapped IPv6；{@code includeLoopback} 为 true 时把映射回环也视为禁止。
     *
     * @param address 地址字节
     * @param includeLoopback 是否包含 127.0.0.0/8 映射
     * @return 命中时返回 true
     */
    private static boolean isIpv4Mapped(byte[] address, boolean includeLoopback) {
        if (address == null || address.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        if ((address[10] & 0xff) != 0xff || (address[11] & 0xff) != 0xff) {
            return false;
        }
        return !includeLoopback || (address[12] & 0xff) == 127;
    }

    /**
     * 比较地址字节。
     *
     * @param left 左值
     * @param right 右值
     * @return 相等返回 true
     */
    private static boolean bytesEqual(byte[] left, byte[] right) {
        return java.util.Arrays.equals(left, right);
    }
}
