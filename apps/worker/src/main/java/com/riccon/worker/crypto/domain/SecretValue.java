package com.riccon.worker.crypto.domain;

import java.util.Objects;

/**
 * 敏感字符串包装。
 *
 * <p>{@link #toString()} 固定返回脱敏占位，避免凭据被 record 的自动 toString、日志或断言意外打印。
 * 仅在构造 HTTP 认证头的最后一刻调用 {@link #reveal()}。
 *
 * @param value 明文敏感值
 */
public record SecretValue(String value) {

    /** 脱敏占位，所有日志与异常都只能看到该值。 */
    public static final String REDACTED = "[redacted]";

    /** 校验非空，避免把空凭据发送给上游。 */
    public SecretValue {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("secret value must not be blank");
        }
    }

    /**
     * 返回明文；调用方必须保证不写日志、不拼入异常。
     *
     * @return 明文
     */
    public String reveal() {
        return value;
    }

    /** 固定输出脱敏值。 */
    @Override
    public String toString() {
        return REDACTED;
    }

    /**
     * 比较明文是否相等，避免调用方直接比较包装对象。
     *
     * @param other 另一个敏感值
     * @return 是否相同
     */
    public boolean matches(SecretValue other) {
        return other != null && Objects.equals(value, other.value);
    }
}
