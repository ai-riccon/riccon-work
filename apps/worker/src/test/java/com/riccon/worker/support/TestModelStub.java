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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    /** 是否在首个 content 帧后暂停，用于证明下游逐事件 flush。 */
    private volatile boolean holdAfterFirstContent;

    /** 时间分片：首个 content 帧之前的延迟，毫秒；0 表示未启用分片。 */
    private volatile long firstDelayMs;

    /** 时间分片：相邻帧之间的间隔，毫秒。 */
    private volatile long frameIntervalMs;

    /** 时间分片：content 帧数量。 */
    private volatile int sliceCount;

    /** 释放暂停的闸门；reset 时立即放开，避免测试之间互相阻塞。 */
    private volatile CountDownLatch contentGate = new CountDownLatch(0);

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
        holdAfterFirstContent = false;
        firstDelayMs = 0;
        frameIntervalMs = 0;
        sliceCount = 0;
        contentGate.countDown();
        contentGate = new CountDownLatch(0);
    }

    /**
     * 启用时间分片输出：模拟真实模型的逐字增量。
     *
     * <p>桩在首个 content 帧之前等待 {@code firstDelayMs}，之后每帧等待 {@code intervalMs}。
     * 调用方据此判断“首字是否被缓冲到流结束”：若链路把增量攒到结束，首字时间会接近
     * {@code firstDelayMs + (sliceCount - 1) * intervalMs}。
     *
     * @param firstDelayMs 首个 content 帧之前的延迟，毫秒
     * @param intervalMs 相邻 content 帧的间隔，毫秒
     * @param sliceCount content 帧数量，必须为正
     */
    public void timeSliced(int firstDelayMs, int intervalMs, int sliceCount) {
        if (firstDelayMs < 0 || intervalMs <= 0 || sliceCount < 1) {
            throw new IllegalArgumentException(
                    "time sliced pacing requires non-negative first delay, positive interval");
        }
        this.firstDelayMs = firstDelayMs;
        this.frameIntervalMs = intervalMs;
        this.sliceCount = sliceCount;
    }

    /**
     * 在首个 content 帧后暂停输出，直到 {@link #releaseContent()} 被调用。
     *
     * <p>用于验证 SSE 增量在模型结束前就被下游读到；若链路缓冲到流结束，测试会在读取
     * 首个 delta 时超时，而不是靠“最终 body 完整”误判为通过。
     *
     * <p><b>首响应闸门语义（M22/TASK-0152，承接 M21 F-001）</b>：调用方用“首个
     * {@code ChatResponse}”作为首段内容的信号，前提是客户端抑制 role-only/空帧。
     * {@code OpenAiCompatibleChatModel.handlePayload} 当前只在 content/finish/usage 存在时
     * {@code sink.next}，因此 role-only 帧不会成为首个响应。若客户端将来改为逐帧输出空
     * role/finish 响应，闸门会提前释放，“首字后失败”用例将失去确定性；此时必须改成显式
     * content 回调或同步更新 {@code OpenAiCompatibleChatModelStreamingTest} 的首响应断言，
     * 不允许放宽闸门断言。
     */
    public void holdAfterFirstContent() {
        holdAfterFirstContent = true;
        contentGate = new CountDownLatch(1);
    }

    /** 释放 {@link #holdAfterFirstContent()} 暂停的输出。 */
    public void releaseContent() {
        contentGate.countDown();
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
            boolean held = false;
            int index = 0;
            for (String frame : plan.frames()) {
                // 帧索引 0 是 role 帧，1 是首个 content 帧；其余帧按间隔推进，模拟真实逐字流。
                if (index == 1) {
                    pause(plan.firstDelayMs());
                } else if (index > 1) {
                    pause(plan.frameIntervalMs());
                }
                index++;
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (!held && holdAfterFirstContent && frame.contains("\"content\"")) {
                    held = true;
                    // 有界等待：测试忘记释放时让上游失败，而不是永久占用线程池。
                    try {
                        if (!contentGate.await(15, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        exchange.close();
    }

    /**
     * 按毫秒暂停；0 表示不等待。
     *
     * <p>桩需要按真实节奏产出增量，这里的等待是被测协议的一部分，不是测试轮询。
     *
     * @param millis 毫秒
     */
    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
        if (sliceCount > 0) {
            // 时间分片：每帧一个非空增量，桩按 firstDelay/frameInterval 控制到达节奏。
            List<String> sliced = new java.util.ArrayList<>();
            sliced.add(frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"));
            for (int index = 0; index < sliceCount; index++) {
                sliced.add(frame("{\"choices\":[{\"delta\":{\"content\":\"分片" + index + "。\"}}]}"));
            }
            sliced.add(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
            sliced.add(frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}"));
            sliced.add("data: [DONE]\n\n");
            return new Plan(sliced, firstDelayMs, frameIntervalMs);
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
     * @param firstDelayMs 首个 content 帧之前的等待
     * @param frameIntervalMs 相邻帧之间的等待
     */
    private record Plan(List<String> frames, long firstDelayMs, long frameIntervalMs) {

        /**
         * 无节奏控制的响应计划。
         *
         * @param frames SSE 帧
         */
        private Plan(List<String> frames) {
            this(frames, 0, 0);
        }
    }
}
