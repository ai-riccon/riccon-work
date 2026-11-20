package com.riccon.platform.common.model;

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
 * 受管模型端点策略：部署允许列表、URL 校验与生产/演示标签。
 *
 * <p>纯 JDK 实现，API（保存/目录）与 Worker（启动/调用前）共用同一套语义（ADR-0021 §2.2）：
 *
 * <ul>
 *   <li>只允许 HTTPS；{@code http} 仅在 {@link ModelDeploymentMode#DEMO} + 显式 allow-http 开关下允许；
 *   <li>禁止 userinfo、query、fragment；
 *   <li>永久拒绝云元数据与链路本地地址；生产模式额外拒绝回环地址；
 *   <li>先做 IPv4/IPv6 归一化（十进制/十六进制/八进制、IPv4-mapped IPv6、末尾点 FQDN）；
 *   <li>启动时通过 {@link HostResolver} 解析 host，拒绝解析到元数据/链路本地/生产回环地址；不固定解析结果。
 * </ul>
 *
 * <p>禁止重定向由 HTTP 客户端固定 {@code Redirect.NEVER} 保证（ADR-0021 §2.2），本类不负责出网。
 */
public final class ManagedEndpointPolicy {

    /** 端点代码规则：小写字母/数字开头，允许短横线，长度 2–64（ADR-0021 §2.1）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    /** 元数据服务主机名（末尾点已归一化后比较）。 */
    private static final Set<String> METADATA_HOSTNAMES = Set.of("metadata.google.internal", "metadata");

    /** 阿里云元数据地址 100.100.100.200。 */
    private static final byte[] ALIYUN_METADATA = new byte[] {100, 100, 100, (byte) 200};

    /** Oracle Cloud 元数据地址 192.0.0.192。 */
    private static final byte[] ORACLE_METADATA = new byte[] {(byte) 192, 0, 0, (byte) 192};

    /** AWS ECS/EKS IPv6 元数据地址 fd00:ec2::254。 */
    private static final byte[] AWS_IPV6_METADATA =
            new byte[] {(byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};

    /** 部署模式。 */
    private final ModelDeploymentMode deploymentMode;

    /** 是否允许明文 HTTP；仅 DEMO 可开启。 */
    private final boolean allowHttp;

    /** 主机解析接缝。 */
    private final HostResolver hostResolver;

    /**
     * 创建端点策略。
     *
     * @param deploymentMode 部署模式
     * @param allowHttp 是否允许 HTTP；PRODUCTION 下开启会被拒绝
     * @param hostResolver 主机解析器
     * @throws ManagedEndpointPolicyException 生产模式开启 HTTP 开关
     */
    public ManagedEndpointPolicy(ModelDeploymentMode deploymentMode, boolean allowHttp, HostResolver hostResolver) {
        this.deploymentMode = Objects.requireNonNull(deploymentMode, "deploymentMode must not be null");
        this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver must not be null");
        if (allowHttp && deploymentMode == ModelDeploymentMode.PRODUCTION) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED,
                    "allow-http",
                    "riccon.model.allow-http is only allowed in DEMO mode");
        }
        this.allowHttp = allowHttp;
    }

    /**
     * 返回部署模式。
     *
     * @return 部署模式
     */
    public ModelDeploymentMode deploymentMode() {
        return deploymentMode;
    }

    /**
     * 校验部署配置并生成允许列表。
     *
     * <p>每个条目校验 code 规则、标签、URL（含 DNS 解析）与重复项；任一失败即抛出，
     * 由调用方把启动失败暴露给部署方，不做静默降级（ADR-0021 §2.1/§2.2）。
     *
     * @param configs 部署配置条目；为空表示未配置
     * @return 不可变允许列表
     * @throws ManagedEndpointPolicyException 配置非法
     */
    public ManagedEndpointRegistry load(List<ManagedModelEndpointConfig> configs) {
        List<ManagedModelEndpointConfig> entries = configs == null ? List.of() : configs;
        List<ManagedModelEndpoint> endpoints = new ArrayList<>(entries.size());
        Set<String> codes = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (ManagedModelEndpointConfig config : entries) {
            if (config == null) {
                throw malformed("endpoints", "model endpoint entry must not be null");
            }
            String code = config.code() == null ? "" : config.code().strip();
            if (!CODE_PATTERN.matcher(code).matches()) {
                throw malformed("code", "model endpoint code must match " + CODE_PATTERN.pattern());
            }
            if (!codes.add(code)) {
                throw new ManagedEndpointPolicyException(
                        EndpointRejectionReason.ENDPOINT_DUPLICATE, "code", "duplicate model endpoint code: " + code);
            }
            EndpointEnvironmentLabel label;
            try {
                label = EndpointEnvironmentLabel.parse(config.environmentLabel());
            } catch (IllegalArgumentException exception) {
                throw malformed("environmentLabel", exception.getMessage());
            }
            URI baseUrl = validateConfiguredUrl(config.baseUrl(), "baseUrl");
            String normalized = normalizeBaseUrl(baseUrl);
            if (!urls.add(normalized)) {
                throw new ManagedEndpointPolicyException(
                        EndpointRejectionReason.ENDPOINT_DUPLICATE,
                        "baseUrl",
                        "duplicate model endpoint baseUrl: " + normalized);
            }
            endpoints.add(new ManagedModelEndpoint(code, URI.create(normalized), label));
        }
        return new ManagedEndpointRegistry(deploymentMode, endpoints);
    }

    /**
     * 校验单个 URL 的结构、协议、userinfo/query/fragment 与字面量地址。
     *
     * <p>本方法不做 DNS 解析，适合“管理接口尝试写入 baseUrl”的场景：结构非法返回具体原因，
     * 结构合法但属于部署管理字段时由调用方返回 {@code ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED}。
     *
     * @param rawUrl 原始 URL
     * @param field 出错字段名
     * @return 解析后的 URI
     * @throws ManagedEndpointPolicyException URL 非法
     */
    public URI validateUrl(String rawUrl, String field) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            throw malformed(field, "model endpoint URL must not be empty");
        }
        for (int index = 0; index < rawUrl.length(); index++) {
            char current = rawUrl.charAt(index);
            // 空白与控制字符会造成 URL 解析歧义，直接拒绝；不做 trim 宽容处理。
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                throw malformed(field, "model endpoint URL must not contain whitespace or control characters");
            }
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException exception) {
            throw malformed(field, "model endpoint URL is not a valid URI");
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw malformed(field, "model endpoint URL must be absolute");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            // HTTPS 始终允许。
        } else if ("http".equals(scheme)) {
            if (!(allowHttp && deploymentMode == ModelDeploymentMode.DEMO)) {
                throw new ManagedEndpointPolicyException(
                        EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED,
                        field,
                        "model endpoint must use https (http is only allowed in DEMO with allow-http)");
            }
        } else {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_SCHEME_NOT_ALLOWED, field, "model endpoint scheme must be https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_USERINFO_FORBIDDEN,
                    field,
                    "model endpoint URL must not contain userinfo");
        }
        if (uri.getRawQuery() != null) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_QUERY_FORBIDDEN,
                    field,
                    "model endpoint URL must not contain a query");
        }
        if (uri.getRawFragment() != null) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_FRAGMENT_FORBIDDEN,
                    field,
                    "model endpoint URL must not contain a fragment");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw malformed(field, "model endpoint URL has an invalid port");
        }
        String host = extractHost(uri);
        if (host == null || host.isBlank()) {
            throw malformed(field, "model endpoint URL must contain a host");
        }
        String normalizedHost = ManagedEndpointRegistry.normalizeHost(host);
        byte[] literal = parseIpLiteral(normalizedHost);
        if (literal != null) {
            rejectLiteralAddress(literal, field);
        } else if (METADATA_HOSTNAMES.contains(normalizedHost)) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN,
                    field,
                    "model endpoint host is a cloud metadata service");
        } else if (isLoopbackHostname(normalizedHost) && deploymentMode == ModelDeploymentMode.PRODUCTION) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_LOOPBACK_FORBIDDEN,
                    field,
                    "model endpoint host is loopback and PRODUCTION mode forbids it");
        }
        return uri;
    }

    /**
     * 校验部署配置中的 URL：结构校验 + DNS 解析后的地址校验。
     *
     * @param rawUrl 原始 URL
     * @param field 出错字段名
     * @return 解析后的 URI
     * @throws ManagedEndpointPolicyException URL 非法或解析到禁止地址
     */
    public URI validateConfiguredUrl(String rawUrl, String field) {
        URI uri = validateUrl(rawUrl, field);
        verifyResolvedHost(uri, field);
        return uri;
    }

    /**
     * 解析 host 并拒绝元数据、链路本地与生产回环地址。
     *
     * <p>调用方可在每次模型调用前复用它（Worker 运行时再次校验）；只校验解析结果，不缓存、不固定 IP。
     *
     * @param uri 已通过结构校验的 URI
     * @param field 出错字段名
     * @throws ManagedEndpointPolicyException 解析失败或解析到禁止地址
     */
    public void verifyResolvedHost(URI uri, String field) {
        String host = extractHost(uri);
        if (host == null || host.isBlank()) {
            throw malformed(field, "model endpoint URL must contain a host");
        }
        String normalizedHost = ManagedEndpointRegistry.normalizeHost(host);
        if (parseIpLiteral(normalizedHost) != null) {
            // 字面量地址已在结构校验阶段检查，无需再触发 DNS。
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(normalizedHost);
        } catch (UnknownHostException exception) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_UNRESOLVED, field, "model endpoint host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_UNRESOLVED, field, "model endpoint host resolved to no address");
        }
        for (InetAddress address : addresses) {
            if (address == null) {
                continue;
            }
            if (address.isLinkLocalAddress() || isMetadataAddress(address.getAddress())) {
                throw new ManagedEndpointPolicyException(
                        EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN,
                        field,
                        "model endpoint resolves to a metadata or link-local address");
            }
            if (deploymentMode == ModelDeploymentMode.PRODUCTION && address.isLoopbackAddress()) {
                throw new ManagedEndpointPolicyException(
                        EndpointRejectionReason.ENDPOINT_LOOPBACK_FORBIDDEN,
                        field,
                        "model endpoint resolves to a loopback address in PRODUCTION mode");
            }
        }
    }

    /**
     * 判断标签在当前部署模式下是否可被路由选择。
     *
     * @param label 端点标签
     * @return 演示端点在生产模式返回 false
     */
    public boolean isRoutable(EndpointEnvironmentLabel label) {
        return label != null
                && (deploymentMode == ModelDeploymentMode.DEMO || label == EndpointEnvironmentLabel.PRODUCTION);
    }

    /**
     * 规范化 base URL 用于比较：scheme/host 小写、去默认端口与 host 末尾点、空路径补 {@code /}。
     *
     * @param uri 已通过结构校验的 URI
     * @return 规范化字符串
     */
    public static String normalizeBaseUrl(URI uri) {
        Objects.requireNonNull(uri, "uri must not be null");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = extractHost(uri);
        String normalizedHost = host == null ? "" : ManagedEndpointRegistry.normalizeHost(host);
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
     * 提取 URI 的 host；对非法 host 语法（如十六进制 IPv4）回退解析 authority。
     *
     * @param uri URI
     * @return host（IPv6 去方括号）；无法提取时返回 {@code null}
     */
    private static String extractHost(URI uri) {
        String host = uri.getHost();
        if (host != null) {
            return ManagedEndpointRegistry.normalizeHost(host);
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
     * 拒绝字面量地址中的元数据、链路本地与生产回环地址。
     *
     * @param address 归一化后的地址字节
     * @param field 字段名
     */
    private void rejectLiteralAddress(byte[] address, String field) {
        if (isLinkLocal(address) || isMetadataAddress(address)) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_METADATA_FORBIDDEN,
                    field,
                    "model endpoint host is a metadata or link-local address");
        }
        if (deploymentMode == ModelDeploymentMode.PRODUCTION && isLoopback(address)) {
            throw new ManagedEndpointPolicyException(
                    EndpointRejectionReason.ENDPOINT_LOOPBACK_FORBIDDEN,
                    field,
                    "model endpoint host is loopback and PRODUCTION mode forbids it");
        }
    }

    /**
     * 解析 IPv4/IPv6 字面量；不是字面量时返回 {@code null}。
     *
     * <p>兼容 inet_aton 的常见编码：点分十进制、每段十六进制（{@code 0x..}）或八进制（前导 0），
     * 以及 1–4 段数字形式（如 {@code 2852039166} == 169.254.169.254）；IPv4-mapped IPv6 由 JDK 归一化。
     *
     * @param host 已归一化的 host
     * @return 4 或 16 字节地址；非字面量返回 null
     */
    private static byte[] parseIpLiteral(String host) {
        if (host.isEmpty()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            try {
                // IPv4-mapped IPv6（::ffff:a.b.c.d 或 ::ffff:a9fe:a9fe）由 JDK 归一化为 IPv4。
                return InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException exception) {
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return null;
        }
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
        long[] values = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            long value = parseWideIpv4Part(parts[index]);
            if (value < 0) {
                return null;
            }
            values[index] = value;
        }
        long numeric;
        if (parts.length == 1) {
            numeric = values[0];
        } else if (parts.length == 2) {
            if (values[0] > 0xff || values[1] > 0xffffffL) {
                return null;
            }
            numeric = (values[0] << 24) | values[1];
        } else {
            if (values[0] > 0xff || values[1] > 0xff || values[2] > 0xffffL) {
                return null;
            }
            numeric = (values[0] << 24) | (values[1] << 16) | values[2];
        }
        if (numeric < 0 || numeric > 0xffffffffL) {
            return null;
        }
        return new byte[] {(byte) (numeric >>> 24), (byte) (numeric >>> 16), (byte) (numeric >>> 8), (byte) numeric};
    }

    /**
     * 解析宽 IPv4 段（1–3 段形式），支持十进制/十六进制/八进制。
     *
     * @param part 段文本
     * @return 0–0xFFFFFFFF；非法返回 -1
     */
    private static long parseWideIpv4Part(String part) {
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
        long value = 0;
        for (int index = start; index < part.length(); index++) {
            int digit = Character.digit(part.charAt(index), radix);
            if (digit < 0) {
                return -1;
            }
            value = value * radix + digit;
            if (value > 0xffffffffL) {
                return -1;
            }
        }
        return value;
    }

    /**
     * 解析 IPv4 单个十进制/十六进制/八进制段。
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
            // inet_aton 语义：前导 0 表示八进制；"0" 本身仍按 0 处理。
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
     * 判断地址字节是否为云元数据地址。
     *
     * @param address 4 或 16 字节地址
     * @return 元数据地址返回 true
     */
    private static boolean isMetadataAddress(byte[] address) {
        if (address == null) {
            return false;
        }
        if (address.length == 4) {
            // 169.254.0.0/16 覆盖 AWS/GCP/Azure/阿里云等常见元数据地址（169.254.169.254/169.254.170.2）。
            return (address[0] & 0xff) == 169 && (address[1] & 0xff) == 254
                    || bytesEqual(address, ALIYUN_METADATA)
                    || bytesEqual(address, ORACLE_METADATA);
        }
        if (address.length == 16) {
            if (isIpv4Mapped(address)) {
                return isMetadataAddress(java.util.Arrays.copyOfRange(address, 12, 16));
            }
            return bytesEqual(address, AWS_IPV6_METADATA);
        }
        return false;
    }

    /**
     * 判断地址是否为链路本地（169.254/16、fe80::/10）。
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
            if (isIpv4Mapped(address)) {
                return isLinkLocal(java.util.Arrays.copyOfRange(address, 12, 16));
            }
            return (address[0] & 0xff) == 0xfe && (address[1] & 0xc0) == 0x80;
        }
        return false;
    }

    /**
     * 判断地址是否为回环。
     *
     * @param address 4 或 16 字节地址
     * @return 回环返回 true
     */
    private static boolean isLoopback(byte[] address) {
        if (address == null) {
            return false;
        }
        if (address.length == 4) {
            return (address[0] & 0xff) == 127;
        }
        if (address.length == 16) {
            if (isIpv4Mapped(address)) {
                return isLoopback(java.util.Arrays.copyOfRange(address, 12, 16));
            }
            for (int index = 0; index < 15; index++) {
                if (address[index] != 0) {
                    return false;
                }
            }
            return address[15] == 1;
        }
        return false;
    }

    /**
     * 判断 IPv6 是否为 IPv4-mapped（::ffff:a.b.c.d）。
     *
     * @param address 16 字节地址
     * @return IPv4-mapped 返回 true
     */
    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
    }

    /**
     * 判断主机名是否为回环名称。
     *
     * @param host 已归一化 host
     * @return localhost 或 *.localhost 返回 true
     */
    private static boolean isLoopbackHostname(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost");
    }

    /**
     * 比较字节数组。
     *
     * @param left 左值
     * @param right 右值
     * @return 完全相等返回 true
     */
    private static boolean bytesEqual(byte[] left, byte[] right) {
        return java.util.Arrays.equals(left, right);
    }

    /**
     * 构造结构非法异常。
     *
     * @param field 字段名
     * @param message 服务端说明
     * @return 异常
     */
    private static ManagedEndpointPolicyException malformed(String field, String message) {
        return new ManagedEndpointPolicyException(EndpointRejectionReason.ENDPOINT_MALFORMED, field, message);
    }
}
