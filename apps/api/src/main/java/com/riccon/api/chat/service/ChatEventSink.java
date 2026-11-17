package com.riccon.api.chat.service;

import java.io.IOException;

/**
 * 对话事件输出目标。
 *
 * <p>抽象 SSE 写入，使 {@link ChatService} 不依赖 Servlet API；生产实现 {@code SseEmitterSink} 逐事件 flush，
 * 测试可用内存实现断言帧顺序。
 */
public interface ChatEventSink {

    /**
     * 发送一帧。
     *
     * @param id 事件 ID；可为空
     * @param event 事件类型
     * @param data JSON
     * @throws IOException 客户端断开或写入失败
     */
    void send(String id, String event, String data) throws IOException;
}
