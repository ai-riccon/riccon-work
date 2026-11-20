package com.riccon.worker.model.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 Authorization 中 key 返回不同状态的最小模型桩。
 *
 * <p>{@code TestModelStub} 的状态码是全局的，无法验证“同一 provider 第一把 key 401、
 * 第二把 key 成功”的 key 池顺序；本桩只补这一能力，仍只监听 127.0.0.1，不发真实出网。
 */
public final class ApiKeyRoutingStub implements AutoCloseable {

    /** HTTP 服务。 */
    private final HttpServer server;

    /** 每个 Bearer 值对应的状态码。 */
    private final Map<String, Integer> statusByKey = new ConcurrentHashMap<>();

    /** 收到的 Authorization 头（按顺序）。 */
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

    /** 请求数。 */
    private final AtomicInteger requests = new AtomicInteger();

    /** 全局状态码；0 表示按 key 或默认 200。 */
    private final AtomicInteger forcedStatus = new AtomicInteger();

    /**
     * 启动桩。
     */
    public ApiKeyRoutingStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start api-key routing stub", ex);
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

    /**
     * 为指定 key 设置响应状态。
     *
     * @param apiKey 明文 key
     * @param status HTTP 状态
     */
    public void statusFor(String apiKey, int status) {
        statusByKey.put("Bearer " + apiKey, status);
    }

    /**
     * 设置全局状态码（优先级高于按 key 配置）。
     *
     * @param status HTTP 状态；0 恢复
     */
    public void forceStatus(int status) {
        forcedStatus.set(status);
    }

    /**
     * 返回请求数。
     *
     * @return 请求数
     */
    public int requestCount() {
        return requests.get();
    }

    /**
     * 返回按顺序记录的 Authorization 头。
     *
     * @return 头值列表
     */
    public List<String> authorizations() {
        synchronized (authorizations) {
            return List.copyOf(authorizations);
        }
    }

    /**
     * 处理请求。
     *
     * @param exchange HTTP 交换
     * @throws IOException 写入失败
     */
    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        requests.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization == null ? "" : authorization);
        int status = forcedStatus.get();
        if (status <= 0) {
            status = statusByKey.getOrDefault(authorization, 200);
        }
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            write(output, "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n");
            write(output, "data: {\"choices\":[{\"delta\":{\"content\":\"来自可用 key 的回答。\"}}]}\n\n");
            write(output, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
            write(output, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n");
            write(output, "data: [DONE]\n\n");
        }
        exchange.close();
    }

    /**
     * 写出并 flush 一个 SSE 帧。
     *
     * @param output 响应体
     * @param frame 帧
     * @throws IOException 写入失败
     */
    private static void write(OutputStream output, String frame) throws IOException {
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /** 关闭服务。 */
    @Override
    public void close() {
        server.stop(0);
    }
}
