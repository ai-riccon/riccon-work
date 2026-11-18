package com.riccon.api.oidc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M10 OIDC 配置属性。
 *
 * <p>安全约束（OPS-003）：{@code RICCON_OIDC_IDPS} 只以原始 {@link String} 承接，绝不直接绑定为类型化对象，
 * 避免 Spring 绑定失败异常把含 {@code clientSecret} 的整段值写入启动日志；解析与校验由
 * {@code OidcConfigurationParser} 负责。{@link #toString()} 对整段配置做掩码。
 *
 * <p>部署方式：生产通过 Helm Secret 注入 {@code RICCON_OIDC_IDPS} 与 {@code RICCON_PUBLIC_BASE_URL}；
 * M10 不提供运行时热更新（ADR-0019 第 1 节）。
 */
@ConfigurationProperties(prefix = "riccon")
public class OidcProperties {

    /** 对外可访问的基础地址；用于构造回调 URI 与联合登出回跳地址。 */
    private String publicBaseUrl = "http://localhost:8000";

    /** OIDC 子配置。 */
    private Oidc oidc = new Oidc();

    /**
     * 返回对外基础地址。
     *
     * @return 基础地址，不含尾部斜杠
     */
    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * 设置对外基础地址。
     *
     * @param publicBaseUrl 基础地址
     */
    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * 返回 OIDC 子配置。
     *
     * @return OIDC 子配置
     */
    public Oidc getOidc() {
        return oidc;
    }

    /**
     * 设置 OIDC 子配置。
     *
     * @param oidc OIDC 子配置
     */
    public void setOidc(Oidc oidc) {
        this.oidc = oidc;
    }

    /**
     * 掩码后的字符串表示：不输出原始 JSON 或任何客户端密钥。
     *
     * @return 脱敏字符串
     */
    @Override
    public String toString() {
        return "OidcProperties[publicBaseUrl=" + publicBaseUrl + ", oidc=<redacted>]";
    }

    /** OIDC 协议与发现配置。 */
    public static class Oidc {

        /** 原始 JSON 数组；只允许从 Secret/环境变量注入。 */
        private String idps = "[]";

        /** 是否允许 http issuer/端点；仅本地与测试可为 true。 */
        private boolean allowHttp;

        /** 发现文档缓存时长，默认 10 分钟。 */
        private Duration discoveryCacheTtl = Duration.ofMinutes(10);

        /** 发现请求连接超时，默认 3 秒。 */
        private Duration connectTimeout = Duration.ofSeconds(3);

        /** 发现请求读取超时，默认 5 秒。 */
        private Duration readTimeout = Duration.ofSeconds(5);

        /**
         * 返回原始 IdP JSON。
         *
         * @return 原始 JSON
         */
        public String getIdps() {
            return idps;
        }

        /**
         * 设置原始 IdP JSON。
         *
         * @param idps 原始 JSON
         */
        public void setIdps(String idps) {
            this.idps = idps;
        }

        /**
         * 是否允许 http。
         *
         * @return 允许时返回 {@code true}
         */
        public boolean isAllowHttp() {
            return allowHttp;
        }

        /**
         * 设置是否允许 http。
         *
         * @param allowHttp 是否允许
         */
        public void setAllowHttp(boolean allowHttp) {
            this.allowHttp = allowHttp;
        }

        /**
         * 发现文档缓存时长。
         *
         * @return 缓存时长
         */
        public Duration getDiscoveryCacheTtl() {
            return discoveryCacheTtl;
        }

        /**
         * 设置发现文档缓存时长。
         *
         * @param discoveryCacheTtl 缓存时长
         */
        public void setDiscoveryCacheTtl(Duration discoveryCacheTtl) {
            this.discoveryCacheTtl = discoveryCacheTtl;
        }

        /**
         * 连接超时。
         *
         * @return 连接超时
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * 设置连接超时。
         *
         * @param connectTimeout 连接超时
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * 读取超时。
         *
         * @return 读取超时
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /**
         * 设置读取超时。
         *
         * @param readTimeout 读取超时
         */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
