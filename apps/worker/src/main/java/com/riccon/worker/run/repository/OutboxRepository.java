package com.riccon.worker.run.repository;

import com.riccon.worker.config.OutboxProperties;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.OutboxLane;
import com.riccon.worker.run.domain.OutboxLeaseRenewer;
import com.riccon.worker.run.persistence.OutboxClaimer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Outbox 持久化门面。
 *
 * <p>服务层只依赖本仓库，不直接接触 {@code JdbcClient}；领取参数来自配置，调用方不能
 * 传入批量或租约，避免无界查询。跨租户读取仍由 {@link OutboxClaimer} 承担。
 *
 * <p>M05 起支持通道领取与租约续期：按通道领取时 {@code lease_owner} 追加
 * {@code #lane#threadId}，保证同一实例的不同处理线程互不覆盖续期；无通道调用保持旧行为。
 */
@Component
public class OutboxRepository implements OutboxLeaseRenewer {

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
     * 按通道领取一批事件。
     *
     * @param lane 通道；非空时租约标识包含通道与线程
     * @return 已领取事件
     */
    public List<ClaimedOutboxEvent> claim(OutboxLane lane) {
        Objects.requireNonNull(lane, "lane must not be null");
        return outboxClaimer.claim(
                leaseOwner(lane), properties.batchSize(), properties.leaseDuration(), lane.eventTypes());
    }

    /**
     * 返回本次调用的租约标识。
     *
     * <p>无通道调用返回实例级标识；按通道调用返回 {@code instance#lane#threadId}，满足 ADR-0023
     * §7.3 对“同实例不同处理线程可区分”的要求。领取与完成必须由同一线程调用，否则租约不匹配。
     *
     * @param lane 通道；null 表示旧的全事件领取
     * @return 租约标识
     */
    public String leaseOwner(OutboxLane lane) {
        if (lane == null) {
            return leaseOwner;
        }
        return leaseOwner + "#" + lane.code() + "#" + Thread.currentThread().threadId();
    }

    /**
     * 续期单条事件租约。
     *
     * @param tenantId 事件租户
     * @param eventId 事件 ID
     * @param leaseOwner 当前租约持有者
     * @param leaseDuration 新的租约时长
     * @return 受影响行数；0 表示租约已丢失
     */
    @Override
    public int renew(String tenantId, UUID eventId, String leaseOwner, Duration leaseDuration) {
        return outboxClaimer.renew(tenantId, eventId, leaseOwner, leaseDuration);
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
     * 按通道标记事件成功。
     *
     * @param event 已领取事件
     * @param lane 通道
     * @return 受影响行数
     */
    public int markDone(ClaimedOutboxEvent event, OutboxLane lane) {
        return outboxClaimer.markDone(event.tenantId(), event.id(), leaseOwner(lane));
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
     * 按通道标记可重试失败。
     *
     * @param event 已领取事件
     * @param availableAt 下次可领取时间
     * @param reason 脱敏失败原因
     * @param lane 通道
     * @return 受影响行数
     */
    public int markRetry(ClaimedOutboxEvent event, Instant availableAt, String reason, OutboxLane lane) {
        return outboxClaimer.markRetry(event.tenantId(), event.id(), leaseOwner(lane), availableAt, reason);
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
     * 按通道标记不可重试失败。
     *
     * @param event 已领取事件
     * @param reason 脱敏失败原因
     * @param lane 通道
     * @return 受影响行数
     */
    public int markDead(ClaimedOutboxEvent event, String reason, OutboxLane lane) {
        return outboxClaimer.markDead(event.tenantId(), event.id(), leaseOwner(lane), reason);
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
