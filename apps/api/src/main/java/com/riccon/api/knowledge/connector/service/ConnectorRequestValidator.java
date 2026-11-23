package com.riccon.api.knowledge.connector.service;

import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.knowledge.connector.domain.ConnectorConfig;
import com.riccon.api.knowledge.service.KnowledgeBadRequestException;
import com.riccon.platform.domain.knowledge.ConnectorStatus;
import com.riccon.platform.domain.knowledge.ConnectorType;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 连接器请求体校验。
 *
 * <p>契约要求未知字段、缺省 {@code defaultVisibility}、非法枚举/长度都返回 400 {@code RICCON-KB-400}，
 * 且创建必须显式给出默认可见范围而不是依赖数据库默认值。这里以 {@link JsonNode} 逐字段解析，避免全局
 * Jackson 的“忽略未知字段”配置让契约失效。
 *
 * <p>错误说明只描述字段，不包含 bucket、对象 key、凭据或端点 URL。
 */
@Component
public class ConnectorRequestValidator {

    /** 名称最大长度。 */
    private static final int MAX_NAME_LENGTH = 120;

    /** endpointId 最大长度。 */
    private static final int MAX_ENDPOINT_ID_LENGTH = 64;

    /** bucket 最大长度（OpenAPI）。 */
    private static final int MAX_BUCKET_LENGTH = 255;

    /** prefix 最大长度。 */
    private static final int MAX_PREFIX_LENGTH = 1000;

    /** region 最大长度。 */
    private static final int MAX_REGION_LENGTH = 64;

    /** glob 最大长度。 */
    private static final int MAX_GLOB_LENGTH = 255;

    /** 扩展名最大数量。 */
    private static final int MAX_EXTENSIONS = 8;

    /** 排除 glob 最大数量。 */
    private static final int MAX_GLOBS = 50;

    /** accessKeyId 最大长度。 */
    private static final int MAX_ACCESS_KEY_ID = 256;

    /** secretAccessKey 最大长度。 */
    private static final int MAX_SECRET_KEY = 1024;

    /** 允许的扩展名（与 ConnectorConfig 白名单一致）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.copyOf(ConnectorConfig.DEFAULT_EXTENSIONS);

    /** endpointId 字符集；只允许安全字符，避免进入 SQL/日志时产生歧义。 */
    private static final Pattern ENDPOINT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * 解析创建请求。
     *
     * @param body 请求体
     * @return 创建命令
     * @throws KnowledgeBadRequestException 字段缺失或非法
     */
    public CreateCommand parseCreate(JsonNode body) {
        requireObject(body, "request body");
        requireAllowedKeys(
                body,
                Set.of(
                        "name",
                        "type",
                        "endpointId",
                        "config",
                        "defaultVisibility",
                        "defaultDepartmentId",
                        "scheduleEnabled",
                        "credential"));
        String name = requireText(body, "name", 1, MAX_NAME_LENGTH);
        ConnectorType type = parseType(requireText(body, "type", 1, 16));
        String endpointId = requireEndpointId(body.get("endpointId"));
        ConnectorConfig config = parseConfig(body.get("config"));
        VisibilityInput visibility = parseVisibility(body);
        boolean scheduleEnabled = optionalBoolean(body, "scheduleEnabled", false);
        CredentialInput credential =
                body.has("credential") && !body.get("credential").isNull()
                        ? parseCredential(body.get("credential"))
                        : null;
        return new CreateCommand(
                name,
                type,
                endpointId,
                config,
                visibility.visibility(),
                visibility.departmentId(),
                scheduleEnabled,
                credential);
    }

    /**
     * 解析部分更新请求；缺省字段保持原值。
     *
     * @param body 请求体
     * @return 更新命令
     * @throws KnowledgeBadRequestException 字段缺失或非法
     */
    public UpdateCommand parseUpdate(JsonNode body) {
        requireObject(body, "request body");
        requireAllowedKeys(
                body,
                Set.of(
                        "name",
                        "endpointId",
                        "config",
                        "defaultVisibility",
                        "defaultDepartmentId",
                        "scheduleEnabled",
                        "status"));
        if (body.isEmpty()) {
            throw bad("at least one field is required");
        }
        String name = body.has("name") ? requireText(body, "name", 1, MAX_NAME_LENGTH) : null;
        String endpointId = body.has("endpointId") ? requireEndpointId(body.get("endpointId")) : null;
        ConnectorConfig config = body.has("config") ? parseConfig(body.get("config")) : null;
        KnowledgeVisibility visibility = body.has("defaultVisibility")
                ? parseVisibilityValue(requireText(body, "defaultVisibility", 1, 16))
                : null;
        boolean departmentProvided = body.has("defaultDepartmentId");
        UUID departmentId =
                departmentProvided ? optionalUuid(body.get("defaultDepartmentId"), "defaultDepartmentId") : null;
        Boolean scheduleEnabled = body.has("scheduleEnabled") ? optionalBoolean(body, "scheduleEnabled", false) : null;
        ConnectorStatus status = body.has("status") ? parseTargetStatus(requireText(body, "status", 1, 16)) : null;
        return new UpdateCommand(
                name, endpointId, config, visibility, departmentId, departmentProvided, scheduleEnabled, status);
    }

    /**
     * 解析凭据请求。
     *
     * @param body 请求体
     * @return 凭据命令
     * @throws KnowledgeBadRequestException 字段缺失或非法
     */
    public CredentialInput parseCredentialBody(JsonNode body) {
        requireObject(body, "request body");
        return parseCredential(body);
    }

    /**
     * 校验可选查询枚举。
     *
     * @param value 原始值
     * @param field 字段名
     * @param type 枚举类型
     * @param <E> 枚举
     * @return 枚举或 null
     */
    public static <E extends Enum<E>> E optionalEnum(String value, String field, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException exception) {
            throw bad(field + " is not a valid value");
        }
    }

    /**
     * 校验分页。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页
     */
    public static PageQuery page(Integer page, Integer size) {
        try {
            return PageQuery.of(page, size);
        } catch (RuntimeException exception) {
            throw bad(exception.getMessage());
        }
    }

    /**
     * 解析并校验 config。
     *
     * @param node JSON 节点
     * @return 配置
     */
    private ConnectorConfig parseConfig(JsonNode node) {
        if (node == null || node.isNull()) {
            throw bad("config is required");
        }
        requireObject(node, "config");
        requireAllowedKeys(node, Set.of("bucket", "prefix", "region", "includeExtensions", "excludeGlobs"));
        String bucket = requireText(node, "bucket", 1, MAX_BUCKET_LENGTH);
        String prefix = node.has("prefix") && !node.get("prefix").isNull()
                ? optionalText(node, "prefix", MAX_PREFIX_LENGTH)
                : "";
        String region = node.has("region") && !node.get("region").isNull()
                ? optionalText(node, "region", MAX_REGION_LENGTH)
                : null;
        List<String> includeExtensions = parseExtensions(node.get("includeExtensions"));
        List<String> excludeGlobs = parseGlobs(node.get("excludeGlobs"));
        return new ConnectorConfig(bucket, prefix, region, includeExtensions, excludeGlobs);
    }

    /**
     * 解析扩展名白名单。
     *
     * @param node JSON 节点
     * @return 扩展名列表
     */
    private List<String> parseExtensions(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_EXTENSIONS) {
            throw bad("includeExtensions must be an array of at most " + MAX_EXTENSIONS + " items");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw bad("includeExtensions must contain strings");
            }
            String value = item.asText().strip().toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXTENSIONS.contains(value)) {
                throw bad("includeExtensions contains an unsupported extension");
            }
            if (!values.add(value)) {
                throw bad("includeExtensions must not contain duplicates");
            }
        }
        return List.copyOf(values);
    }

    /**
     * 解析排除 glob。
     *
     * @param node JSON 节点
     * @return glob 列表
     */
    private List<String> parseGlobs(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_GLOBS) {
            throw bad("excludeGlobs must be an array of at most " + MAX_GLOBS + " items");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw bad("excludeGlobs must contain strings");
            }
            String value = item.asText().strip();
            if (value.isEmpty() || value.length() > MAX_GLOB_LENGTH) {
                throw bad("excludeGlobs entries must be 1-" + MAX_GLOB_LENGTH + " characters");
            }
            if (!values.add(value)) {
                throw bad("excludeGlobs must not contain duplicates");
            }
        }
        return List.copyOf(values);
    }

    /**
     * 解析默认可见范围与部门。
     *
     * @param body 请求体
     * @return 可见范围输入
     */
    private VisibilityInput parseVisibility(JsonNode body) {
        String visibilityValue = requireText(body, "defaultVisibility", 1, 16);
        KnowledgeVisibility visibility = parseVisibilityValue(visibilityValue);
        UUID departmentId = null;
        if (body.has("defaultDepartmentId") && !body.get("defaultDepartmentId").isNull()) {
            departmentId = optionalUuid(body.get("defaultDepartmentId"), "defaultDepartmentId");
        }
        if (visibility == KnowledgeVisibility.DEPT && departmentId == null) {
            throw bad("defaultDepartmentId is required when defaultVisibility is DEPT");
        }
        if (visibility != KnowledgeVisibility.DEPT && departmentId != null) {
            throw bad("defaultDepartmentId is only allowed when defaultVisibility is DEPT");
        }
        return new VisibilityInput(visibility, departmentId);
    }

    /**
     * 解析凭据对象。
     *
     * @param node JSON 节点
     * @return 凭据
     */
    private CredentialInput parseCredential(JsonNode node) {
        requireObject(node, "credential");
        requireAllowedKeys(node, Set.of("accessKeyId", "secretAccessKey"));
        String accessKeyId = requireText(node, "accessKeyId", 1, MAX_ACCESS_KEY_ID);
        String secretAccessKey = requireText(node, "secretAccessKey", 1, MAX_SECRET_KEY);
        if (!accessKeyId.equals(accessKeyId.strip()) || !secretAccessKey.equals(secretAccessKey.strip())) {
            throw bad("credential must not contain leading or trailing whitespace");
        }
        return new CredentialInput(accessKeyId, secretAccessKey);
    }

    /**
     * 解析连接器类型。
     *
     * @param value 原始值
     * @return 类型
     */
    private static ConnectorType parseType(String value) {
        try {
            return ConnectorType.valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            throw bad("type must be S3");
        }
    }

    /**
     * 解析默认可见范围。
     *
     * @param value 原始值
     * @return 可见范围
     */
    private static KnowledgeVisibility parseVisibilityValue(String value) {
        if (!"TENANT".equals(value.strip()) && !"DEPT".equals(value.strip())) {
            throw bad("defaultVisibility must be TENANT or DEPT");
        }
        return KnowledgeVisibility.valueOf(value.strip());
    }

    /**
     * 解析 PATCH 目标状态；ERROR 只由同步结果产生。
     *
     * @param value 原始值
     * @return 状态
     */
    private static ConnectorStatus parseTargetStatus(String value) {
        if ("ACTIVE".equals(value.strip())) {
            return ConnectorStatus.ACTIVE;
        }
        if ("PAUSED".equals(value.strip())) {
            return ConnectorStatus.PAUSED;
        }
        throw bad("status must be ACTIVE or PAUSED");
    }

    /**
     * 读取必填文本。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @param min 最小长度
     * @param max 最大长度
     * @return 去首尾空白值
     */
    private static String requireText(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw bad(field + " is required and must be a string");
        }
        String text = value.asText().strip();
        if (text.length() < min || text.length() > max) {
            throw bad(field + " length must be between " + min + " and " + max);
        }
        return text;
    }

    /**
     * 读取可选文本。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @param max 最大长度
     * @return 文本
     */
    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw bad(field + " must be a string");
        }
        String text = value.asText();
        if (text.length() > max) {
            throw bad(field + " must be at most " + max + " characters");
        }
        return text;
    }

    /**
     * 读取可选布尔。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @param defaultValue 缺省值
     * @return 布尔值
     */
    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw bad(field + " must be a boolean");
        }
        return value.asBoolean();
    }

    /**
     * 读取 endpointId。
     *
     * @param node JSON 节点
     * @return endpointId
     */
    private static String requireEndpointId(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw bad("endpointId is required and must be a string");
        }
        String value = node.asText().strip();
        if (!ENDPOINT_ID_PATTERN.matcher(value).matches()) {
            throw bad("endpointId has invalid format");
        }
        if (value.length() > MAX_ENDPOINT_ID_LENGTH) {
            throw bad("endpointId must be at most " + MAX_ENDPOINT_ID_LENGTH + " characters");
        }
        return value;
    }

    /**
     * 读取可选 UUID。
     *
     * @param node JSON 节点
     * @param field 字段名
     * @return UUID 或 null
     */
    private static UUID optionalUuid(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw bad(field + " must be a UUID string or null");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException exception) {
            throw bad(field + " must be a valid UUID");
        }
    }

    /**
     * 校验 JSON 对象。
     *
     * @param node 节点
     * @param field 字段名
     */
    private static void requireObject(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw bad(field + " must be a JSON object");
        }
    }

    /**
     * 校验未知字段。
     *
     * @param node JSON 对象
     * @param allowed 允许键
     */
    private static void requireAllowedKeys(JsonNode node, Set<String> allowed) {
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw bad("unknown field: " + key);
            }
        }
    }

    /**
     * 构造 400。
     *
     * @param detail 脱敏说明
     * @return 异常
     */
    private static KnowledgeBadRequestException bad(String detail) {
        return new KnowledgeBadRequestException(detail);
    }

    /**
     * 创建命令。
     *
     * @param name 名称
     * @param type 类型
     * @param endpointId 端点 ID
     * @param config 配置
     * @param defaultVisibility 默认可见范围
     * @param defaultDepartmentId 默认部门
     * @param scheduleEnabled 是否参与定时同步
     * @param credential 可选凭据
     */
    public record CreateCommand(
            String name,
            ConnectorType type,
            String endpointId,
            ConnectorConfig config,
            KnowledgeVisibility defaultVisibility,
            UUID defaultDepartmentId,
            boolean scheduleEnabled,
            CredentialInput credential) {}

    /**
     * 更新命令；null 表示保持原值。
     *
     * @param name 名称
     * @param endpointId 端点 ID
     * @param config 配置
     * @param defaultVisibility 默认可见范围
     * @param defaultDepartmentId 默认部门
     * @param departmentProvided 请求是否显式包含 defaultDepartmentId
     * @param scheduleEnabled 调度开关
     * @param status 目标状态
     */
    public record UpdateCommand(
            String name,
            String endpointId,
            ConnectorConfig config,
            KnowledgeVisibility defaultVisibility,
            UUID defaultDepartmentId,
            boolean departmentProvided,
            Boolean scheduleEnabled,
            ConnectorStatus status) {}

    /**
     * 凭据输入。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     */
    public record CredentialInput(String accessKeyId, String secretAccessKey) {}

    /**
     * 可见范围输入。
     *
     * @param visibility 可见范围
     * @param departmentId 部门
     */
    private record VisibilityInput(KnowledgeVisibility visibility, UUID departmentId) {}
}
