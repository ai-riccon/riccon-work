package com.riccon.worker.model.service;

import com.riccon.worker.config.ModelGatewayProperties;
import com.riccon.worker.model.domain.ModelException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.core.JacksonException;
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
     * @param messages 对话消息
     * @param tools 可用工具 schema
     * @param options 生成参数
     * @return 响应增量序列
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.create(sink -> streamInto(sink, messages, tools, options));
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
        int attempts = Math.max(1, properties.maxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<InputStream> response = send(body);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    parseEventStream(response.body(), sink);
                    return;
                }
                ModelException failure = translateStatus(response.statusCode());
                closeQuietly(response.body());
                if (!failure.retryable() || attempt == attempts) {
                    sink.error(failure);
                    return;
                }
                LOG.warn(
                        "model.request.retry modelId={} status={} attempt={} errorCode={}",
                        resolvedModel.model().id(),
                        response.statusCode(),
                        attempt,
                        failure.errorCode());
                sleepBackoff(attempt);
            } catch (ModelException ex) {
                if (!ex.retryable() || attempt == attempts) {
                    sink.error(ex);
                    return;
                }
                LOG.warn(
                        "model.request.retry modelId={} attempt={} errorCode={}",
                        resolvedModel.model().id(),
                        attempt,
                        ex.errorCode());
                sleepBackoff(attempt);
            } catch (RuntimeException ex) {
                sink.error(new ModelException(
                        ModelException.INVALID_RESPONSE, false, "unexpected model client failure", ex));
                return;
            }
        }
        sink.error(new ModelException(ModelException.UNAVAILABLE, true, "model request exhausted retries"));
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
            throw new ModelException(ModelException.TIMEOUT, false, "model request was interrupted", ex);
        } catch (IOException ex) {
            throw new ModelException(ModelException.UNAVAILABLE, true, "model endpoint is unreachable", ex);
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
            String line;
            while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty() && handlePayload(data.toString(), sink, finished)) {
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
            if (!data.isEmpty() && handlePayload(data.toString(), sink, finished)) {
                return;
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
                sink.error(new ModelException(
                        ModelException.UNAVAILABLE, false, "model stream terminated unexpectedly", ex));
            }
        }
    }

    /**
     * 处理单个 SSE data 载荷。
     *
     * @param payload data 内容
     * @param sink 订阅者
     * @param finished 是否已输出终止块
     * @return 是否收到 [DONE]
     */
    private boolean handlePayload(String payload, FluxSink<ChatResponse> sink, AtomicBoolean finished) {
        if (DONE.equals(payload.strip())) {
            sink.complete();
            return true;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            sink.error(new ModelException(
                    ModelException.INVALID_RESPONSE, false, "model stream returned invalid JSON", ex));
            return true;
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            sink.error(new ModelException(
                    ModelException.INVALID_RESPONSE, false, "model endpoint returned an error payload"));
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
                    ToolUseBlock.Builder builder = ToolUseBlock.builder();
                    JsonNode id = toolCall.get("id");
                    if (id != null && id.isTextual() && !id.asText().isEmpty()) {
                        builder.id(id.asText());
                    }
                    JsonNode function = toolCall.path("function");
                    JsonNode name = function.get("name");
                    if (name != null && name.isTextual() && !name.asText().isEmpty()) {
                        builder.name(name.asText());
                    }
                    JsonNode arguments = function.get("arguments");
                    if (arguments != null && arguments.isTextual()) {
                        builder.content(arguments.asText());
                    }
                    blocks.add(builder.build());
                }
            }
            ChatResponse.Builder response = ChatResponse.builder().content(blocks);
            JsonNode finishReason = choice.get("finish_reason");
            if (finishReason != null
                    && finishReason.isTextual()
                    && !finishReason.asText().isBlank()) {
                response.finishReason(finishReason.asText());
                finished.set(true);
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
            case 401 -> new ModelException(ModelException.UNAUTHORIZED, false, "model endpoint rejected credentials");
            case 403 -> new ModelException(ModelException.FORBIDDEN, false, "model endpoint denied access");
            case 408 -> new ModelException(ModelException.TIMEOUT, true, "model endpoint timed out");
            case 429 -> new ModelException(ModelException.RATE_LIMITED, true, "model endpoint rate limited");
            default ->
                status >= 500
                        ? new ModelException(ModelException.UNAVAILABLE, true, "model endpoint is unavailable")
                        : new ModelException(ModelException.INVALID_RESPONSE, false, "model endpoint rejected request");
        };
    }

    /**
     * 按配置退避；测试使用 0ms 退避，不依赖真实时间。
     *
     * @param attempt 当前尝试号
     */
    private void sleepBackoff(int attempt) {
        int index = Math.min(attempt - 1, properties.retryDelays().size() - 1);
        Duration delay = properties.retryDelays().get(index);
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.TIMEOUT, false, "model retry backoff interrupted", ex);
        }
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
