package com.riccon.platform.common.model;

/**
 * 受管端点校验的稳定拒绝原因。
 *
 * <p>原因码同时用于启动失败日志与管理接口 400 响应的 {@code reason} 字段；对外只暴露这些稳定码，
 * 不暴露堆栈、解析后的 IP 或 DNS 应答（ADR-0021 §2.2）。
 */
public enum EndpointRejectionReason {

    /** 不是绝对 URL、缺少 host 或包含空白/控制字符。 */
    ENDPOINT_MALFORMED,

    /** 非 HTTPS；HTTP 仅在 DEMO + 显式开关下允许。 */
    ENDPOINT_SCHEME_NOT_ALLOWED,

    /** URL 包含 userinfo（{@code user:pass@}）。 */
    ENDPOINT_USERINFO_FORBIDDEN,

    /** URL 包含查询串。 */
    ENDPOINT_QUERY_FORBIDDEN,

    /** URL 包含片段。 */
    ENDPOINT_FRAGMENT_FORBIDDEN,

    /** 生产模式下的回环地址（localhost、127.0.0.0/8、::1）。 */
    ENDPOINT_LOOPBACK_FORBIDDEN,

    /** 云元数据地址或链路本地地址（169.254.0.0/16、100.100.100.200、192.0.0.192、fd00:ec2::254 等）。 */
    ENDPOINT_METADATA_FORBIDDEN,

    /** 演示端点在生产模式不可被路由选择。 */
    ENDPOINT_DEMO_ONLY,

    /** provider code 不在部署允许列表。 */
    ENDPOINT_NOT_ALLOWED,

    /** 同一 code 或同一 baseUrl 在允许列表内重复。 */
    ENDPOINT_DUPLICATE,

    /** 字段属于部署管理范围，管理接口不可写。 */
    ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED,

    /** 主机名无法解析；无法完成元数据/链路本地校验。 */
    ENDPOINT_UNRESOLVED
}
