package com.riccon.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 持久化工具与 {@link TenantJdbc} 租户守卫的单元测试。
 *
 * <p>守卫必须在执行 SQL 前失败，因此使用 mock 的 {@link JdbcClient} 即可验证；真实 SQL 行为由集成测试覆盖。
 */
class TenantJdbcTest {

    /** 每个测试结束后清理 ThreadLocal，避免线程复用串租户。 */
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 缺少 tenant_id 条件的查询必须被拒绝。 */
    @Test
    void queryRejectsSqlWithoutTenantCondition() {
        TenantJdbc tenantJdbc = new TenantJdbc(mock(JdbcClient.class));
        TenantContext.set(TenantId.of("tenant-a"));
        assertThatThrownBy(() -> tenantJdbc.query(
                        TenantId.of("tenant-a"), "SELECT id FROM app_user", Map.of(), (rs, rowNum) -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id = :tenantId");
    }

    /** 传入租户与线程上下文不一致时必须失败，阻止线程池串租户。 */
    @Test
    void queryRejectsTenantDifferentFromContext() {
        TenantJdbc tenantJdbc = new TenantJdbc(mock(JdbcClient.class));
        TenantContext.set(TenantId.of("tenant-a"));
        assertThatThrownBy(() -> tenantJdbc.query(
                        TenantId.of("tenant-b"),
                        "SELECT id FROM app_user WHERE tenant_id = :tenantId",
                        Map.of(),
                        (rs, rowNum) -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current tenant context");
    }

    /** 没有租户上下文时必须快速失败。 */
    @Test
    void queryFailsWithoutTenantContext() {
        TenantJdbc tenantJdbc = new TenantJdbc(mock(JdbcClient.class));
        assertThatThrownBy(() -> tenantJdbc.query(
                        TenantId.of("tenant-a"),
                        "SELECT id FROM app_user WHERE tenant_id = :tenantId",
                        Map.of(),
                        (rs, rowNum) -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant context is not set");
    }

    /** 插入语句必须包含 tenant_id 列与参数。 */
    @Test
    void insertRejectsSqlWithoutTenantColumn() {
        TenantJdbc tenantJdbc = new TenantJdbc(mock(JdbcClient.class));
        TenantContext.set(TenantId.of("tenant-a"));
        assertThatThrownBy(() ->
                        tenantJdbc.insert(TenantId.of("tenant-a"), "INSERT INTO app_user (id) VALUES (:id)", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    /** UUID 与 BINARY(16) 必须可逆。 */
    @Test
    void binaryUuidsRoundTrip() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = BinaryUuids.toBytes(uuid);
        assertThat(bytes).hasSize(16);
        assertThat(BinaryUuids.fromBytes(bytes)).isEqualTo(uuid);
        assertThat(BinaryUuids.fromBytes(null)).isNull();
    }

    /** 非 16 字节输入必须拒绝。 */
    @Test
    void binaryUuidsRejectWrongLength() {
        assertThatThrownBy(() -> BinaryUuids.fromBytes(new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    /** DATETIME(6) 与 Instant 按 UTC 互转。 */
    @Test
    void sqlTimestampsUseUtc() {
        Instant instant = Instant.parse("2026-11-17T12:34:56.123456Z");
        LocalDateTime value = SqlTimestamps.toDatabase(instant);
        assertThat(value).isEqualTo(LocalDateTime.of(2026, 11, 17, 12, 34, 56, 123_456_000));
        assertThat(SqlTimestamps.toInstant(value)).isEqualTo(instant);
        assertThat(SqlTimestamps.toDatabase(null)).isNull();
        assertThat(SqlTimestamps.toInstant(null)).isNull();
        assertThat(ZoneOffset.UTC).isNotNull();
    }
}
