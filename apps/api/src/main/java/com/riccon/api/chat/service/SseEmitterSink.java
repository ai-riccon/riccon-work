package com.riccon.api.chat.service;

import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 {@link SseEmitter} 的 SSE 输出。
 *
 * <p>Spring 的 {@code StreamingResponseBody} 给业务代码的是 {@code NonFlushingOutputStream}，显式 flush 会被忽略，
 * 浏览器在流结束前收不到事件；{@code SseEmitter.send} 每帧写入并 flush，是可靠的流式实现。
 */
public final class SseEmitterSink implements ChatEventSink {

    /** 事件发射器。 */
    private final SseEmitter emitter;

    /**
     * 创建输出。
     *
     * @param emitter SSE 发射器
     */
    public SseEmitterSink(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    }

    /**
     * 发送一帧；JSON 以 text/plain 原样写入，避免被 JSON 转换器二次转义。
     *
     * @param id 事件 ID；可为空
     * @param event 事件类型
     * @param data JSON
     * @throws IOException 客户端断开
     */
    @Override
    public void send(String id, String event, String data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data, MediaType.TEXT_PLAIN);
        if (id != null && !id.isBlank()) {
            builder.id(id);
        }
        emitter.send(builder);
    }
}
