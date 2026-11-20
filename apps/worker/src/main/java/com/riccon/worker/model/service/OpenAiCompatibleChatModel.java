package com.riccon.worker.model.service;

import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ResolvedModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI 兼容流式 ChatModel。
 *
 * <p>使用 JDK HttpClient + Jackson 实现 agentscope {@link Model} 协议，不引入额外模型 SDK。
 * 请求只携带目录中的模型标识、消息与工具 schema；API key 只放在 Authorization 头中，
 * 不进入日志、异常或元数据。可重试错误仅在尚未输出任何增量时重试，避免同一回答重复。
 */
public class OpenAiCompatibleChatModel implements Model {

    /** 日志记录器；只记录模型标识与错误码。 */
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);

    /** 流结束标记。 */
    private static final String DONE = "[DONE]";

    /** 本次调用使用的模型与凭据。 */
    private final ResolvedModel resolvedModel;

    /** 网关配置。 */
    private final ModelGatewayProperties properties;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端。 */
    private final HttpClient httpClient;

    /**
     * 创建模型客户端。
     *
     * @param resolvedModel 模型与凭据
     * @param properties 网关配置
     * @param objectMapper JSON 序列化器
     */
    public OpenAiCompatibleChatModel(
            ResolvedModel resolvedModel, ModelGatewayProperties properties, ObjectMapper objectMapper) {
        this.resolvedModel = Objects.requireNonNull(resolvedModel, "resolvedModel must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 返回上游模型标识。
     *
     * @return 模型 key
     */
    @Override
    public String getModelName() {
        return resolvedModel.model().modelKey();
    }

    /**
     * 发起流式调用。
     *
     * <p>HTTP 读取是阻塞的，必须在独立线程上执行：若在订阅线程上直接跑读循环，任何阻塞式消费方
     * （例如 {@code Flux.toIterable().iterator()} 会在 {@code subscribe} 内同步执行生成器）都要等
     * 整个模型流结束才能拿到第一个元素，表现为“首字被缓冲到流结束”。{@code subscribeOn}
     * 把阻塞读放到 {@code boundedElastic}，增量即可逐条下发；取消时同时关闭响应体，避免
     * 阻塞的 {@code readLine} 一直占住线程。
     *
     * @param messages 对话消息
     * @param tools 可用工具 schema
     * @param options 生成参数
     * @return 响应增量序列
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.<ChatResponse>create(
                        sink -> streamInto(sink, messages, tools, options), FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在当前线程读取上游 SSE 并写入 Reactor 订阅者。
     *
     * <p>整个方法只在尚未 emit 任何响应前允许重试；一旦输出增量，后续失败直接终止，
     * 由上层把已生成内容保留并发送失败事件。
     *
     * @param sink 响应订阅者
     * @param messages 消息
     * @param tools 工具
     * @param options 生成参数
     */
    private void streamInto(
            FluxSink<ChatResponse> sink, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String body = serializeRequest(messages, tools, options);
        // 取消或下游释放时关闭当前响应体：只靠 sink.isCancelled() 检查要等下一行数据，
        // 上游长时间无增量时取消会被拖到空闲超时。
        AtomicReference<InputStream> activeStream = new AtomicReference<>();
        sink.onCancel(() -> closeQuietly(activeStream.get()));
        sink.onDispose(() -> closeQuietly(activeStream.get()));
        try {
            HttpResponse<InputStream> response = send(body);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                activeStream.set(response.body());
                parseEventStream(response.body(), sink);
                return;
            }
            ModelException failure = translateStatus(response.statusCode());
            closeQuietly(response.body());
            LOG.warn(
                    "model.request.failed modelId={} status={} errorCode={}",
                    resolvedModel.model().id(),
                    response.statusCode(),
                    failure.errorCode());
            sink.error(failure);
        } catch (ModelException ex) {
            if (!sink.isCancelled()) {
                sink.error(ex);
            }
        } catch (RuntimeException ex) {
            if (!sink.isCancelled()) {
                sink.error(new ModelException(
                        ModelException.INVALID_RESPONSE,
                        false,
                        "unexpected model client failure",
                        ModelFailureKind.INVALID_RESPONSE,
                        ex));
            }
        }
    }

    /**
     * 发送请求并返回流式响应。
     *
     * @param body 请求 JSON
     * @return HTTP 响应
     */
    private HttpResponse<InputStream> send(String body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + resolvedModel.apiKey().reveal())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(
                    ModelException.TIMEOUT, false, "model request was interrupted", ModelFailureKind.TIMEOUT, ex);
        } catch (IOException ex) {
            throw new ModelException(
                    ModelException.UNAVAILABLE, true, "model endpoint is unreachable", ModelFailureKind.TRANSPORT, ex);
        }
    }

    /**
     * 拼装 chat completions 端点；基础 URL 只来自目录，不接受请求方输入。
     *
     * @return 完整 URI
     */
    private URI endpoint() {
        String base = resolvedModel.baseUrl().toString();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + "/chat/completions");
    }

    /**
     * 构造 OpenAI 兼容请求体。
     *
     * @param messages 消息
     * @param tools 工具
     * @param options 生成参数
     * @return JSON
     */
    private String serializeRequest(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", resolvedModel.model().modelKey());
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        ArrayNode messageArray = root.putArray("messages");
        for (Msg message : messages) {
            appendMessage(messageArray, message);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = root.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode function =
                        toolArray.addObject().put("type", "function").putObject("function");
                function.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    function.put("description", tool.getDescription());
                }
                function.set(
                        "parameters",
                        objectMapper.valueToTree(
                                tool.getParameters() == null ? Map.of("type", "object") : tool.getParameters()));
            }
        }
        if (options != null && options.getTemperature() != null) {
            root.put("temperature", options.getTemperature());
        }
        if (options != null && options.getTopP() != null) {
            root.put("top_p", options.getTopP());
        }
        int maxTokens = options != null && options.getMaxTokens() != null
                ? options.getMaxTokens()
                : resolvedModel.maxOutputTokens();
        root.put("max_tokens", maxTokens);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException ex) {
            throw new ModelException(ModelException.CONFIGURATION, false, "failed to build model request", ex);
        }
    }

    /**
     * 将 agentscope 消息转换为 OpenAI 消息。
     *
     * @param target 目标数组
     * @param message agentscope 消息
     */
    private void appendMessage(ArrayNode target, Msg message) {
        List<ToolUseBlock> toolCalls = message.getContentBlocks(ToolUseBlock.class);
        List<ToolResultBlock> toolResults = message.getContentBlocks(ToolResultBlock.class);
        if (!toolResults.isEmpty()) {
            for (ToolResultBlock result : toolResults) {
                ObjectNode node = target.addObject();
                node.put("role", "tool");
                node.put("tool_call_id", result.getId());
                node.put("content", joinText(result.getOutput()));
            }
            return;
        }
        ObjectNode node = target.addObject();
        node.put(
                "role",
                switch (message.getRole()) {
                    case SYSTEM -> "system";
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    case TOOL -> "tool";
                });
        node.put("content", message.getTextContent());
        if (!toolCalls.isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolUseBlock call : toolCalls) {
                ObjectNode item = calls.addObject();
                item.put("id", call.getId());
                item.put("type", "function");
                ObjectNode function = item.putObject("function");
                function.put("name", call.getName());
                function.put("arguments", arguments(call));
            }
        }
    }

    /**
     * 返回工具调用参数的 JSON 字符串；流式聚合块可能只有原始 content。
     *
     * @param call 工具调用
     * @return 参数 JSON
     */
    private String arguments(ToolUseBlock call) {
        if (call.getInput() != null && !call.getInput().isEmpty()) {
            try {
                return objectMapper.writeValueAsString(call.getInput());
            } catch (JacksonException ex) {
                throw new ModelException(ModelException.CONFIGURATION, false, "failed to serialize tool arguments", ex);
            }
        }
        return call.getContent() == null || call.getContent().isBlank() ? "{}" : call.getContent();
    }

    /**
     * 拼接工具结果中的文本块。
     *
     * @param blocks 内容块
     * @return 文本
     */
    private static String joinText(List<ContentBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                builder.append(text.getText());
            }
        }
        return builder.toString();
    }

    /**
     * 解析上游 SSE。
     *
     * @param stream 响应正文
     * @param sink 订阅者
     */
    private void parseEventStream(InputStream stream, FluxSink<ChatResponse> sink) {
        AtomicBoolean finished = new AtomicBoolean(false);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            Map<String, PendingToolCall> pendingToolCalls = new LinkedHashMap<>();
            String line;
            while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty() && handlePayload(data.toString(), sink, finished, pendingToolCalls)) {
                        return;
                    }
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
            }
            // 部分兼容端点最后一行没有空行分隔；按已累积数据再处理一次。
            if (!data.isEmpty() && handlePayload(data.toString(), sink, finished, pendingToolCalls)) {
                return;
            }
            if (!pendingToolCalls.isEmpty() && !sink.isCancelled()) {
                List<ContentBlock> toolBlocks = new ArrayList<>();
                appendToolCalls(toolBlocks, pendingToolCalls);
                sink.next(ChatResponse.builder()
                        .finishReason("tool_calls")
                        .content(toolBlocks)
                        .build());
                finished.set(true);
            }
            if (!finished.get() && !sink.isCancelled()) {
                sink.next(ChatResponse.builder()
                        .finishReason("stop")
                        .content(List.of())
                        .build());
                finished.set(true);
            }
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (IOException ex) {
            if (!sink.isCancelled()) {
                // 流中断按传输层失败上报；首字前由降级状态机决定是否切换，首字后只保留已输出内容。
                sink.error(new ModelException(
                        ModelException.UNAVAILABLE,
                        false,
                        "model stream terminated unexpectedly",
                        ModelFailureKind.TRANSPORT,
                        ex));
            }
        }
    }

    /**
     * 处理单个 SSE data 载荷。
     *
     * @param payload data 内容
     * @param sink 订阅者
     * @param finished 是否已输出终止块
     * @param pendingToolCalls 按工具调用聚合的参数分片
     * @return 是否收到 [DONE]
     */
    private boolean handlePayload(
            String payload,
            FluxSink<ChatResponse> sink,
            AtomicBoolean finished,
            Map<String, PendingToolCall> pendingToolCalls) {
        if (DONE.equals(payload.strip())) {
            sink.complete();
            return true;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            sink.error(new ModelException(
                    ModelException.INVALID_RESPONSE,
                    false,
                    "model stream returned invalid JSON",
                    ModelFailureKind.INVALID_RESPONSE,
                    ex));
            return true;
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            sink.error(new ModelException(
                    ModelException.INVALID_RESPONSE,
                    false,
                    "model endpoint returned an error payload",
                    ModelFailureKind.INVALID_RESPONSE));
            return true;
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            List<ContentBlock> blocks = new ArrayList<>();
            JsonNode content = delta.get("content");
            if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                blocks.add(TextBlock.builder().text(content.asText()).build());
            }
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    accumulateToolCall(pendingToolCalls, toolCall);
                }
            }
            ChatResponse.Builder response = ChatResponse.builder().content(blocks);
            JsonNode finishReason = choice.get("finish_reason");
            if (finishReason != null
                    && finishReason.isTextual()
                    && !finishReason.asText().isBlank()) {
                response.finishReason(finishReason.asText());
                finished.set(true);
                // 工具参数可能分多个 delta 到达，只有终止块才把聚合后的完整 input 交给 AgentScope；
                // 否则审批阶段会拿到空参数，导致“审批 A 执行 B”。
                appendToolCalls(blocks, pendingToolCalls);
            }
            ChatUsage usage = usage(root.get("usage"));
            if (usage != null) {
                response.usage(usage);
            }
            if (!blocks.isEmpty() || finishReason != null || usage != null) {
                sink.next(response.build());
            }
        } else {
            ChatUsage usage = usage(root.get("usage"));
            if (usage != null) {
                sink.next(ChatResponse.builder().content(List.of()).usage(usage).build());
            }
        }
        return false;
    }

    /**
     * 聚合工具调用分片；同一 id 的 arguments 按到达顺序拼接。
     *
     * @param pending 聚合状态
     * @param toolCall 当前 delta 中的工具调用
     */
    private static void accumulateToolCall(Map<String, PendingToolCall> pending, JsonNode toolCall) {
        JsonNode idNode = toolCall.get("id");
        String id = idNode != null && idNode.isTextual() && !idNode.asText().isEmpty() ? idNode.asText() : null;
        // OpenAI 兼容端点只在首个 delta 带 id/name，后续 delta 只带 index；以 index 聚合才能把
        // 参数拼到同一个工具调用上，避免“id 一条、index 一条”导致审批参数为空。
        JsonNode indexNode = toolCall.get("index");
        String key = indexNode != null && indexNode.isNumber()
                ? "index:" + indexNode.asInt()
                : (id == null ? "pending:0" : id);
        PendingToolCall call = pending.computeIfAbsent(key, ignored -> new PendingToolCall());
        if (id != null) {
            call.id = id;
        }
        JsonNode function = toolCall.path("function");
        JsonNode name = function.get("name");
        if (name != null && name.isTextual() && !name.asText().isEmpty()) {
            call.name = name.asText();
        }
        JsonNode arguments = function.get("arguments");
        if (arguments != null && arguments.isTextual()) {
            call.arguments.append(arguments.asText());
        }
    }

    /**
     * 把聚合后的工具调用写入响应块，并尽量解析完整 arguments 为 input。
     *
     * @param blocks 当前响应块
     * @param pending 聚合状态；写入后清空
     */
    private void appendToolCalls(List<ContentBlock> blocks, Map<String, PendingToolCall> pending) {
        if (pending.isEmpty()) {
            return;
        }
        for (PendingToolCall call : pending.values()) {
            ToolUseBlock.Builder builder = ToolUseBlock.builder();
            if (call.id != null) {
                builder.id(call.id);
            }
            if (call.name != null) {
                builder.name(call.name);
            }
            String raw = call.arguments.toString();
            builder.content(raw);
            if (!raw.isBlank()) {
                try {
                    Map<String, Object> parsed =
                            objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
                    if (parsed != null) {
                        builder.input(parsed);
                    }
                } catch (JacksonException ex) {
                    // 模型返回非法参数时不在这里中断流；审批阶段会拒绝空/非法参数并失败关闭。
                }
            }
            blocks.add(builder.build());
        }
        pending.clear();
    }

    /**
     * 工具调用分片聚合状态。
     */
    private static final class PendingToolCall {

        /** 调用 ID。 */
        private String id;

        /** 工具名。 */
        private String name;

        /** 参数 JSON 分片。 */
        private final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 解析 usage；缺失或字段非法时返回空。
     *
     * @param node usage 节点
     * @return 用量或 null
     */
    private ChatUsage usage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        int prompt = node.path("prompt_tokens").asInt(0);
        int completion = node.path("completion_tokens").asInt(0);
        if (prompt == 0 && completion == 0) {
            return null;
        }
        return ChatUsage.builder().inputTokens(prompt).outputTokens(completion).build();
    }

    /**
     * 将 HTTP 状态映射为稳定错误。
     *
     * @param status 状态码
     * @return 模型错误
     */
    private static ModelException translateStatus(int status) {
        return switch (status) {
            case 401 ->
                new ModelException(
                        ModelException.UNAUTHORIZED,
                        false,
                        "model endpoint rejected credentials",
                        ModelFailureKind.UNAUTHORIZED);
            case 403 ->
                new ModelException(
                        ModelException.FORBIDDEN, false, "model endpoint denied access", ModelFailureKind.FORBIDDEN);
            case 408 ->
                new ModelException(ModelException.TIMEOUT, true, "model endpoint timed out", ModelFailureKind.TIMEOUT);
            case 429 ->
                new ModelException(
                        ModelException.RATE_LIMITED,
                        true,
                        "model endpoint rate limited",
                        ModelFailureKind.RATE_LIMITED);
            default ->
                status >= 500
                        ? new ModelException(
                                ModelException.UNAVAILABLE,
                                true,
                                "model endpoint is unavailable",
                                ModelFailureKind.SERVER_ERROR)
                        : new ModelException(
                                ModelException.INVALID_RESPONSE,
                                false,
                                "model endpoint rejected request",
                                ModelFailureKind.INVALID_RESPONSE);
        };
    }

    /**
     * 关闭失败响应正文，避免连接泄漏。
     *
     * @param stream 响应正文
     */
    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ex) {
            // 关闭失败不影响错误映射；这里不记录日志，避免噪音。
        }
    }
}
