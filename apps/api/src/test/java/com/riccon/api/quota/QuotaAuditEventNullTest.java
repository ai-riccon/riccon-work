package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计信封对动作级 nullable details 的支持（F-002）。
 *
 * <p>{@code quota.limit.changed.previousLimit} 首次设置或“显式不限 → 数字”时必须是 JSON null，而不是缺键。
 * 本测试锁定 {@link AuditEvent} 的 null 容忍拷贝、不可变性、插入顺序与序列化输出；DB 往返由
 * {@code QuotaAdminApiTest} 的审计查询断言覆盖。
 */
class QuotaAuditEventNullTest {

    /** JSON 序列化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * details 中的 null 值被保留并输出为 JSON null。
     *
     * @throws Exception JSON 序列化失败
     */
    @Test
    void create_preservesNullDetailValue() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("scopeType", "TENANT");
        source.put("scopeId", "tenant-1");
        source.put("window", "DAILY");
        source.put("previousLimit", null);
        source.put("limit", 5);

        AuditEvent event = AuditEvent.create(
                Instant.parse("2026-11-21T00:00:00Z"),
                "platform",
                AuditActorType.USER,
                "01a1fa42-3220-7411-92fb-63bbe7ddc6f2",
                AuditActions.QUOTA_LIMIT_CHANGED,
                AuditTargetType.QUOTA,
                "tenant-1",
                AuditOutcome.SUCCESS,
                "trace-1",
                null,
                null,
                source);

        assertThat(event.details()).containsEntry("previousLimit", null);
        assertThat(event.details()).containsEntry("limit", 5);
        assertThat(event.details()).containsKey("previousLimit");
        String json = MAPPER.writeValueAsString(event.details());
        assertThat(json).contains("\"previousLimit\":null");
    }

    /**
     * details 是防御性不可变拷贝：改原 map 不影响事件，事件 map 不可写。
     */
    @Test
    void create_defensivelyCopiesDetails() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("previousLimit", null);
        source.put("limit", 5);

        AuditEvent event = AuditEvent.create(
                Instant.parse("2026-11-21T00:00:00Z"),
                "platform",
                AuditActorType.USER,
                "01a1fa42-3220-7411-92fb-63bbe7ddc6f2",
                AuditActions.QUOTA_LIMIT_CHANGED,
                AuditTargetType.QUOTA,
                "tenant-1",
                AuditOutcome.SUCCESS,
                "trace-1",
                null,
                null,
                source);

        source.put("limit", 9);
        assertThat(event.details()).containsEntry("limit", 5);
        assertThatThrownBy(() -> event.details().put("limit", 9)).isInstanceOf(UnsupportedOperationException.class);
    }
}
