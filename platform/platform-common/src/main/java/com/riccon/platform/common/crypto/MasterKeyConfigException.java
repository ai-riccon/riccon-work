package com.riccon.platform.common.crypto;

/**
 * 主密钥环配置错误。
 *
 * <p>只在启动装配或按需解密前的配置校验阶段抛出，继承 {@link IllegalStateException} 让 Spring 启动失败
 * 而不是以明文或临时密钥继续运行（ADR-0006 第 3 节）。异常只带稳定错误码、原因枚举与环境变量名，
 * 不携带密钥、DEK、nonce、密文或底层异常，避免配置值通过日志与错误链泄漏。
 */
public final class MasterKeyConfigException extends IllegalStateException {

    /** 配置失败原因；稳定枚举便于测试与运维定位，不参与对外文案。 */
    public enum Reason {
        /** 当前主密钥缺失。 */
        MASTER_KEY_MISSING,
        /** 当前或上一把主密钥 Base64 非法或长度不是 32 字节。 */
        MASTER_KEY_INVALID,
        /** 生产配置误用已知测试密钥。 */
        TEST_KEY_REJECTED,
        /** pepper 缺失。 */
        PEPPER_MISSING,
        /** pepper Base64 非法或长度不是 32 字节。 */
        PEPPER_INVALID,
        /** 启动 canary 自检失败，通常表示运行环境裁剪了 AES-GCM。 */
        CANARY_FAILED,
        /** 测试 profile 未注入主密钥时的占位状态，任何加密操作都必须失败关闭。 */
        NOT_CONFIGURED
    }

    /** 稳定错误码。 */
    private final String errorCode;

    /** 失败原因。 */
    private final Reason reason;

    /**
     * 创建配置错误。
     *
     * @param errorCode 稳定错误码
     * @param reason 失败原因
     * @param message 脱敏说明，不得包含密钥或配置原值
     */
    public MasterKeyConfigException(String errorCode, Reason reason, String message) {
        super(message);
        this.errorCode = errorCode;
        this.reason = reason;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 返回失败原因。
     *
     * @return 原因枚举
     */
    public Reason reason() {
        return reason;
    }
}
