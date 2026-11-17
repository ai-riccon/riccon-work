package com.riccon.api.outbox.persistence;

import com.riccon.api.outbox.domain.OutboxEvent;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox 事件持久化。
 *
 * <p>事件与业务数据在同一事务写入；唯一键 {@code (tenant_id, idempotency_key)} 阻止重复投递。payload 只
 * 序列化白名单参数，不写提示词、原文或凭据。
 */
@Component
public class OutboxJdbc {

    /** 插入事件；状态初始为 PENDING，attempts 从 0 开始。 */
    private static final String INSERT = """
            INSERT INTO agent_run_outbox (
                tenant_id, id, run_id, event_type, event_version, idempotency_key,
                payload, status, attempts, available_at, created_at, updated_at
            ) VALUES (
                :tenantId, :id, :runId, :eventType, :eventVersion, :idempotencyKey,
                :payload, 'PENDING', 0, :availableAt, :availableAt, :availableAt
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** payload JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建持久化组件。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     * @param objectMapper JSON 序列化器
     */
    public OutboxJdbc(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 写入一条 outbox 事件。
     *
     * @param tenantId 当前租户
     * @param event 事件
     * @throws IllegalStateException payload 无法序列化
     */
    public void insert(TenantId tenantId, OutboxEvent event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", BinaryUuids.toBytes(event.id()));
        params.put("runId", BinaryUuids.toBytes(event.runId()));
        params.put("eventType", event.eventType());
        params.put("eventVersion", event.eventVersion());
        params.put("idempotencyKey", event.idempotencyKey());
        params.put("payload", toJson(event));
        params.put("availableAt", SqlTimestamps.toDatabase(event.occurredAt()));
        tenantJdbc.insert(tenantId, INSERT, params);
    }

    /**
     * 序列化 payload；失败属于实现错误，必须让业务事务回滚。
     *
     * @param event 事件
     * @return JSON 字符串
     */
    private String toJson(OutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize outbox payload", exception);
        }
    }
}
