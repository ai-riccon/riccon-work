package com.riccon.worker.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riccon.worker.agentscope.McpMockServer;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * 测试用 MCP stdio 命令构造。
 *
 * <p>{@code mcp_server.command} 有 512 字符上限，不能写入完整 surefire classpath；这里只拼
 * mock server 真正需要的 test-classes 与 Jackson 2 三个 jar，命令长度稳定可控。
 */
public final class TestMcpCommands {

    /** 工具类禁止实例化。 */
    private TestMcpCommands() {}

    /**
     * 构造命令。
     *
     * @return 可拆分执行的命令字符串
     */
    public static String mockServer() {
        String java = "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\"";
        String classpath = String.join(
                ":",
                "target/test-classes",
                jarOf(ObjectMapper.class),
                jarOf(JsonFactory.class),
                jarOf(JsonIgnore.class));
        String command = java + " -cp \"" + classpath + "\" " + McpMockServer.class.getName();
        if (command.length() > 500) {
            throw new IllegalStateException("test mcp command exceeds database column budget");
        }
        return command;
    }

    /**
     * 取类所在 jar。
     *
     * @param type 类
     * @return jar 路径
     */
    private static String jarOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("cannot resolve code source for " + type.getName(), ex);
        }
    }
}
