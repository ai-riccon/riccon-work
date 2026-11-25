package com.riccon.worker.workflow.approval.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.agent.domain.AgentException;
import com.riccon.worker.mcp.domain.ArgumentDigest;
import com.riccon.worker.mcp.domain.Digests;
import com.riccon.worker.research.service.ApprovalParamsCrypto;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalSealedParams;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import com.riccon.worker.workflow.node.service.WorkflowTemplateRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批工具参数的渲染、脱敏与信封加密（ADR-0024 §4.7，复用 ADR-0017）。
 *
 * <p>审批创建时：
 * <ol>
 *   <li>按服务端封闭占位符渲染 {@code argsTemplate}（只允许运行输入与上游受控字段，不解释表达式）；
 *   <li>递归按键排序得到规范化 JSON，计算 {@code params_sha256}；
 *   <li>生成脱敏预览（≤16 键、单值 ≤200 字符、≤4096 字节），敏感键/凭据形态替换为 redacted；
 *   <li>用租户 DEK（{@code purpose=approval-params}）做 AES-256-GCM 加密，AAD 为
 *       {@code tenantId|approvalId}。
 * </ol>
 *
 * <p>批准后 T07b 只能通过 {@link #openToolParams} 按密文记录解密并复核哈希；密文缺失、key 不可用、
 * 认证失败或哈希不符都必须失败关闭，禁止退回 {@code params_preview} 明文执行。本类不写日志，
 * 不把明文、密文、nonce 或 key 放入异常消息。
 */
@Component
public class WorkflowApprovalParamsService {

    /** 参数模板最大嵌套深度；与发布校验的顶层 16 键共同限制恶意结构。 */
    private static final int MAX_ARGS_DEPTH = 5;

    /** 预览 JSON 字节预算；与 J2 一致，确保 {@code params_preview} 列 ≤4096 字节。 */
    private static final int MAX_PREVIEW_BYTES = 3800;

    /** 预览最多顶层键数量。 */
    private static final int MAX_PREVIEW_KEYS = 16;

    /** 预览单值最大长度。 */
    private static final int MAX_PREVIEW_STRING = 200;

    /** 封闭模板渲染。 */
    private final WorkflowTemplateRenderer renderer;

    /** 审批参数信封加密。 */
    private final ApprovalParamsCrypto paramsCrypto;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建参数服务。
     *
     * @param renderer 封闭模板渲染
     * @param paramsCrypto 审批参数信封加密
     * @param objectMapper JSON 序列化
     */
    public WorkflowApprovalParamsService(
            WorkflowTemplateRenderer renderer, ApprovalParamsCrypto paramsCrypto, ObjectMapper objectMapper) {
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.paramsCrypto = Objects.requireNonNull(paramsCrypto, "paramsCrypto must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 渲染并加密工具参数。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID（AAD 绑定）
     * @param argsTemplate 节点参数模板
     * @param inputs 运行输入
     * @param upstreamOutputs 上游成功节点输出
     * @return 规范化哈希、脱敏预览、密文与 key id
     * @throws WorkflowNodeFailureException 模板非法、参数含敏感键、超限或加密不可用
     */
    public WorkflowApprovalSealedParams sealToolParams(
            TenantId tenantId,
            UUID approvalId,
            JsonNode argsTemplate,
            JsonNode inputs,
            Map<String, WorkflowNodeOutput> upstreamOutputs) {
        Map<String, Object> arguments = renderArguments(argsTemplate, inputs, upstreamOutputs);
        if (ArgumentDigest.hasSensitiveKey(arguments)) {
            // 工具参数不得携带凭据类键；审批不创建，节点失败关闭而不是静默脱敏后执行。
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_TOOL_NOT_AUTHORIZED, "工具参数包含不允许的敏感字段");
        }
        String canonicalJson = canonicalJson(arguments);
        String paramsSha256 = Digests.sha256Hex(canonicalJson);
        Map<String, Object> preview =
                ArgumentDigest.preview(arguments, MAX_PREVIEW_BYTES, MAX_PREVIEW_KEYS, MAX_PREVIEW_STRING);
        try {
            ApprovalParamsCrypto.Sealed sealed = paramsCrypto.seal(tenantId, approvalId, canonicalJson);
            return new WorkflowApprovalSealedParams(paramsSha256, preview, sealed.ciphertext(), sealed.keyId());
        } catch (AgentException exception) {
            // 加密不可用属于运行环境/密钥问题，必须让审批节点失败而不是保存明文或跳过审批。
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "审批参数加密不可用", exception);
        }
    }

    /**
     * 解密并复核工具参数（T07b 执行前调用）。
     *
     * @param tenantId 租户
     * @param approvalId 审批 ID
     * @param keyId 密文 key id
     * @param ciphertext 密文
     * @param expectedSha256 审批行中的参数哈希
     * @return 规范化参数
     * @throws WorkflowNodeFailureException 密文/密钥/哈希不可用（{@code WF_PLAN_DRIFT}）
     */
    public Map<String, Object> openToolParams(
            TenantId tenantId, UUID approvalId, String keyId, byte[] ciphertext, String expectedSha256) {
        try {
            return paramsCrypto.open(tenantId, approvalId, keyId, ciphertext, expectedSha256);
        } catch (AgentException exception) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_PLAN_DRIFT, "审批参数不可重放", exception);
        }
    }

    /**
     * 把参数映射序列化为规范化 JSON（键递归排序）。
     *
     * @param arguments 参数
     * @return 规范化 JSON
     * @throws WorkflowNodeFailureException 序列化失败
     */
    public String canonicalJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(ArgumentDigest.canonical(arguments));
        } catch (JacksonException exception) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INTERNAL, "工具参数无法规范化", exception);
        }
    }

    /**
     * 按封闭占位符渲染参数模板。
     *
     * @param template 参数模板（对象）
     * @param inputs 运行输入
     * @param upstreamOutputs 上游输出
     * @return 渲染后的参数映射
     * @throws WorkflowNodeFailureException 模板不是对象、嵌套过深或占位符非法
     */
    private Map<String, Object> renderArguments(
            JsonNode template, JsonNode inputs, Map<String, WorkflowNodeOutput> upstreamOutputs) {
        if (template == null || !template.isObject()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "审批工具参数模板必须是对象");
        }
        return renderObject(template, inputs, upstreamOutputs, 0);
    }

    /**
     * 递归渲染对象。
     *
     * @param object 对象节点
     * @param inputs 运行输入
     * @param upstreamOutputs 上游输出
     * @param depth 当前深度
     * @return 渲染后的映射
     */
    private Map<String, Object> renderObject(
            JsonNode object, JsonNode inputs, Map<String, WorkflowNodeOutput> upstreamOutputs, int depth) {
        requireDepth(depth);
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (String name : object.propertyNames()) {
            rendered.put(name, renderValue(object.get(name), inputs, upstreamOutputs, depth + 1));
        }
        return rendered;
    }

    /**
     * 递归渲染单个值。
     *
     * @param value 模板值
     * @param inputs 运行输入
     * @param upstreamOutputs 上游输出
     * @param depth 当前深度
     * @return 渲染值
     */
    private Object renderValue(
            JsonNode value, JsonNode inputs, Map<String, WorkflowNodeOutput> upstreamOutputs, int depth) {
        if (value == null || value.isNull()) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "审批工具参数不能包含 null");
        }
        if (value.isTextual()) {
            return renderer.render(value.asString(), inputs, upstreamOutputs);
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isObject()) {
            return renderObject(value, inputs, upstreamOutputs, depth);
        }
        if (value.isArray()) {
            requireDepth(depth);
            List<Object> items = new ArrayList<>(value.size());
            for (JsonNode item : value) {
                items.add(renderValue(item, inputs, upstreamOutputs, depth + 1));
            }
            return items;
        }
        throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "审批工具参数包含不支持的值类型");
    }

    /**
     * 校验嵌套深度。
     *
     * @param depth 当前深度
     */
    private static void requireDepth(int depth) {
        if (depth > MAX_ARGS_DEPTH) {
            throw new WorkflowNodeFailureException(WorkflowErrorCode.WF_INVALID_GRAPH, "审批工具参数嵌套过深");
        }
    }
}
