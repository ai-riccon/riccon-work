package com.riccon.api.outbox.repository;

import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.outbox.persistence.OutboxJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * Outbox 仓储。
 *
 * <p>只暴露追加语义；事件状态推进由 Worker 负责。重复幂等键会抛出唯一键异常，由调用方事务整体回滚。
 */
@Repository
public class OutboxRepository {

    /** Outbox SQL。 */
    private final OutboxJdbc outboxJdbc;

    /**
     * 创建仓储。
     *
     * @param outboxJdbc Outbox SQL
     */
    public OutboxRepository(OutboxJdbc outboxJdbc) {
        this.outboxJdbc = Objects.requireNonNull(outboxJdbc, "outboxJdbc must not be null");
    }

    /**
     * 写入事件。
     *
     * @param tenantId 当前租户
     * @param event 事件
     */
    public void enqueue(TenantId tenantId, OutboxEvent event) {
        outboxJdbc.insert(tenantId, event);
    }
}
