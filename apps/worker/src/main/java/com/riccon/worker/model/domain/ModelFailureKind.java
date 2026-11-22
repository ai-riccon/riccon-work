package com.riccon.worker.model.domain;

/**
 * 底层模型客户端失败的物理分类。
 *
 * <p>降级状态机需要区分“5xx 可同 key 重试一次”和“连接失败不重试同 key”（ADR-0021 §5.1），
 * 而 {@link ModelException#errorCode()} 只描述员工侧稳定错误码，二者不能混用。
 */
public enum ModelFailureKind {

    /** 未分类；旧构造器创建的错误按错误码回退映射。 */
    UNKNOWN,

    /** 连接超时/连接拒绝/DNS 失败等传输层失败。 */
    TRANSPORT,

    /** 等待响应头、首字或读流超时。 */
    TIMEOUT,

    /** 上游限流 429。 */
    RATE_LIMITED,

    /** 上游 5xx。 */
    SERVER_ERROR,

    /** 上游 401。 */
    UNAUTHORIZED,

    /** 上游 403。 */
    FORBIDDEN,

    /** 协议结构或 JSON 非法、3xx、错误载荷。 */
    INVALID_RESPONSE,

    /** 凭据解密失败、DEK 缺失等配置问题。 */
    CONFIGURATION
}
