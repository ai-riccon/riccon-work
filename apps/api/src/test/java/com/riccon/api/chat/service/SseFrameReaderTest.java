package com.riccon.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * SSE 帧解析测试。
 *
 * <p>覆盖字段、多行 data、注释、未知字段、心跳空行与 EOF 末帧，确保 API 转发格式与 Worker 写入一致。
 */
class SseFrameReaderTest {

    /** 解析连续帧与多行 data。 */
    @Test
    void parsesFramesAndMultiLineData() throws Exception {
        String stream = """
                : keep-alive
                id: 1
                event: riccon.chat.started.v1
                data: {"eventType":"started"}
                retry: 1000

                id: 2
                event: riccon.chat.delta.v1
                data: line1
                data: line2

                """;
        try (SseFrameReader reader =
                new SseFrameReader(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)))) {
            SseFrame first = reader.next();
            assertThat(first.id()).isEqualTo("1");
            assertThat(first.event()).isEqualTo("riccon.chat.started.v1");
            assertThat(first.data()).isEqualTo("{\"eventType\":\"started\"}");

            SseFrame second = reader.next();
            assertThat(second.id()).isEqualTo("2");
            assertThat(second.event()).isEqualTo("riccon.chat.delta.v1");
            assertThat(second.data()).isEqualTo("line1\nline2");

            assertThat(reader.next()).isNull();
        }
    }

    /** EOF 前没有空行但已收到 data 时仍返回最后一帧。 */
    @Test
    void returnsTrailingFrameAtEof() throws Exception {
        String stream = "event: riccon.chat.delta.v1\ndata: tail";
        try (SseFrameReader reader =
                new SseFrameReader(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)))) {
            SseFrame frame = reader.next();
            assertThat(frame).isNotNull();
            assertThat(frame.event()).isEqualTo("riccon.chat.delta.v1");
            assertThat(frame.data()).isEqualTo("tail");
            assertThat(reader.next()).isNull();
        }
    }

    /** 空流返回 null。 */
    @Test
    void emptyStreamReturnsNull() throws Exception {
        try (SseFrameReader reader = new SseFrameReader(new ByteArrayInputStream(new byte[0]))) {
            assertThat(reader.next()).isNull();
        }
    }
}
