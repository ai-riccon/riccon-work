package com.riccon.platform.common.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 租户标识值对象。
 *
 * <p>租户 ID 由平台生成，只允许字母、数字、下划线和短横线，长度 1–64。禁止把租户名称、邮箱或任何 PII 直接当作
 * 租户 ID 使用。
 *
 * @param value 租户标识的字符串值
 */
public record TenantId(String value) {

    /** 租户 ID 允许的字符集与长度。 */
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /**
     * 校验并创建租户标识。
     *
     * @param value 租户标识的字符串值
     * @throws IllegalArgumentException 值为空或不满足字符集约束
     */
    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantId contains illegal characters: " + value);
        }
    }

    /**
     * 从字符串创建租户标识。
     *
     * @param value 租户标识的字符串值
     * @return 校验通过的租户标识
     */
    public static TenantId of(String value) {
        return new TenantId(Objects.requireNonNull(value, "value must not be null"));
    }

    /** 返回原始值，便于日志与序列化。 */
    @Override
    public String toString() {
        return value;
    }
}
