package com.riccon.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 冒烟 3：AgentScope MCP stdio 客户端。
 *
 * <p>通过 {@link McpClientBuilder} 启动 {@link McpMockServer} 子进程，显式协商 2024-11-05 协议，
 * 先 {@code listTools} 再调用 echo 工具并断言文本结果。整个过程仅本机进程通信，不访问外网。
 */
class McpStdioSmokeTest {

    /** stdio 客户端应完成初始化、工具发现与工具调用三个步骤。 */
    @Test
    @Timeout(90)
    void stdioClient_listsAndCallsMockTool() {
        McpClientWrapper client = McpClientBuilder.create("riccon-mcp-smoke")
                .stdioTransport(
                        javaExecutable(), List.of("-cp", testClasspath(), McpMockServer.class.getName()), Map.of())
                .protocolVersions(McpMockServer.PROTOCOL_VERSION)
                .timeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15))
                .buildSync();

        try {
            client.initialize().block(Duration.ofSeconds(20));

            List<McpSchema.Tool> tools = client.listTools().block(Duration.ofSeconds(20));
            assertThat(tools).isNotNull();
            assertThat(tools).extracting(McpSchema.Tool::name).contains(McpMockServer.TOOL_NAME);

            McpSchema.CallToolResult result = client.callTool(McpMockServer.TOOL_NAME, Map.of("text", "hello-mcp"))
                    .block(Duration.ofSeconds(20));
            assertThat(result).isNotNull();
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0))
                    .isInstanceOfSatisfying(
                            McpSchema.TextContent.class,
                            content -> assertThat(content.text()).isEqualTo("echo: hello-mcp"));
        } finally {
            // close() 会关闭 stdio 传输并终止 mock server 子进程。
            client.close();
        }
    }

    /** 使用当前测试 JVM 的 java 可执行文件，避免依赖宿主环境额外安装。 */
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * 取 surefire 提供的完整测试 classpath；在 IDE 中直接运行时回退到 java.class.path。
     *
     * @return 可传给子 JVM 的 classpath
     */
    private static String testClasspath() {
        String surefireClasspath = System.getProperty("surefire.test.class.path");
        if (surefireClasspath != null && !surefireClasspath.isBlank()) {
            return surefireClasspath;
        }
        return System.getProperty("java.class.path");
    }
}
