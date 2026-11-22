package com.riccon.worker.run.service;

import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.config.OutboxLaneProperties;
import com.riccon.worker.run.domain.OutboxLane;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Outbox 分通道调度入口（ADR-0023 第 7.3 节）。
 *
 * <p>{@code default} 通道固定 1 个轮询线程，保持 chat/report 事件顺序；{@code heavy} 通道按
 * {@code riccon.worker.outbox.heavy-concurrency}（默认 2）启动多个轮询线程，承载文件解析、知识库
 * 索引与连接器同步。通道由 {@code RICCON_WORKER_OUTBOX_LANES} 选择，允许按实例拆分部署。
 *
 * <p>与旧 {@link OutboxScheduler} 的差异：旧调度器领取全部事件，会在本类启用时造成通道争抢，
 * 因此只在显式设置 {@code legacy-all-lane-scheduling=true} 时注册（回滚窗口用）。
 */
@Component
@ConditionalOnProperty(name = "riccon.worker.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxLaneScheduler {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxLaneScheduler.class);

    /** 实际轮询器。 */
    private final OutboxPoller poller;

    /** 通道与并发配置。 */
    private final OutboxLaneProperties laneProperties;

    /** 轮询间隔与首次延迟。 */
    private final OutboxProperties outboxProperties;

    /** Spring 调度器；线程池大小由 {@code spring.task.scheduling.pool.size} 控制。 */
    private final TaskScheduler taskScheduler;

    /** 已提交任务，关闭时取消。 */
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    /**
     * 创建分通道调度器。
     *
     * @param poller 轮询器
     * @param laneProperties 通道配置
     * @param outboxProperties 轮询间隔配置
     * @param taskScheduler Spring 调度器
     */
    public OutboxLaneScheduler(
            OutboxPoller poller,
            OutboxLaneProperties laneProperties,
            OutboxProperties outboxProperties,
            TaskScheduler taskScheduler) {
        this.poller = poller;
        this.laneProperties = laneProperties;
        this.outboxProperties = outboxProperties;
        this.taskScheduler = taskScheduler;
    }

    /** 为每个启用通道提交固定延迟任务；同一通道的多个线程使用相同事件过滤。 */
    @PostConstruct
    void start() {
        List<OutboxLane> enabled = laneProperties.enabledLanes();
        for (OutboxLane lane : enabled) {
            int concurrency = lane == OutboxLane.HEAVY ? laneProperties.heavyConcurrency() : 1;
            for (int worker = 0; worker < concurrency; worker++) {
                int workerIndex = worker;
                tasks.add(taskScheduler.scheduleWithFixedDelay(
                        () -> poll(lane, workerIndex),
                        Instant.now().plus(outboxProperties.pollInitialDelay()),
                        outboxProperties.pollInterval()));
            }
        }
        List<String> covered =
                enabled.stream().flatMap(lane -> lane.eventTypes().stream()).toList();
        List<String> uncovered = OutboxLane.allEventTypes().stream()
                .filter(eventType -> !covered.contains(eventType))
                .toList();
        if (!uncovered.isEmpty()) {
            // 分实例部署是合法模式：只提示本实例不消费的事件，不阻断启动。
            LOG.info("outbox.lane.partial_deployment lanes={} uncoveredEventTypes={}", enabled, uncovered);
        }
        LOG.info(
                "outbox.lane.scheduler.started lanes={} heavyConcurrency={} maxProcessing={}",
                enabled,
                laneProperties.heavyConcurrency(),
                laneProperties.maxProcessing());
    }

    /** 取消全部通道任务；线程池由 Spring 调度器负责关闭。 */
    @PreDestroy
    void stop() {
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
    }

    /**
     * 执行一个通道的一轮轮询。
     *
     * @param lane 通道
     * @param workerIndex 通道内线程序号，仅用于日志
     */
    private void poll(OutboxLane lane, int workerIndex) {
        try {
            int processed = poller.pollOnce(lane);
            if (processed > 0) {
                LOG.info(
                        "outbox.lane.poll.completed lane={} worker={} processed={}",
                        lane.code(),
                        workerIndex,
                        processed);
            }
        } catch (RuntimeException ex) {
            // 数据库/对象存储瞬时故障不应杀死调度；下一轮重试，日志保留堆栈供排查。
            LOG.error("outbox.lane.poll.failed lane={} worker={}", lane.code(), workerIndex, ex);
        }
    }
}
