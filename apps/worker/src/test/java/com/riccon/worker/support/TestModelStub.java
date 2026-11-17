package com.riccon.worker.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本地 OpenAI 兼容模型桩。
 *
 * <p>只监听 127.0.0.1 随机端口，按请求内容返回确定性 SSE；测试不访问外网、不 sleep。
 * 支持 429/5xx、流中错误与无内容等失败模式，用于验证超时、重试与失败事件。
 */
public final class TestModelStub implements AutoCloseable {

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP 服务。 */
    private final HttpServer server;

    /** 收到的请求体。 */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    /** 收到的 Authorization 头。 */
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

    /** 强制返回的状态码；0 表示正常。 */
    private final AtomicInteger forcedStatus = new AtomicInteger(0);

    /** 剩余需要返回 429 的次数。 */
    private final AtomicInteger rateLimitCount = new AtomicInteger(0);

    /** 是否在首个增量后发送错误载荷。 */
    private volatile boolean errorAfterChunk;

    /** 是否返回空回答。 */
    private volatile boolean emptyAnswer;

    /** 是否返回非法 JSON。 */
    private volatile boolean malformedResponse;

    /** 是否省略 [DONE] 结束帧。 */
    private volatile boolean noDone;

    /** 是否返回空流。 */
    private volatile boolean emptyStream;

    /**
     * 启动桩。
     */
    public TestModelStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start model stub", ex);
        }
    }

    /**
     * 返回端点 URL。
     *
     * @return URL
     */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 重置计数与失败模式。 */
    public void reset() {
        requests.clear();
        authorizations.clear();
        forcedStatus.set(0);
        rateLimitCount.set(0);
        errorAfterChunk = false;
        emptyAnswer = false;
        malformedResponse = false;
        noDone = false;
        emptyStream = false;
    }

    /**
     * 设置固定状态码。
     *
     * @param status 状态
     */
    public void forceStatus(int status) {
        forcedStatus.set(status);
    }

    /**
     * 设置接下来 N 次返回 429。
     *
     * @param count 次数
     */
    public void rateLimitNext(int count) {
        rateLimitCount.set(count);
    }

    /**
     * 首个增量后发送错误。
     */
    public void errorAfterChunk() {
        errorAfterChunk = true;
    }

    /** 返回空回答。 */
    public void emptyAnswer() {
        emptyAnswer = true;
    }

    /** 返回非法 JSON。 */
    public void malformedResponse() {
        malformedResponse = true;
    }

    /** 省略 [DONE]。 */
    public void noDone() {
        noDone = true;
    }

    /** 返回空流。 */
    public void emptyStream() {
        emptyStream = true;
    }

    /**
     * 返回请求数量。
     *
     * @return 数量
     */
    public int requestCount() {
        return requests.size();
    }

    /**
     * 返回最近一次 Authorization。
     *
     * @return 头值
     */
    public String lastAuthorization() {
        synchronized (authorizations) {
            return authorizations.isEmpty() ? null : authorizations.get(authorizations.size() - 1);
        }
    }

    /**
     * 返回最近一次请求体。
     *
     * @return JSON
     */
    public String lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    /**
     * 处理请求。
     *
     * @param exchange HTTP 交换
     * @throws IOException 写入失败
     */
    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization == null ? "" : authorization);
        if (rateLimitCount.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
            return;
        }
        int status = forcedStatus.get();
        if (status > 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            JsonNode root = MAPPER.readTree(body);
            Plan plan = plan(root);
            for (String frame : plan.frames()) {
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }
        exchange.close();
    }

    /**
     * 根据请求内容选择响应计划。
     *
     * @param root 请求
     * @return 计划
     */
    private Plan plan(JsonNode root) {
        if (emptyStream) {
            return new Plan(List.of());
        }
        if (malformedResponse) {
            return new Plan(List.of("data: not-json\n\n"));
        }
        JsonNode messages = root.path("messages");
        String lastRole = messages.isArray() && !messages.isEmpty()
                ? messages.get(messages.size() - 1).path("role").asText("")
                : "";
        boolean hasEcho = toolNames(root).contains("echo");
        String serialized = root.toString();
        if ("tool".equals(lastRole)) {
            return textPlan();
        }
        if (hasEcho && serialized.contains("调研主题")) {
            return toolPlan("echo", "{\"text\":\"market-data\"}");
        }
        if (serialized.contains("CALL_KNOWLEDGE")) {
            return toolPlan("knowledge_search", "{\"query\":\"资料\"}");
        }
        if (serialized.contains("CALL_MCP")) {
            return toolPlan("echo", "{\"text\":\"market-data\"}");
        }
        return textPlan();
    }

    /**
     * 读取工具名。
     *
     * @param root 请求
     * @return 工具名
     */
    private static List<String> toolNames(JsonNode root) {
        List<String> names = new ArrayList<>();
        JsonNode tools = root.path("tools");
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                names.add(tool.path("function").path("name").asText(""));
            }
        }
        return names;
    }

    /**
     * 构造文本响应。
     *
     * @return 计划
     */
    private Plan textPlan() {
        if (emptyAnswer) {
            return new Plan(
                    List.of(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"), "data: [DONE]\n\n"));
        }
        List<String> frames = new java.util.ArrayList<>(List.of(
                frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"),
                frame("{\"choices\":[{\"delta\":{\"content\":\"根据资料，\"}}]}"),
                frame("{\"choices\":[{\"delta\":{\"content\":\"这是回答 [1]。\"}}]}")));
        if (errorAfterChunk) {
            frames.add(frame("{\"error\":{\"message\":\"upstream failed\"}}"));
            return new Plan(frames);
        }
        frames.add(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        frames.add(frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}"));
        if (!noDone) {
            frames.add("data: [DONE]\n\n");
        }
        return new Plan(frames);
    }

    /**
     * 构造工具调用响应。
     *
     * @param name 工具名
     * @param arguments 参数 JSON
     * @return 计划
     */
    private Plan toolPlan(String name, String arguments) {
        List<String> frames = new ArrayList<>();
        frames.add(frame("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"\"}}]}}]}"));
        frames.add(frame("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," + "\"function\":{\"arguments\":"
                + quoteJson(arguments) + "}}]}}]}"));
        frames.add(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"));
        if (errorAfterChunk) {
            frames.add(frame("{\"error\":{\"message\":\"upstream failed\"}}"));
        } else {
            frames.add("data: [DONE]\n\n");
        }
        return new Plan(frames);
    }

    /**
     * 构造 SSE data 帧。
     *
     * @param json 载荷
     * @return 帧
     */
    private static String frame(String json) {
        return "data: " + json + "\n\n";
    }

    /**
     * 把参数 JSON 转义为 JSON 字符串字面量。
     *
     * @param value 原始值
     * @return JSON 字符串
     */
    private static String quoteJson(String value) {
        return MAPPER.writeValueAsString(value);
    }

    /** 关闭服务。 */
    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 响应计划。
     *
     * @param frames SSE 帧
     */
    private record Plan(List<String> frames) {}
}
