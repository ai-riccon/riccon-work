package com.riccon.platform.common.crypto;

/**
 * 信封加解密失败。
 *
 * <p>只携带稳定错误码与固定说明，不携带明文、密钥、nonce、密文或底层异常，避免把敏感信息带入
 * 日志与错误响应。调用方应把该异常映射为内部错误并失败关闭，禁止降级为明文。
 */
public final class EnvelopeException extends RuntimeException {

    /** 稳定错误码；与 Worker 内部错误映射保持一致。 */
    public static final String ERROR_CODE = CryptoErrorCodes.ENVELOPE_FAILURE;

    /**
     * 创建失败异常。
     *
     * @param message 固定说明，不得包含密钥或密文
     */
    public EnvelopeException(String message) {
        super(message);
    }
}
