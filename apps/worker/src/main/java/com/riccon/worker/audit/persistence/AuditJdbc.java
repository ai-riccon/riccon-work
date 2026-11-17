package com.riccon.worker.audit.persistence;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计事件持久化。
 *
 * <p>只提供追加方法，没有 update/delete；数据库触发器进一步拒绝修改与删除（ADR-0007）。
 * 写入失败直接抛异常，由业务事务回滚或任务失败，禁止静默吞掉。
 */
@Component
public class AuditJdbc {

    /** 审计写入 SQL。 */
    private static final String INSERT_SQL = """
            INSERT INTO audit_event (
                event_id, occurred_at, tenant_id, actor_type, actor_id, action,
                target_type, target_id, outcome, trace_id, request_id, source_ip,
                details, schema_version
            ) VALUES (
                :eventId, :occurredAt, :tenantId, :actorType, :actorId, :action,
                :targetType, :targetId, :outcome, :traceId, :requestId, :sourceIp,
                :details, :schemaVersion
            )
            """;

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /** details JSON 序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审计持久化。
     *
     * @param tenantJdbc 租户数据访问层
     * @param objectMapper JSON 序列化器
     */
    public AuditJdbc(TenantJdbc tenantJdbc, ObjectMapper objectMapper) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 追加审计事件。
     *
     * @param event 已校验事件
     * @throws IllegalStateException details 无法序列化
     */
    public void append(AuditEvent event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("eventId", UuidBinary.toBytes(event.eventId()));
        params.put("occurredAt", java.time.LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        params.put("actorType", event.actorType().name());
        params.put("actorId", event.actorId());
        params.put("action", event.action());
        params.put(
                "targetType",
                event.targetType() == null ? null : event.targetType().name());
        params.put("targetId", event.targetId());
        params.put("outcome", event.outcome().name());
        params.put("traceId", event.traceId());
        params.put("requestId", event.requestId());
        params.put("sourceIp", null);
        params.put("details", toJson(event));
        params.put("schemaVersion", event.schemaVersion());
        tenantJdbc.insert(TenantId.of(event.tenantId()), INSERT_SQL, params);
    }

    /**
     * 序列化 details。
     *
     * @param event 审计事件
     * @return JSON 字符串
     */
    private String toJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.details());
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize audit details", ex);
        }
    }
}
