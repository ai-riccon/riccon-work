package com.riccon.worker.chat.service;

import com.riccon.worker.chat.domain.ChatEvent;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于 {@link SseEmitter} 的对话 SSE 输出。
 *
 * <p>Spring 的 {@code StreamingResponseBody} 给业务代码的是 {@code NonFlushingOutputStream}，
 * 显式 flush 会被忽略，客户端在流结束前收不到增量。{@code SseEmitter.send} 每帧写入并 flush，
 * 是可靠的逐事件输出实现（与 API 侧 TASK-0019 的修复一致）。
 *
 * <p>事件 ID、事件类型与 JSON data 与 {@code chat-event.v1} 契约一致；JSON 以 text/plain
 * 原样写入，避免被消息转换器二次加引号。
 */
public final class SseEmitterChatSink implements ChatEventSink {

    /** 事件发射器。 */
    private final SseEmitter emitter;

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建输出。
     *
     * @param emitter SSE 发射器
     * @param objectMapper JSON 序列化
     */
    public SseEmitterChatSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 发送一帧事件。
     *
     * @param event 聊天事件
     * @throws IOException 客户端断开或发射器已关闭
     */
    @Override
    public void write(ChatEvent event) throws IOException {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize chat event", ex);
        }
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(event.eventId().toString())
                .name(event.eventType())
                .data(json, MediaType.TEXT_PLAIN);
        try {
            emitter.send(builder);
        } catch (IllegalStateException ex) {
            // SseEmitter 在 complete/超时后发送会抛 IllegalStateException；对调用方等价于连接已断开。
            throw new IOException("sse emitter is closed", ex);
        }
    }
}
