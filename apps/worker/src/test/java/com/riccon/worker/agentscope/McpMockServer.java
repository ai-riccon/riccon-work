package com.riccon.worker.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 最小 stdio MCP server，测试夹具。
 *
 * <p>按 MCP 2024-11-05 的换行分隔 JSON-RPC 约定实现 {@code initialize}、{@code ping}、
 * {@code tools/list} 与 {@code tools/call}，用于验证 agentscope MCP client，不依赖 npx、Python 或外网。
 */
public final class McpMockServer {

    /** 与测试断言保持一致的协议版本。 */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    /** 测试工具名。 */
    public static final String TOOL_NAME = "echo";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpMockServer() {}

    /**
     * 进程入口：逐行读取 stdin 上的 JSON-RPC 请求，逐行写回响应；stdin 关闭即退出。
     *
     * @param args 未使用
     * @throws Exception JSON 解析或输出失败
     */
    public static void main(String[] args) throws Exception {
        // MCP stdio 传输以 stdout 承载协议消息，这是协议要求，不是调试输出；日志只能写 stderr。
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request = MAPPER.readTree(line);
                JsonNode id = request.get("id");
                if (id == null || id.isNull()) {
                    // notifications/initialized 等通知没有 id，不需要响应。
                    continue;
                }
                output.println(MAPPER.writeValueAsString(respond(request, id)));
            }
        }
    }

    /** 根据 method 构造 JSON-RPC 响应；未知方法返回 -32601，便于客户端快速暴露协议偏差。 */
    private static ObjectNode respond(JsonNode request, JsonNode id) {
        String method = request.path("method").asText("");
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        switch (method) {
            case "initialize" -> response.set("result", initializeResult());
            case "ping" -> response.set("result", MAPPER.createObjectNode());
            case "tools/list" -> response.set("result", toolsListResult());
            case "tools/call" -> response.set("result", toolsCallResult(request.path("params")));
            default -> {
                ObjectNode error = MAPPER.createObjectNode();
                error.put("code", -32601);
                error.put("message", "Method not found: " + method);
                response.set("error", error);
            }
        }
        return response;
    }

    /** 声明固定协议版本、tools 能力与服务端信息。 */
    private static ObjectNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "riccon-mcp-mock");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    /** 暴露一个 echo 工具，输入为必填字符串 text。 */
    private static ObjectNode toolsListResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "回显输入文本");
        ObjectNode inputSchema = tool.putObject("inputSchema");
        inputSchema.put("type", "object");
        ObjectNode properties = inputSchema.putObject("properties");
        properties.putObject("text").put("type", "string");
        inputSchema.putArray("required").add("text");
        return result;
    }

    /** 执行 echo 并返回 MCP text content。 */
    private static ObjectNode toolsCallResult(JsonNode params) {
        String text = params.path("arguments").path("text").asText("");
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", "echo: " + text);
        result.put("isError", false);
        return result;
    }
}
