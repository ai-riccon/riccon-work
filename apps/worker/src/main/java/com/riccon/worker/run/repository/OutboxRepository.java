package com.riccon.worker.run.repository;

import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.persistence.OutboxClaimer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Outbox 持久化门面。
 *
 * <p>服务层只依赖本仓库，不直接接触 {@code JdbcClient}；领取参数来自配置，调用方不能
 * 传入批量或租约，避免无界查询。跨租户读取仍由 {@link OutboxClaimer} 承担。
 */
@Component
public class OutboxRepository {

    /** 跨租户领取器。 */
    private final OutboxClaimer outboxClaimer;

    /** 轮询与租约配置。 */
    private final OutboxProperties properties;

    /** 本实例租约标识，进程生命周期内稳定。 */
    private final String leaseOwner;

    /**
     * 创建 outbox 仓库。
     *
     * @param outboxClaimer 跨租户领取器
     * @param properties 轮询配置
     */
    public OutboxRepository(OutboxClaimer outboxClaimer, OutboxProperties properties) {
        this.outboxClaimer = Objects.requireNonNull(outboxClaimer, "outboxClaimer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.leaseOwner = resolveLeaseOwner(properties.leaseOwner());
    }

    /**
     * 按配置领取一批事件。
     *
     * @return 已领取事件
     */
    public List<ClaimedOutboxEvent> claim() {
        return outboxClaimer.claim(leaseOwner, properties.batchSize(), properties.leaseDuration());
    }

    /**
     * 标记事件成功。
     *
     * @param event 已领取事件
     * @return 受影响行数
     */
    public int markDone(ClaimedOutboxEvent event) {
        return outboxClaimer.markDone(event.tenantId(), event.id(), leaseOwner);
    }

    /**
     * 标记可重试失败。
     *
     * @param event 已领取事件
     * @param availableAt 下次可领取时间
     * @param reason 脱敏失败原因
     * @return 受影响行数
     */
    public int markRetry(ClaimedOutboxEvent event, Instant availableAt, String reason) {
        return outboxClaimer.markRetry(event.tenantId(), event.id(), leaseOwner, availableAt, reason);
    }

    /**
     * 标记不可重试失败。
     *
     * @param event 已领取事件
     * @param reason 脱敏失败原因
     * @return 受影响行数
     */
    public int markDead(ClaimedOutboxEvent event, String reason) {
        return outboxClaimer.markDead(event.tenantId(), event.id(), leaseOwner, reason);
    }

    /**
     * 返回本实例租约标识，便于日志与测试。
     *
     * @return 租约标识
     */
    public String leaseOwner() {
        return leaseOwner;
    }

    /**
     * 优先使用显式配置；否则使用主机名与 PID，避免同一主机多实例互相覆盖租约。
     *
     * @param configured 配置值
     * @return 非空租约标识
     */
    private static String resolveLeaseOwner(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        long pid = ProcessHandle.current().pid();
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + pid;
        } catch (UnknownHostException ex) {
            // 主机名不可解析时仍要保证租约标识唯一，不能启动失败。
            return "worker-" + pid;
        }
    }
}
