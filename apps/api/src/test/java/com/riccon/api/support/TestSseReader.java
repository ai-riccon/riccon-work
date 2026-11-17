package com.riccon.api.support;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 测试侧 SSE 读取器。
 *
 * <p>独立于生产实现，按线格式读取 {@code id}/{@code event}/{@code data}，用于验证 API 输出的真实帧结构；
 * 与生产读取器互为对照，避免同一个实现同时当被测对象与断言工具。
 */
public final class TestSseReader implements Closeable {

    /** 文本读取器。 */
    private final BufferedReader reader;

    /**
     * 创建读取器。
     *
     * @param input 响应体
     */
    public TestSseReader(InputStream input) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /**
     * 读取下一帧。
     *
     * @return 帧；流结束返回 {@code null}
     * @throws IOException 读取失败
     */
    public Frame next() throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    return new Frame(id, event, data.toString());
                }
                id = null;
                event = null;
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0
                    ? ""
                    : line.substring(
                            line.length() > separator + 1 && line.charAt(separator + 1) == ' '
                                    ? separator + 2
                                    : separator + 1);
            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value;
                case "data" -> {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                }
                default -> {
                    // 忽略未知字段。
                }
            }
        }
        return hasData ? new Frame(id, event, data.toString()) : null;
    }

    /** 关闭读取器。 */
    @Override
    public void close() throws IOException {
        reader.close();
    }

    /**
     * SSE 帧。
     *
     * @param id 事件 ID
     * @param event 事件类型
     * @param data JSON
     */
    public record Frame(String id, String event, String data) {}
}
