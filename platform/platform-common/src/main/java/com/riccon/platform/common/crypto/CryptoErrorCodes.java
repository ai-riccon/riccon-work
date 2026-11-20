package com.riccon.platform.common.crypto;

/**
 * 加密相关稳定错误码。
 *
 * <p>错误码一旦发布不得改变语义，只允许新增；调用方（API、Worker）按码映射为内部错误或 ProblemDetail。
 * 错误码与说明都不携带密钥、DEK、nonce、密文或底层异常，避免敏感信息进入日志与响应（ADR-0006 第 5 节）。
 */
public final class CryptoErrorCodes {

    /** 信封加解密失败：认证失败、格式错误、密钥环无法解开 DEK。 */
    public static final String ENVELOPE_FAILURE = "RICCON-CRYPTO-500";

    /** 主密钥/pepper 配置非法或缺失；只允许出现在启动失败与内部错误。 */
    public static final String MASTER_KEY_CONFIG = "RICCON-CRYPTO-501";

    /** 凭据指纹能力不可用（pepper 未配置）。 */
    public static final String FINGERPRINT_UNAVAILABLE = "RICCON-CRYPTO-502";

    /** 工具类禁止实例化。 */
    private CryptoErrorCodes() {}
}
