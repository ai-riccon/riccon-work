package com.riccon.worker.crypto.domain;

/**
 * 凭据解密失败。
 *
 * <p>消息只包含稳定错误码与脱敏说明；禁止携带密文、nonce、DEK、主密钥或底层异常消息，
 * 避免密钥材料通过日志、异常或错误响应泄漏（ADR-0006 第 5 节）。
 */
public class CredentialException extends RuntimeException {

    /** 稳定错误码。 */
    private final String errorCode;

    /**
     * 创建凭据异常。
     *
     * @param errorCode 稳定错误码
     * @param message 脱敏说明
     */
    public CredentialException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }
}
