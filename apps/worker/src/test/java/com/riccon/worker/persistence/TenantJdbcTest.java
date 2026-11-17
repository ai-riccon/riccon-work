package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantJdbc 租户守卫集成测试。 */
class TenantJdbcTest extends WorkerIntegrationTestBase {

    /** 被测的租户数据访问层。 */
    @Autowired
    private TenantJdbc tenantJdbc;

    /** 带租户条件的查询返回本租户数据。 */
    @Test
    void query_withTenantPredicate_returnsScopedRow() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            List<String> names = tenantJdbc.query(
                    TenantId.of(tenant),
                    "SELECT username FROM app_user WHERE tenant_id = :tenantId AND id = :id",
                    Map.of("id", UuidBinary.toBytes(userId)),
                    (rs, rowNum) -> rs.getString("username"));
            assertThat(names).hasSize(1);
        }
    }

    /** 缺少 tenant_id 条件的 SQL 必须被拒绝，不能执行全租户查询。 */
    @Test
    void query_withoutTenantPredicate_throws() {
        String tenant = "tenant-a";
        data.createTenant(tenant);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            assertThatThrownBy(() -> tenantJdbc.query(
                            TenantId.of(tenant),
                            "SELECT username FROM app_user",
                            Map.of(),
                            (rs, rowNum) -> rs.getString("username")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tenant_id = :tenantId");
        }
    }

    /** 调用方声明租户与线程上下文不一致必须快速失败。 */
    @Test
    void query_contextMismatch_throws() {
        String tenantA = "tenant-a";
        String tenantB = "tenant-b";
        data.createTenant(tenantA);
        data.createTenant(tenantB);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenantB))) {
            assertThatThrownBy(() -> tenantJdbc.query(
                            TenantId.of(tenantA),
                            "SELECT username FROM app_user WHERE tenant_id = :tenantId",
                            Map.of(),
                            (rs, rowNum) -> rs.getString("username")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not match");
        }
    }

    /** INSERT 缺少 tenant_id 列必须被拒绝。 */
    @Test
    void insert_withoutTenantColumn_throws() {
        String tenant = "tenant-a";
        data.createTenant(tenant);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            assertThatThrownBy(() -> tenantJdbc.insert(
                            TenantId.of(tenant),
                            "INSERT INTO app_user (id, username, display_name) VALUES (:id, :username, :name)",
                            Map.of(
                                    "id", UuidBinary.toBytes(UuidBinary.newV7()),
                                    "username", "no-tenant",
                                    "name", "No Tenant")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tenant_id");
        }
    }

    /** 带租户列的 INSERT 正常执行。 */
    @Test
    void insert_withTenantColumn_succeeds() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = UuidBinary.newV7();

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            int inserted = tenantJdbc.insert(
                    TenantId.of(tenant),
                    """
                    INSERT INTO app_user (tenant_id, id, username, display_name)
                    VALUES (:tenantId, :id, :username, :name)
                    """,
                    Map.of(
                            "id", UuidBinary.toBytes(userId),
                            "username", "inserted",
                            "name", "Inserted User"));
            assertThat(inserted).isEqualTo(1);
            assertThat(tenantJdbc.queryOne(
                            TenantId.of(tenant),
                            "SELECT username FROM app_user WHERE tenant_id = :tenantId AND id = :id",
                            Map.of("id", UuidBinary.toBytes(userId)),
                            (rs, rowNum) -> rs.getString("username")))
                    .contains("inserted");
        }
    }

    /** 带租户条件的更新只影响本租户。 */
    @Test
    void update_withTenantPredicate_updatesRow() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            int updated = tenantJdbc.update(
                    TenantId.of(tenant),
                    "UPDATE app_user SET display_name = :name WHERE tenant_id = :tenantId AND id = :id",
                    Map.of("name", "Renamed", "id", UuidBinary.toBytes(userId)));
            assertThat(updated).isEqualTo(1);
        }
    }

    /** 更新缺少租户条件必须被拒绝。 */
    @Test
    void update_withoutTenantPredicate_throws() {
        String tenant = "tenant-a";
        data.createTenant(tenant);

        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            assertThatThrownBy(() -> tenantJdbc.update(
                            TenantId.of(tenant), "UPDATE app_user SET display_name = :name", Map.of("name", "X")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tenant_id = :tenantId");
        }
    }
}
