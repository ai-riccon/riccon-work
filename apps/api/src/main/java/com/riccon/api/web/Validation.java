package com.riccon.api.web;

import com.riccon.api.error.BadRequestException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 请求字段校验。
 *
 * <p>契约要求所有外部输入在 controller 边界校验；这里只提供无框架依赖的基础断言，避免引入第二套校验框架。
 */
public final class Validation {

    /** 工具类禁止实例化。 */
    private Validation() {}

    /**
     * 校验必填文本长度。
     *
     * @param value 原值
     * @param field 字段名，用于错误说明
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @return 去除首尾空白后的值
     * @throws BadRequestException 为空或长度不合法
     */
    public static String requireText(String value, String field, int minLength, int maxLength) {
        if (value == null) {
            throw new BadRequestException(field + " is required");
        }
        String trimmed = value.strip();
        if (trimmed.length() < minLength || trimmed.length() > maxLength) {
            throw new BadRequestException(field + " length must be between " + minLength + " and " + maxLength);
        }
        return trimmed;
    }

    /**
     * 校验必填文本匹配指定模式。
     *
     * @param value 原值
     * @param field 字段名
     * @param pattern 允许的模式
     * @param maxLength 最大长度
     * @return 原值
     * @throws BadRequestException 为空、超长或不匹配
     */
    public static String requirePattern(String value, String field, Pattern pattern, int maxLength) {
        String trimmed = requireText(value, field, 1, maxLength);
        if (!pattern.matcher(trimmed).matches()) {
            throw new BadRequestException(field + " has invalid format");
        }
        return trimmed;
    }

    /**
     * 校验非空对象。
     *
     * @param value 值
     * @param field 字段名
     * @param <T> 类型
     * @return 原值
     * @throws BadRequestException 值为空
     */
    public static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new BadRequestException(field + " is required");
        }
        return value;
    }

    /**
     * 校验角色列表非空、无空白且不重复。
     *
     * @param roles 角色码列表
     * @param field 字段名
     * @return 不可变副本
     * @throws BadRequestException 列表为空、含空值或重复
     */
    public static List<String> requireRoles(List<String> roles, String field) {
        if (roles == null || roles.isEmpty()) {
            throw new BadRequestException(field + " must not be empty");
        }
        List<String> normalized =
                roles.stream().map(role -> requireText(role, field, 1, 64)).toList();
        if (normalized.size() != normalized.stream().distinct().count()) {
            throw new BadRequestException(field + " must not contain duplicates");
        }
        return normalized;
    }

    /**
     * 校验可选整数的范围。
     *
     * @param value 值
     * @param field 字段名
     * @param min 最小值
     * @param max 最大值
     * @return 原值
     * @throws BadRequestException 超出范围
     */
    public static Integer requireRange(Integer value, String field, int min, int max) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max);
        }
        return value;
    }
}
