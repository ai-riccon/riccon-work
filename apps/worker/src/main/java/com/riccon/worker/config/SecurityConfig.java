package com.riccon.worker.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Worker 安全基线。
 *
 * <p>Worker 只应被集群内部调用，不直接暴露给浏览器或公网。健康检查公开；两个内部端点由控制器/服务校验
 * HMAC 令牌；其余路径默认拒绝。M04 的 {@code /actuator/info} 与 {@code /actuator/prometheus} 按
 * {@code RICCON_OPS_ALLOWED_CIDRS} 限制来源（空=loopback），不为运维端点添加 permitAll，也不放宽默认拒绝
 * （ADR-0021 §2.2/§10.6）。
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(SecurityConfig.OpsProperties.class)
public class SecurityConfig {

    /**
     * 运维观测配置。
     *
     * @param allowedCidrs 允许读取 info 与抓取 Prometheus 的来源 CIDR，逗号分隔；空值仅 loopback
     */
    @ConfigurationProperties(prefix = "riccon.ops")
    public record OpsProperties(@DefaultValue("") String allowedCidrs) {}

    /**
     * 构建默认拒绝的安全过滤器链。
     *
     * @param http Spring Security 的 HTTP 配置入口
     * @param opsProperties 运维来源配置
     * @return 安全过滤器链
     * @throws Exception 过滤器链构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OpsProperties opsProperties) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // M04：info 与 Prometheus 只允许 RICCON_OPS_ALLOWED_CIDRS（空=loopback）的来源。
                        .requestMatchers("/actuator/info", "/actuator/prometheus")
                        .access(opsEndpointAccess(opsProperties.allowedCidrs()))
                        // 内部端点只允许集群内访问；仅放行两个已实现路径，令牌在控制器/服务中强制校验。
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,
                                "/internal/v1/chat/streams",
                                "/internal/v1/runs/*/cancel")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * 返回 Actuator 运维端点（info/prometheus）的来源授权规则。
     *
     * <p>空配置仅允许 loopback；非空只允许列出的 CIDR。非法 CIDR 在启动时失败。
     *
     * @param allowedCidrs 逗号分隔的 CIDR 配置
     * @return 访问决策
     */
    static AuthorizationManager<RequestAuthorizationContext> opsEndpointAccess(String allowedCidrs) {
        AllowedCidrs cidrs = AllowedCidrs.parse(allowedCidrs);
        return (authentication, context) ->
                new AuthorizationDecision(cidrs.permits(context.getRequest().getRemoteAddr()));
    }

    /**
     * 运维来源 CIDR 允许列表。
     *
     * <p>API 侧有一份同语义实现；两处都只用于 Actuator 来源过滤，不进入业务包。
     * 空配置只允许 loopback；非空配置只允许列出的 CIDR；IPv4 与 IPv6 都支持。
     */
    static final class AllowedCidrs {

        /** 是否只允许 loopback（空配置）。 */
        private final boolean loopbackOnly;

        /** 允许网段。 */
        private final List<Cidr> cidrs;

        /**
         * 创建来源允许列表。
         *
         * @param loopbackOnly 是否只允许 loopback
         * @param cidrs 允许网段
         */
        private AllowedCidrs(boolean loopbackOnly, List<Cidr> cidrs) {
            this.loopbackOnly = loopbackOnly;
            this.cidrs = List.copyOf(cidrs);
        }

        /**
         * 解析逗号分隔的 CIDR；空白视为空配置。
         *
         * @param raw 原始配置
         * @return 允许列表
         * @throws IllegalArgumentException CIDR 非法
         */
        static AllowedCidrs parse(String raw) {
            String value = raw == null ? "" : raw.strip();
            if (value.isEmpty()) {
                return new AllowedCidrs(true, List.of());
            }
            List<Cidr> parsed = new ArrayList<>();
            for (String part : value.split(",")) {
                String token = part.strip();
                if (token.isEmpty()) {
                    continue;
                }
                parsed.add(Cidr.parse(token));
            }
            if (parsed.isEmpty()) {
                return new AllowedCidrs(true, List.of());
            }
            return new AllowedCidrs(false, parsed);
        }

        /**
         * 判断来源地址是否允许。
         *
         * @param remoteAddress 请求来源地址
         * @return 允许返回 true
         */
        boolean permits(String remoteAddress) {
            if (remoteAddress == null || remoteAddress.isBlank()) {
                return false;
            }
            InetAddress address;
            try {
                address = InetAddress.getByName(remoteAddress);
            } catch (UnknownHostException exception) {
                return false;
            }
            if (loopbackOnly) {
                return address.isLoopbackAddress();
            }
            for (Cidr cidr : cidrs) {
                if (cidr.contains(address)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 单个 CIDR 网段。 */
    static final class Cidr {

        /** 网络地址字节。 */
        private final byte[] network;

        /** 前缀长度。 */
        private final int prefix;

        /**
         * 创建网段。
         *
         * @param network 网络地址
         * @param prefix 前缀长度
         */
        private Cidr(byte[] network, int prefix) {
            this.network = network.clone();
            this.prefix = prefix;
        }

        /**
         * 解析 {@code address/prefix}。
         *
         * @param token 网段文本
         * @return 网段
         * @throws IllegalArgumentException 格式或地址非法
         */
        static Cidr parse(String token) {
            int slash = token.lastIndexOf('/');
            if (slash <= 0 || slash == token.length() - 1) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry must be address/prefix");
            }
            String addressText = token.substring(0, slash).strip();
            String prefixText = token.substring(slash + 1).strip();
            InetAddress address;
            try {
                address = InetAddress.getByName(addressText);
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry has an invalid address", exception);
            }
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = Integer.parseInt(prefixText);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("ops.allowed-cidrs entry has an invalid prefix", exception);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("ops.allowed-cidrs prefix must be between 0 and " + bits);
            }
            return new Cidr(address.getAddress(), prefix);
        }

        /**
         * 判断地址是否落在网段内。
         *
         * @param address 来源地址
         * @return 命中返回 true
         */
        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
