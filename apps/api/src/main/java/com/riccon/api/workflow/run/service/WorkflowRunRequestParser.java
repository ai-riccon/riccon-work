package com.riccon.api.workflow.run.service;

import com.riccon.api.workflow.domain.WorkflowGraphNode;
import com.riccon.api.workflow.run.domain.WorkflowRunCreateCommand;
import com.riccon.api.workflow.run.domain.WorkflowRunRetryCommand;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行创建/重试请求体解析与白名单校验。
 *
 * <p>使用 {@link JsonNode} 而不是直接绑定 DTO，确保契约 {@code additionalProperties: false} 生效：
 * 未知字段、错误类型统一返回 400 {@code RICCON-WF-400}。运行输入额外执行服务端边界校验：
 * 最多 16 个顶层键、键名符合图节点 ID 同款 pattern（模板占位符 {@code {{input.<field>}}} 的字段语法）、
 * 规范化后 ≤64 KiB（ADR-0024 §3.3.3/§4.5）。
 *
 * <p>本类不做范围/状态校验；这些需要数据库资源级判定，由 {@link WorkflowRunService} 在同事务内完成。
 */
@Component
public class WorkflowRunRequestParser {

    /** 创建运行请求字段白名单。 */
    private static final Set<String> CREATE_FIELDS = Set.of("versionId", "inputs");

    /** 重试请求字段白名单。 */
    private static final Set<String> RETRY_FIELDS = Set.of("nodeId");

    /** 运行输入最大序列化字节数（64 KiB），与契约和 ADR 一致。 */
    static final int MAX_INPUT_BYTES = 64 * 1024;

    /** 运行输入最大顶层键数，与契约 {@code maxProperties: 16} 一致。 */
    static final int MAX_INPUT_KEYS = 16;

    /** Idempotency-Key 最小长度。 */
    static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** Idempotency-Key 最大长度。 */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** JSON 序列化器，用于计算输入字节数并生成稳定文本。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建请求解析器。
     *
     * @param objectMapper JSON 序列化器
     */
    public WorkflowRunRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析创建运行请求。
     *
     * @param body 请求体
     * @return 创建命令
     * @throws WorkflowRunBadRequestException 请求体、字段或输入边界非法
     */
    public WorkflowRunCreateCommand parseCreate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new WorkflowRunBadRequestException("request body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!CREATE_FIELDS.contains(name)) {
                throw new WorkflowRunBadRequestException("unknown field: " + name);
            }
        }
        UUID versionId = optionalUuid(body, "versionId");
        JsonNode inputs = body.get("inputs");
        if (inputs == null || !inputs.isObject()) {
            throw new WorkflowRunBadRequestException("inputs must be a JSON object");
        }
        if (inputs.propertyNames().size() > MAX_INPUT_KEYS) {
            throw new WorkflowRunBadRequestException("inputs must not exceed " + MAX_INPUT_KEYS + " keys");
        }
        for (String key : inputs.propertyNames()) {
            if (!WorkflowGraphNode.ID_PATTERN.matcher(key).matches()) {
                throw new WorkflowRunBadRequestException("input key must match " + WorkflowGraphNode.ID_PATTERN);
            }
        }
        String inputsJson;
        try {
            inputsJson = objectMapper.writeValueAsString(inputs);
        } catch (JacksonException exception) {
            throw new WorkflowRunBadRequestException("inputs must be valid JSON");
        }
        if (inputsJson.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new WorkflowRunBadRequestException("inputs must not exceed " + MAX_INPUT_BYTES + " bytes");
        }
        return new WorkflowRunCreateCommand(versionId, inputsJson);
    }

    /**
     * 解析重试请求。
     *
     * @param body 请求体
     * @return 重试命令
     * @throws WorkflowRunBadRequestException 请求体、字段或节点 key 非法
     */
    public WorkflowRunRetryCommand parseRetry(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new WorkflowRunBadRequestException("request body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!RETRY_FIELDS.contains(name)) {
                throw new WorkflowRunBadRequestException("unknown field: " + name);
            }
        }
        JsonNode nodeId = body.get("nodeId");
        if (nodeId == null
                || !nodeId.isTextual()
                || !WorkflowGraphNode.ID_PATTERN.matcher(nodeId.asString()).matches()) {
            throw new WorkflowRunBadRequestException("nodeId must match " + WorkflowGraphNode.ID_PATTERN);
        }
        return new WorkflowRunRetryCommand(nodeId.asString());
    }

    /**
     * 校验必填幂等键的长度与字符集。
     *
     * <p>幂等键写入 {@code VARCHAR(128) ascii_bin}；非 ASCII 字符会让数据库拒绝并变成 500，
     * 因此在边界返回 400 而不是依赖存储层报错。
     *
     * @param idempotencyKey 请求头
     * @throws WorkflowRunBadRequestException 缺失、长度越界或含非 ASCII 字符
     */
    public static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new WorkflowRunBadRequestException("Idempotency-Key must be between " + MIN_IDEMPOTENCY_KEY_LENGTH
                    + " and " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        for (int index = 0; index < idempotencyKey.length(); index++) {
            char value = idempotencyKey.charAt(index);
            if (value < 0x21 || value > 0x7e) {
                throw new WorkflowRunBadRequestException(
                        "Idempotency-Key must contain printable ASCII characters only");
            }
        }
    }

    /**
     * 读取可选 UUID 字段。
     *
     * @param object 请求对象
     * @param field 字段名
     * @return UUID 或 {@code null}
     */
    private static UUID optionalUuid(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new WorkflowRunBadRequestException(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new WorkflowRunBadRequestException(field + " must be a UUID string");
        }
    }
}
