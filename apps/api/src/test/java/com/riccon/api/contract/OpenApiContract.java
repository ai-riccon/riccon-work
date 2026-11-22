package com.riccon.api.contract;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * OpenAPI 契约测试的 YAML 读取与结构访问工具。
 *
 * <p>测试不依赖网络或 Spring 上下文：从仓库根定位 {@code contracts/openapi.yaml}，直接用 SnakeYAML 读取。
 * 只用于断言路径、方法、权限、schema 必填字段与 deferred 标记，不生成代码。
 */
final class OpenApiContract {

    /** 工具类禁止实例化。 */
    private OpenApiContract() {}

    /**
     * 读取契约。
     *
     * @return YAML 根映射
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> load() {
        Path file = locateRepositoryRoot().resolve("contracts").resolve("openapi.yaml");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read contracts/openapi.yaml", exception);
        }
    }

    /**
     * 从当前工作目录向上定位仓库根。
     *
     * @return 包含 {@code contracts/openapi.yaml} 的目录
     */
    static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("contracts").resolve("openapi.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("contracts/openapi.yaml not found from " + System.getProperty("user.dir"));
    }

    /**
     * 读取组件 schema。
     *
     * @param document 契约根
     * @param name schema 名
     * @return schema 映射
     */
    static Map<String, Object> schema(Map<String, Object> document, String name) {
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> schema = map(schemas.get(name));
        if (schema.isEmpty()) {
            throw new IllegalArgumentException("schema not found: " + name);
        }
        return schema;
    }

    /**
     * 读取路径项。
     *
     * @param document 契约根
     * @param path 路径
     * @return 路径项
     */
    static Map<String, Object> pathItem(Map<String, Object> document, String path) {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> item = map(paths.get(path));
        if (item.isEmpty()) {
            throw new IllegalArgumentException("path not found: " + path);
        }
        return item;
    }

    /**
     * 读取操作。
     *
     * @param document 契约根
     * @param path 路径
     * @param method HTTP 方法（小写）
     * @return 操作映射
     */
    static Map<String, Object> operation(Map<String, Object> document, String path, String method) {
        Map<String, Object> operation = map(pathItem(document, path).get(method));
        if (operation.isEmpty()) {
            throw new IllegalArgumentException("operation not found: " + method + " " + path);
        }
        return operation;
    }

    /**
     * 读取 200 JSON 响应 schema 的 {@code $ref} 名称。
     *
     * @param operation 操作
     * @return schema 名；没有引用时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    static String responseSchemaName(Map<String, Object> operation) {
        Map<String, Object> responses = map(operation.get("responses"));
        Map<String, Object> ok = map(responses.get("200"));
        Map<String, Object> content = map(ok.get("content"));
        Map<String, Object> json = map(content.get("application/json"));
        Map<String, Object> schema = map(json.get("schema"));
        Object ref = schema.get("$ref");
        if (!(ref instanceof String value) || !value.startsWith("#/components/schemas/")) {
            return null;
        }
        return value.substring("#/components/schemas/".length());
    }

    /**
     * 读取 schema 的 properties 键集合。
     *
     * @param schema schema
     * @return 属性名集合
     */
    static List<String> propertyNames(Map<String, Object> schema) {
        return List.copyOf(map(schema.get("properties")).keySet());
    }

    /**
     * 读取 required 列表。
     *
     * @param schema schema
     * @return 必填字段
     */
    static List<String> required(Map<String, Object> schema) {
        Object value = schema.get("required");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * 断言 schema 的 {@code additionalProperties} 为 false。
     *
     * @param schema schema
     * @return 是否为 false
     */
    static boolean additionalPropertiesFalse(Map<String, Object> schema) {
        return Boolean.FALSE.equals(schema.get("additionalProperties"));
    }

    /**
     * 读取操作权限码。
     *
     * @param operation 操作
     * @return 权限码
     */
    static String permission(Map<String, Object> operation) {
        return String.valueOf(operation.get("x-riccon-permission"));
    }

    /**
     * 读取 deferred 标记。
     *
     * @param operation 操作
     * @return 标记；不存在返回 {@code null}
     */
    static String deferred(Map<String, Object> operation) {
        Object value = operation.get("x-riccon-deferred");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 判断操作参数是否引用指定组件。
     *
     * @param operation 操作
     * @param component 组件名，如 {@code CsrfToken}
     * @return 引用返回 true
     */
    static boolean referencesParameter(Map<String, Object> operation, String component) {
        Object parameters = operation.get("parameters");
        if (!(parameters instanceof List<?> list)) {
            return false;
        }
        String expected = "#/components/parameters/" + component;
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(parameter -> expected.equals(parameter.get("$ref")));
    }

    /**
     * 合并 {@code allOf} 的 properties/required（支持内联对象与 {@code $ref}）。
     *
     * @param document 契约根
     * @param schema schema
     * @return 合并后的简化 schema
     */
    static Map<String, Object> mergedSchema(Map<String, Object> document, Map<String, Object> schema) {
        Object allOf = schema.get("allOf");
        if (!(allOf instanceof List<?> parts)) {
            return schema;
        }
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (Object part : parts) {
            Map<String, Object> partSchema = map(part);
            String ref = (String) partSchema.get("$ref");
            if (ref != null && ref.startsWith("#/components/schemas/")) {
                partSchema = schema(document, ref.substring("#/components/schemas/".length()));
            }
            properties.putAll(map(partSchema.get("properties")));
            required.addAll(required(partSchema));
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("properties", properties);
        merged.put("required", required);
        merged.put("additionalProperties", schema.get("additionalProperties"));
        return merged;
    }

    /**
     * 把 YAML 数组转成字符串列表。
     *
     * @param value 原始数组
     * @return 字符串列表；非数组返回空列表
     */
    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * 安全转换为字符串键映射。
     *
     * @param value 原始值
     * @return 映射；类型不符时返回空映射
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
