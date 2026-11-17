package com.riccon.worker.run.domain;

import com.riccon.platform.domain.research.RunKind;

/**
 * Outbox 事件处理器 SPI。
 *
 * <p>每种事件声明一个事件类型与对应的运行类别；Worker 在租户作用域内调用实现。
 * 处理器必须幂等：重复投递、租约过期后的再次领取都不得产生重复产物。
 */
public interface OutboxEventHandler {

    /**
     * 返回本处理器消费的事件类型。
     *
     * @return 契约事件名，例如 {@code riccon.file.parse.requested.v1}
     */
    String eventType();

    /**
     * 返回事件对应的运行类别，用于校验 agent_run.kind。
     *
     * @return 运行类别
     */
    RunKind runKind();

    /**
     * 处理事件。
     *
     * @param event 已领取事件
     * @throws EventProcessingException 业务失败；异常携带稳定错误码与可重试标记
     */
    void handle(ClaimedOutboxEvent event);
}
