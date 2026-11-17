package com.riccon.api.chat.service;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Worker SSE 帧读取器。
 *
 * <p>按 SSE 规范累积 {@code id}/{@code event}/{@code data} 字段，遇到空行提交一帧；忽略注释与未知字段。
 * 只用于服务端到服务端的文本流，不解释事件语义（校验在 {@link ChatEventValidator}）。
 *
 * <p>关闭底层 {@link InputStream} 而不是只关闭 {@link BufferedReader}：看门狗与客户端断开路径需要在另一个线程
 * 中断阻塞读取，直接关闭底层流才能解除阻塞。
 */
public final class SseFrameReader implements Closeable {

    /** 底层输入流，供看门狗强制关闭。 */
    private final InputStream input;

    /** 文本读取器。 */
    private final BufferedReader reader;

    /**
     * 创建读取器。
     *
     * @param input Worker 响应体
     */
    public SseFrameReader(InputStream input) {
        this.input = input;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /**
     * 读取下一帧。
     *
     * @return 帧；流结束且没有待提交数据时返回 {@code null}
     * @throws IOException 读取失败或流被看门狗关闭
     */
    public SseFrame next() throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    return new SseFrame(id, event, data.toString());
                }
                // 连续空行或未知字段组成的心跳不产生事件。
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
            String value = separator < 0 ? "" : fieldValue(line, separator);
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
                    // retry 等字段与当前转发语义无关，按规范忽略。
                }
            }
        }
        // EOF 前没有空行：只要收到过 data，仍提交最后一帧，避免丢失上游最后一条事件。
        return hasData ? new SseFrame(id, event, data.toString()) : null;
    }

    /**
     * 读取字段值；兼容 {@code data:value} 与 {@code data: value}。
     *
     * @param line 原始行
     * @param separator 冒号位置
     * @return 字段值
     */
    private static String fieldValue(String line, int separator) {
        int start = separator + 1;
        if (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        return line.substring(start);
    }

    /**
     * 关闭底层流。
     *
     * @throws IOException 关闭失败
     */
    @Override
    public void close() throws IOException {
        input.close();
    }
}
