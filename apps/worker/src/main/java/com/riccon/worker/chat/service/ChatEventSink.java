package com.riccon.worker.chat.service;

import com.riccon.worker.chat.domain.ChatEvent;
import java.io.IOException;

/**
 * 对话 SSE 输出抽象。
 *
 * <p>生产实现基于 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}，
 * 每帧写入后立即 flush；测试可注入内存实现验证事件顺序与失败处理。
 */
public interface ChatEventSink {

    /**
     * 发送一帧事件。
     *
     * @param event 聊天事件
     * @throws IOException 客户端断开或输出不可用
     */
    void write(ChatEvent event) throws IOException;
}
