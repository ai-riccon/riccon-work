package com.riccon.worker.run.service;

import com.riccon.worker.run.domain.OutboxEventHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 事件类型到处理器的路由。
 *
 * <p>Spring 注入全部 {@link OutboxEventHandler}，启动时建立不可变映射；重复事件类型直接失败，
 * 避免两个处理器竞争同一事件。未注册的事件由轮询器标记为 DEAD 并安全跳过。
 */
@Component
public class OutboxEventDispatcher {

    /** 事件类型到处理器的映射。 */
    private final Map<String, OutboxEventHandler> handlers;

    /**
     * 创建路由表。
     *
     * @param handlerList 所有处理器
     */
    public OutboxEventDispatcher(List<OutboxEventHandler> handlerList) {
        Map<String, OutboxEventHandler> map = new HashMap<>();
        for (OutboxEventHandler handler : handlerList) {
            OutboxEventHandler previous = map.put(handler.eventType(), handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate outbox handler for event type: " + handler.eventType());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    /**
     * 查找处理器。
     *
     * @param eventType 事件类型
     * @return 处理器；未实现时为空
     */
    public Optional<OutboxEventHandler> find(String eventType) {
        return Optional.ofNullable(handlers.get(eventType));
    }

    /**
     * 返回已注册事件类型数量，供启动日志与测试使用。
     *
     * @return 处理器数量
     */
    public int size() {
        return handlers.size();
    }
}
