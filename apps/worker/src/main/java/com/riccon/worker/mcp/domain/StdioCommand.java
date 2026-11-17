package com.riccon.worker.mcp.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * stdio MCP 命令拆分。
 *
 * <p>数据库中的 {@code command} 是平台维护的静态命令，但仍按不可信输入处理：只支持空白分隔、
 * 单/双引号与反斜杠转义，不做变量展开、管道或重定向，避免通过目录注入 shell。
 */
public final class StdioCommand {

    /** 工具类禁止实例化。 */
    private StdioCommand() {}

    /**
     * 拆分命令。
     *
     * @param command 命令文本
     * @return 可执行文件与参数
     * @throws IllegalArgumentException 命令为空或引号未闭合
     */
    public static List<String> split(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("stdio mcp command must not be blank");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean hasToken = false;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (character == '\\' && index + 1 < command.length()) {
                current.append(command.charAt(++index));
                hasToken = true;
                continue;
            }
            if ((character == '"' || character == '\'')) {
                if (quoted && character != quote) {
                    current.append(character);
                    continue;
                }
                if (quoted) {
                    quoted = false;
                } else {
                    quoted = true;
                    quote = character;
                }
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(character) && !quoted) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            current.append(character);
            hasToken = true;
        }
        if (quoted) {
            throw new IllegalArgumentException("stdio mcp command contains an unterminated quote");
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
