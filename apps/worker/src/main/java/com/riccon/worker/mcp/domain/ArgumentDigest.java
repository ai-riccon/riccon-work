package com.riccon.worker.mcp.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * 工具入参摘要与脱敏预览。
 *
 * <p>摘要使用按键排序后的 JSON 计算 SHA-256，保证同一入参在不同 JVM 得到相同值；
 * 预览只保留短字符串并移除敏感键，禁止把完整工具入参写入数据库或日志。
 */
public final class ArgumentDigest {

    /** 单个字符串值在预览中的最大长度。 */
    private static final int PREVIEW_VALUE_LENGTH = 120;

    /** 预览中禁止出现的键片段。 */
    private static final String[] SENSITIVE_KEY_PARTS = {"token", "secret", "password", "credential", "key"};

    /** 工具类禁止实例化。 */
    private ArgumentDigest() {}

    /**
     * 规范化入参：递归按键排序，排除 null。
     *
     * @param arguments 原始入参
     * @return 规范映射
     */
    public static Map<String, Object> canonical(Map<String, Object> arguments) {
        return canonicalMap(arguments == null ? Map.of() : arguments);
    }

    /**
     * 生成脱敏预览映射。
     *
     * @param arguments 原始入参
     * @param maxLength 预览总长度上限
     * @return 可写入 JSON 列的预览
     */
    public static Map<String, Object> preview(Map<String, Object> arguments, int maxLength) {
        Map<String, Object> preview = new TreeMap<>();
        int budget = maxLength;
        for (Map.Entry<String, Object> entry : canonical(arguments).entrySet()) {
            if (isSensitive(entry.getKey())) {
                preview.put(entry.getKey(), "[redacted]");
                continue;
            }
            String rendered = String.valueOf(entry.getValue());
            int allowed = Math.min(
                    PREVIEW_VALUE_LENGTH, Math.max(0, budget - entry.getKey().length() - 4));
            if (allowed > 0 && rendered.length() > allowed) {
                rendered = rendered.substring(0, allowed) + "...";
            }
            preview.put(entry.getKey(), rendered);
            budget -= rendered.length();
            if (budget <= 0) {
                break;
            }
        }
        return preview;
    }

    /**
     * 判断入参是否包含敏感键；敏感键的值不能进入审批参数或恢复执行。
     *
     * @param arguments 入参
     * @return 是否包含敏感键
     */
    public static boolean hasSensitiveKey(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        for (String key : canonical(arguments).keySet()) {
            if (isSensitive(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断键是否敏感。
     *
     * @param key 键
     * @return 是否敏感
     */
    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        for (String part : SENSITIVE_KEY_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归规范化映射。
     *
     * @param source 原始映射
     * @return 排序后映射
     */
    private static Map<String, Object> canonicalMap(Map<String, Object> source) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> nestedSource = new TreeMap<>();
                nested.forEach((key, value) -> nestedSource.put(String.valueOf(key), value));
                sorted.put(entry.getKey(), canonicalMap(nestedSource));
            } else {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        return sorted;
    }
}
