package com.riccon.worker.mcp.domain;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具入参摘要与脱敏预览。
 *
 * <p>摘要使用按键排序后的 JSON 计算 SHA-256，保证同一入参在不同 JVM 得到相同值；
 * 预览只保留白名单键、受限深度与长度，并递归移除敏感键与敏感值，禁止把完整工具入参
 * 写入数据库、阶段详情或日志。
 *
 * <p>预览不是重放数据：审批通过后的完整参数保存在 {@code approval.params_ciphertext} 密文列，
 * 由 {@code ApprovalParamsCrypto} 解密后使用（ADR-0017）。
 */
public final class ArgumentDigest {

    /** 预览中单个字符串值的默认最大长度。 */
    private static final int PREVIEW_VALUE_LENGTH = 120;

    /** 预览默认最多保留的顶层键数量。 */
    private static final int MAX_PREVIEW_KEYS = 16;

    /** 预览允许的最大嵌套深度；超过后只保留截断标记。 */
    private static final int MAX_PREVIEW_DEPTH = 3;

    /** 数组在预览中最多保留的元素数量。 */
    private static final int MAX_PREVIEW_ITEMS = 10;

    /** 预览中禁止出现的键片段。 */
    private static final String[] SENSITIVE_KEY_PARTS = {
        "token", "secret", "password", "credential", "key", "authorization", "cookie", "session"
    };

    /** 常见敏感值形态；命中后整个值替换为 redacted，避免密钥经非敏感键泄露。 */
    private static final Pattern[] SENSITIVE_VALUE_PATTERNS = {
        Pattern.compile("(?i)^bearer\\s+\\S+"),
        Pattern.compile("(?i)^basic\\s+\\S+"),
        Pattern.compile("(?i).*sk-[A-Za-z0-9_-]{8,}.*"),
        Pattern.compile("(?i).*(password|secret|token|api[_-]?key)\\s*[:=].*"),
        Pattern.compile("^eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\..*$")
    };

    /** 预览字节预算使用的 JSON 序列化器；只用于测量，不向外输出。 */
    private static final ObjectMapper PREVIEW_MAPPER = new ObjectMapper();

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
     * @param maxBytes 预览序列化后的 UTF-8 字节上限
     * @return 可写入 JSON 列的预览
     */
    public static Map<String, Object> preview(Map<String, Object> arguments, int maxBytes) {
        return preview(arguments, maxBytes, MAX_PREVIEW_KEYS, PREVIEW_VALUE_LENGTH);
    }

    /**
     * 生成脱敏预览映射，允许调用方指定键数量与字符串长度。
     *
     * @param arguments 原始入参
     * @param maxBytes 预览序列化后的 UTF-8 字节上限
     * @param maxKeys 顶层键数量上限
     * @param maxString 单个字符串长度上限
     * @return 可写入 JSON 列的预览，永远不超过 maxBytes
     */
    public static Map<String, Object> preview(Map<String, Object> arguments, int maxBytes, int maxKeys, int maxString) {
        if (maxBytes <= 2 || maxKeys <= 0 || maxString <= 0) {
            return Map.of();
        }
        Map<String, Object> source = canonical(arguments);
        Map<String, Object> candidate = renderMap(source, 0, maxKeys, maxString);
        if (previewBytes(candidate) <= maxBytes) {
            return candidate;
        }
        // 先逐步缩短字符串；仍超限时再丢弃低优先级键，保证 JSON 对象与列上限同时成立。
        int cap = maxString;
        while (previewBytes(candidate) > maxBytes && cap > 8) {
            cap = Math.max(8, cap / 2);
            candidate = renderMap(source, 0, maxKeys, cap);
        }
        while (previewBytes(candidate) > maxBytes && !candidate.isEmpty()) {
            candidate.remove(candidate.keySet().iterator().next());
        }
        return previewBytes(candidate) <= maxBytes ? candidate : Map.of();
    }

    /**
     * 判断入参是否包含敏感键；敏感键的值不能进入审批参数或恢复执行。
     *
     * @param arguments 入参
     * @return 是否包含敏感键
     */
    public static boolean hasSensitiveKey(Map<String, Object> arguments) {
        return containsSensitiveKey(arguments);
    }

    /**
     * 判断键是否敏感。
     *
     * @param key 键
     * @return 是否敏感
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String part : SENSITIVE_KEY_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归渲染映射；键排序、限制键数与深度。
     *
     * @param source 原始映射
     * @param depth 当前深度
     * @param maxKeys 当前层键数量上限
     * @param maxString 字符串长度上限
     * @return 渲染后的映射
     */
    private static Map<String, Object> renderMap(Map<String, Object> source, int depth, int maxKeys, int maxString) {
        Map<String, Object> rendered = new TreeMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= maxKeys) {
                break;
            }
            if (isSensitiveKey(entry.getKey())) {
                rendered.put(entry.getKey(), "[redacted]");
                continue;
            }
            rendered.put(entry.getKey(), renderValue(entry.getValue(), depth, maxKeys, maxString));
        }
        return rendered;
    }

    /**
     * 渲染单个值；超深、超长或敏感值统一替换为截断标记。
     *
     * @param value 原始值
     * @param depth 当前深度
     * @param maxKeys 每层键数量上限
     * @param maxString 字符串长度上限
     * @return 可序列化的预览值
     */
    private static Object renderValue(Object value, int depth, int maxKeys, int maxString) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            if (depth + 1 >= MAX_PREVIEW_DEPTH) {
                return "[truncated]";
            }
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return renderMap(normalized, depth + 1, maxKeys, maxString);
        }
        if (value instanceof Collection<?> collection) {
            if (depth + 1 >= MAX_PREVIEW_DEPTH) {
                return "[truncated]";
            }
            List<Object> items = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= MAX_PREVIEW_ITEMS) {
                    break;
                }
                items.add(renderValue(item, depth + 1, maxKeys, maxString));
            }
            return items;
        }
        if (value.getClass().isArray()) {
            if (depth + 1 >= MAX_PREVIEW_DEPTH) {
                return "[truncated]";
            }
            int length = Math.min(Array.getLength(value), MAX_PREVIEW_ITEMS);
            List<Object> items = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                items.add(renderValue(Array.get(value, index), depth + 1, maxKeys, maxString));
            }
            return items;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = String.valueOf(value);
        if (isSensitiveValue(text)) {
            return "[redacted]";
        }
        return truncate(text, maxString);
    }

    /**
     * 递归判断映射/集合中是否出现敏感键。
     *
     * @param value 待检查值
     * @return 是否包含敏感键
     */
    private static boolean containsSensitiveKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isSensitiveKey(String.valueOf(entry.getKey())) || containsSensitiveKey(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (containsSensitiveKey(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (containsSensitiveKey(Array.get(value, index))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断字符串值是否像凭据；命中时整个值脱敏，避免通过非敏感键泄露。
     *
     * @param value 字符串值
     * @return 是否敏感
     */
    private static boolean isSensitiveValue(String value) {
        for (Pattern pattern : SENSITIVE_VALUE_PATTERNS) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 截断字符串并保留省略号。
     *
     * @param value 原值
     * @param maxLength 最大长度
     * @return 截断值
     */
    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * 测量预览 JSON 的 UTF-8 字节数。
     *
     * @param preview 预览映射
     * @return 字节数
     */
    private static int previewBytes(Map<String, Object> preview) {
        try {
            return PREVIEW_MAPPER.writeValueAsBytes(preview).length;
        } catch (JacksonException ex) {
            // 预览只含字符串、数字、布尔与集合；序列化失败时按近似长度继续收缩，不向外抛错。
            return String.valueOf(preview).getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * 递归规范化映射；列表中若含映射也按键排序，保证摘要稳定。
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
            sorted.put(entry.getKey(), canonicalValue(entry.getValue()));
        }
        return sorted;
    }

    /**
     * 规范化单个值。
     *
     * @param value 原始值
     * @return 规范值
     */
    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), item));
            return canonicalMap(nested);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                normalized.add(canonicalValue(item));
            }
            return normalized;
        }
        return value;
    }
}
