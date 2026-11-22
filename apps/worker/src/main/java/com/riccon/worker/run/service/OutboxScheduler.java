package com.riccon.worker.run.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时轮询入口（旧的全事件通道，回滚窗口用）。
 *
 * <p>使用 fixedDelay 而不是 fixedRate：上一轮处理完成后再等待固定间隔，避免批次叠加。
 * 轮询异常只记录日志，不终止调度线程；下一轮会自行重试，数据库恢复后继续工作。
 *
 * <p>M05 起默认由 {@link OutboxLaneScheduler} 按 default/heavy 通道调度；本类只在显式设置
 * {@code riccon.worker.outbox.legacy-all-lane-scheduling=true} 时注册，避免与分通道调度争抢事件。
 */
@Component
@ConditionalOnProperty(name = "riccon.worker.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "riccon.worker.outbox.legacy-all-lane-scheduling", havingValue = "true")
public class OutboxScheduler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxScheduler.class);

    /** 实际轮询器。 */
    private final OutboxPoller poller;

    /**
     * 创建调度器。
     *
     * @param poller 轮询器
     */
    public OutboxScheduler(OutboxPoller poller) {
        this.poller = poller;
    }

    /** 按配置间隔执行一轮轮询。 */
    @Scheduled(
            fixedDelayString = "${riccon.worker.outbox.poll-interval:500ms}",
            initialDelayString = "${riccon.worker.outbox.poll-initial-delay:5s}")
    public void poll() {
        try {
            int processed = poller.pollOnce();
            if (processed > 0) {
                LOG.info("outbox.poll.completed processed={}", processed);
            }
        } catch (RuntimeException ex) {
            // 数据库/对象存储瞬时故障不应杀死调度；下一轮重试，日志保留堆栈供排查。
            LOG.error("outbox.poll.failed", ex);
        }
    }
}
