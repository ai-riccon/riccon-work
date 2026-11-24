package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V11 模型网关纵深迁移（M04 B1a）语义测试。
 *
 * <p>不使用热栈或应用上下文：在一次性 Testcontainers MySQL 8.4.13 上先用 Flyway 只应用到 V10，
 * 种入 M02 形态的租户、角色、模型目录、1 把 ACTIVE + 1 把 RETIRED 跨 provider 的凭据与 1 万条历史调用，
 * 再应用 V11，验证路由/开关/多 key/成本快照/尝试/配额表、权限回填与 DDL 算法。空库直接应用 V11
 * 通过不算本测试的替代；空库路径另行验证 RoleDefinitions 全量权限字典覆盖。
 *
 * <p>多 key 回滚演练在同一个一次性容器的第二个 schema（{@code riccon_rollback}）上执行：V11 后插入
 * 2 把 ACTIVE + 1 把 INVALID，按“每组 1 行、有 ACTIVE 保留 ACTIVE，否则保留 RETIRED”收敛后
 * 删除 V11 对象/列/权限，验证回到 V10 且 M02 数据可读。第三个 schema（{@code riccon_fresh}）验证
 * 空库 V1–V14 后 RoleDefinitions 的权限字典完整。
 *
 * <p>生产执行前提：V11 的一次性迁移 Job、备份/恢复与旧 Worker 回滚窗口仍需 B10 RUNBOOK 与
 * 用户 T4 生产批准；本测试只证明迁移与回滚在非生产副本上可执行。
 */
@Tag("migration")
class V11MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像；命令参数与 API 集成测试保持一致。 */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--log-bin-trust-function-creators=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    /** 正向迁移 schema。 */
    private static final String FORWARD_SCHEMA = "riccon";

    /** 回滚演练 schema。 */
    private static final String ROLLBACK_SCHEMA = "riccon_rollback";

    /** fresh install 演练 schema。 */
    private static final String FRESH_SCHEMA = "riccon_fresh";

    /** 容器业务账号。 */
    private static final String USERNAME = "riccon";

    /** 仅容器内使用的占位密码，不对应任何真实环境。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 平台保留租户。 */
    private static final String PLATFORM_TENANT = "platform";

    /** 业务租户。 */
    private static final String TENANT = "acme";

    /** 平台管理员账号。 */
    private static final UUID PLATFORM_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000001");

    /** 租户管理员账号。 */
    private static final UUID TENANT_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000002");

    /** 普通员工账号。 */
    private static final UUID MEMBER_USER = uuid("00000000-0000-7000-8000-000000000003");

    /** 平台管理员角色。 */
    private static final UUID PLATFORM_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000101");

    /** 租户管理员角色。 */
    private static final UUID TENANT_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000102");

    /** 普通员工角色。 */
    private static final UUID MEMBER_ROLE = uuid("00000000-0000-7000-8000-000000000103");

    /** 供应商 A：V10 阶段持有 ACTIVE 凭据。 */
    private static final UUID PROVIDER_A = uuid("00000000-0000-7000-8000-000000000201");

    /** 供应商 B：V10 阶段持有 RETIRED 凭据。 */
    private static final UUID PROVIDER_B = uuid("00000000-0000-7000-8000-000000000202");

    /** 供应商 C：V11 之后用于多 key 插入（2 ACTIVE + 1 INVALID）。 */
    private static final UUID PROVIDER_C = uuid("00000000-0000-7000-8000-000000000203");

    /** 最早创建的 ACTIVE 模型；V11 路由种子必须选中它。 */
    private static final UUID MODEL_EARLY = uuid("00000000-0000-7000-8000-000000000301");

    /** 较晚创建的 ACTIVE 模型；不得被路由种子选中。 */
    private static final UUID MODEL_LATE = uuid("00000000-0000-7000-8000-000000000302");

    /** 供应商 B 的模型；用于跨 provider 目录断言。 */
    private static final UUID MODEL_B = uuid("00000000-0000-7000-8000-000000000303");

    /** V10 阶段供应商 A 的 ACTIVE 凭据。 */
    private static final UUID CREDENTIAL_A = uuid("00000000-0000-7000-8000-000000000401");

    /** V10 阶段供应商 B 的 RETIRED 凭据。 */
    private static final UUID CREDENTIAL_B = uuid("00000000-0000-7000-8000-000000000402");

    /** V11 之后供应商 C 的第一把 ACTIVE 凭据。 */
    private static final UUID CREDENTIAL_C1 = uuid("00000000-0000-7000-8000-000000000403");

    /** V11 之后供应商 C 的第二把 ACTIVE 凭据。 */
    private static final UUID CREDENTIAL_C2 = uuid("00000000-0000-7000-8000-000000000404");

    /** V11 之后供应商 C 的 INVALID 凭据。 */
    private static final UUID CREDENTIAL_C3 = uuid("00000000-0000-7000-8000-000000000405");

    /** V11 之后供应商 B 的 INVALID 凭据；使 B 组无 ACTIVE，回滚后必须为 RETIRED。 */
    private static final UUID CREDENTIAL_B_INVALID = uuid("00000000-0000-7000-8000-000000000406");

    /** 平台 DEK 标识；测试密文只使用该标识，不含真实密钥。 */
    private static final String DEK_KEY_ID = "dek-legacy";

    /** 历史调用条数。 */
    private static final int HISTORICAL_INVOCATIONS = 10_000;

    /** 历史调用第一条的 ID，用于尝试表外键与应用层成本快照测试。 */
    private static final byte[] FIRST_INVOCATION_ID = historicalInvocationId(1);

    /** 正向 schema 的 JDBC 客户端。 */
    private static JdbcClient forward;

    /** 回滚 schema 的 JDBC 客户端（root）。 */
    private static JdbcClient rollback;

    /** fresh install schema 的 JDBC 客户端（root）。 */
    private static JdbcClient fresh;

    /** 正向 schema 的 Flyway URL。 */
    private static String forwardUrl;

    /** 回滚 schema 的 Flyway URL。 */
    private static String rollbackUrl;

    /** fresh install schema 的 Flyway URL。 */
    private static String freshUrl;

    /**
     * 启动一次性容器并准备三个 schema：正向 V10 种子→V11、回滚演练、fresh install。
     */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        freshUrl = jdbcUrl(FRESH_SCHEMA);
        createSchema(ROLLBACK_SCHEMA);
        createSchema(FRESH_SCHEMA);

        // 正向：V1–V10 -> M02 种子（1 ACTIVE + 1 RETIRED 跨 provider）与 1 万历史调用 -> V11 -> 多 key。
        migrate(forwardUrl, USERNAME, "10");
        forward = jdbcClient(forwardUrl, USERNAME);
        seedV10Data(forward);
        seedHistoricalInvocations(forward, TENANT, MODEL_EARLY, HISTORICAL_INVOCATIONS);
        migrate(forwardUrl, USERNAME, "11");
        seedMultiKeyCredentials(forward);

        // 回滚：相同路径到 V11 + 多 key，再执行 V11 回滚收敛与结构删除。
        migrate(rollbackUrl, "root", "10");
        rollback = jdbcClient(rollbackUrl, "root");
        seedV10Data(rollback);
        seedHistoricalInvocations(rollback, TENANT, MODEL_EARLY, HISTORICAL_INVOCATIONS);
        migrate(rollbackUrl, "root", "11");
        seedMultiKeyCredentials(rollback);
        rollbackV11Objects(rollback);

        // fresh install：空库直接应用 V1–V14，验证当前最新 schema 的权限字典覆盖与种子可用。
        // 空库始终代表“新装系统”，必须跑最新迁移；V11 自身的增量仍由 forward/rollback 两个 schema 覆盖。
        migrate(freshUrl, "root", "14");
        fresh = jdbcClient(freshUrl, "root");
    }

    /** V11 在 V10 种子数据上由 Flyway 成功应用，history 记录成功版本 11。 */
    @Test
    @DisplayName("V11 在 V10 种子数据上应用成功")
    void migration_appliesOnV10SeedData() {
        assertThat(flyway(forwardUrl, USERNAME).info().current().getVersion().getVersion())
                .isEqualTo("11");
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                         WHERE success = 1 AND version = '11'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(count(forward, "SELECT COUNT(*) FROM model_invocation")).isEqualTo(HISTORICAL_INVOCATIONS);
    }

    /** V11 为 CHAT/REPORT 各建一条链，并按 created_at, id 选中最早的 ACTIVE 模型。 */
    @Test
    @DisplayName("V11 预置 CHAT/REPORT 路由链并保留 M02 单模型行为")
    void migration_seedsRoutingChainsWithEarliestActiveModel() {
        assertThat(forward.sql("SELECT purpose FROM model_routing ORDER BY purpose")
                        .query(String.class)
                        .list())
                .containsExactly("CHAT", "REPORT");

        List<Map.Entry<String, byte[]>> entries = forward.sql("""
                        SELECT purpose, model_id FROM model_routing_entry ORDER BY purpose
                        """)
                .query((rs, rowNum) -> Map.entry(rs.getString("purpose"), rs.getBytes("model_id")))
                .list();
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .allSatisfy(entry -> assertThat(entry.getValue()).isEqualTo(BinaryUuids.toBytes(MODEL_EARLY)));
        assertThat(entries.stream().map(Map.Entry::getKey)).containsExactly("CHAT", "REPORT");
        assertThat(forward.sql("""
                        SELECT position FROM model_routing_entry WHERE purpose = 'CHAT'
                        """).query(Integer.class).single()).isEqualTo(1);
    }

    /** 开关种子必须为 ROUTED/true/false，且版本从 1 开始。 */
    @Test
    @DisplayName("V11 预置网关开关种子")
    void migration_seedsGatewaySwitches() {
        Map<String, String> switches = switchValues(forward);
        assertThat(switches)
                .containsEntry("routingMode", "ROUTED")
                .containsEntry("failoverEnabled", "true")
                .containsEntry("quotaEnforcementEnabled", "false");
        assertThat(forward.sql("SELECT COUNT(*) FROM model_gateway_switch WHERE version = 1")
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    /** provider_credential 完成多 key 改造：RETIRED→DISABLED、fingerprint 回填、唯一键替换。 */
    @Test
    @DisplayName("V11 将 provider_credential 改为平台托管 key 池")
    void migration_upgradesProviderCredentialToKeyPool() {
        assertThat(credentialStatus(forward, CREDENTIAL_A)).isEqualTo("ACTIVE");
        assertThat(credentialStatus(forward, CREDENTIAL_B)).isEqualTo("DISABLED");
        assertThat(credentialStatus(forward, CREDENTIAL_C1)).isEqualTo("ACTIVE");
        assertThat(credentialStatus(forward, CREDENTIAL_C2)).isEqualTo("ACTIVE");
        assertThat(credentialStatus(forward, CREDENTIAL_C3)).isEqualTo("INVALID");
        assertThat(credentialStatus(forward, CREDENTIAL_B_INVALID)).isEqualTo("INVALID");

        Map<String, Object> legacy = forward.sql("""
                        SELECT label, masked_hint, fingerprint, priority, consecutive_failures
                          FROM provider_credential WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(CREDENTIAL_A))
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "label", rs.getString("label"),
                        "maskedHint", rs.getString("masked_hint"),
                        "fingerprint", rs.getString("fingerprint"),
                        "priority", rs.getInt("priority"),
                        "failures", rs.getInt("consecutive_failures")))
                .single();
        assertThat(legacy.get("label")).isEqualTo("默认");
        assertThat(legacy.get("maskedHint")).isEqualTo("****");
        assertThat((String) legacy.get("fingerprint")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(legacy.get("priority")).isEqualTo(100);
        assertThat(legacy.get("failures")).isEqualTo(0);

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "provider_credential", "uk_provider_credential_fingerprint"))
                .containsExactly("tenant_id", "provider_id", "credential_type", "fingerprint");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "provider_credential", "idx_provider_credential_pool"))
                .containsExactly("tenant_id", "provider_id", "credential_type", "status", "priority", "created_at");
        assertThat(indexExists(forward, FORWARD_SCHEMA, "provider_credential", "uk_provider_credential"))
                .isFalse();

        Map<String, String> checks = checkClauses(forward, FORWARD_SCHEMA, "provider_credential");
        assertThat(checks)
                .containsKeys(
                        "chk_provider_credential_status",
                        "chk_provider_credential_priority",
                        "chk_provider_credential_failures");
        assertThat(checks.get("chk_provider_credential_status"))
                .contains("ACTIVE")
                .contains("DISABLED")
                .contains("INVALID")
                .doesNotContain("RETIRED");
        assertThat(checks.get("chk_provider_credential_priority")).contains("priority");
        assertThat(checks.get("chk_provider_credential_failures")).contains("consecutive_failures");
    }

    /** 多 key 允许同一 provider 多行；fingerprint 重复由新唯一键拒绝。 */
    @Test
    @DisplayName("V11 允许同 provider 多 key 并拒绝重复 fingerprint")
    void migration_enforcesFingerprintUniqueness() {
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM provider_credential
                         WHERE tenant_id = 'platform' AND provider_id = :providerId AND credential_type = 'API_KEY'
                        """)
                        .param("providerId", BinaryUuids.toBytes(PROVIDER_C))
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
        assertRejected(
                "uk_provider_credential_fingerprint",
                () -> insertPoolCredential(
                        forward,
                        UUID.randomUUID(),
                        PROVIDER_C,
                        "ACTIVE",
                        "1".repeat(64),
                        "duplicate",
                        10,
                        "2026-03-10 00:00:00"));
    }

    /** model_invocation 新增列全部可空/有默认，历史行不猜测价格与日桶。 */
    @Test
    @DisplayName("V11 扩展 model_invocation 且历史调用保持 USAGE_UNKNOWN")
    void migration_extendsInvocationWithCostSnapshotColumns() {
        List<String> columns = columnList(
                forward,
                FORWARD_SCHEMA,
                "model_invocation",
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table ORDER BY ORDINAL_POSITION");
        assertThat(columns)
                .containsExactly(
                        "tenant_id",
                        "id",
                        "run_id",
                        "conversation_id",
                        "message_id",
                        "model_id",
                        "model_key_snapshot",
                        "purpose",
                        "status",
                        "first_token_ms",
                        "duration_ms",
                        "prompt_tokens",
                        "completion_tokens",
                        "error_code",
                        "trace_id",
                        "started_at",
                        "finished_at",
                        "created_at",
                        "provider_id",
                        "user_id",
                        "cost_day",
                        "input_price_per_1m",
                        "output_price_per_1m",
                        "price_currency",
                        "pricing_version",
                        "cost_amount",
                        "cost_status",
                        "attempt_count");

        ColumnInfo costStatus = columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "cost_status");
        assertThat(costStatus.type()).isEqualTo("varchar(16)");
        assertThat(costStatus.nullable()).isEqualTo("NO");
        assertThat(costStatus.defaultValue()).isEqualTo("USAGE_UNKNOWN");
        ColumnInfo attemptCount = columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "attempt_count");
        assertThat(attemptCount.type()).isEqualTo("int");
        assertThat(attemptCount.nullable()).isEqualTo("NO");
        assertThat(attemptCount.defaultValue()).isEqualTo("1");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "cost_amount")
                        .type())
                .isEqualTo("decimal(20,6)");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "input_price_per_1m")
                        .type())
                .isEqualTo("decimal(12,6)");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "output_price_per_1m")
                        .type())
                .isEqualTo("decimal(12,6)");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "price_currency")
                        .type())
                .isEqualTo("char(3)");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "cost_day")
                        .type())
                .isEqualTo("date");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "model_invocation", "provider_id")
                        .type())
                .isEqualTo("binary(16)");

        assertThat(count(forward, "SELECT COUNT(*) FROM model_invocation WHERE cost_status = 'USAGE_UNKNOWN'"))
                .isEqualTo(HISTORICAL_INVOCATIONS);
        assertThat(count(forward, "SELECT COUNT(*) FROM model_invocation WHERE attempt_count = 1"))
                .isEqualTo(HISTORICAL_INVOCATIONS);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM model_invocation
                         WHERE cost_day IS NULL AND cost_amount IS NULL AND provider_id IS NULL
                        """)).isEqualTo(HISTORICAL_INVOCATIONS);

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation", "idx_model_invocation_tenant_time"))
                .containsExactly("tenant_id", "started_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation", "idx_model_invocation_user_time"))
                .containsExactly("tenant_id", "user_id", "started_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation", "idx_model_invocation_cost_day"))
                .containsExactly("tenant_id", "cost_day");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation", "idx_model_invocation_provider"))
                .containsExactly("provider_id");
    }

    /** 大表约束边界：不建 provider 外键、不加新 CHECK，避免 COPY 阻塞写。 */
    @Test
    @DisplayName("V11 不为 model_invocation 建 provider 外键或新增 CHECK")
    void migration_keepsInvocationWithoutProviderForeignKeyOrNewChecks() {
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'model_invocation'
                           AND CONSTRAINT_NAME = 'fk_model_invocation_provider'
                        """, "schema", FORWARD_SCHEMA)).isZero();
        assertThat(checkClauses(forward, FORWARD_SCHEMA, "model_invocation"))
                .containsOnlyKeys(
                        "chk_model_invocation_tenant",
                        "chk_model_invocation_purpose",
                        "chk_model_invocation_status",
                        "chk_model_invocation_tokens");
    }

    /** 尝试表结构、主键顺序、外键与 CHECK 与 ADR-0021 §3.3 一致。 */
    @Test
    @DisplayName("V11 创建 model_invocation_attempt 并约束状态与序号")
    void migration_createsInvocationAttemptTable() {
        List<String> columns = columnList(
                forward,
                FORWARD_SCHEMA,
                "model_invocation_attempt",
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table ORDER BY ORDINAL_POSITION");
        assertThat(columns)
                .containsExactly(
                        "tenant_id",
                        "invocation_id",
                        "attempt_no",
                        "model_id",
                        "provider_id",
                        "credential_id",
                        "status",
                        "error_code",
                        "first_token_ms",
                        "started_at",
                        "finished_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation_attempt", "PRIMARY"))
                .containsExactly("tenant_id", "invocation_id", "attempt_no");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "model_invocation_attempt", "idx_model_attempt_model"))
                .containsExactly("tenant_id", "model_id", "started_at");

        Map<String, String> checks = checkClauses(forward, FORWARD_SCHEMA, "model_invocation_attempt");
        assertThat(checks).containsKeys("chk_model_attempt_status", "chk_model_attempt_no");
        assertThat(checks.get("chk_model_attempt_status"))
                .contains("STARTED")
                .contains("SUCCEEDED")
                .contains("FAILED")
                .contains("CANCELLED");
        assertThat(checks.get("chk_model_attempt_no")).contains("attempt_no");

        assertThat(foreignKeyColumns(
                        forward, FORWARD_SCHEMA, "model_invocation_attempt", "fk_model_attempt_invocation"))
                .containsExactly("tenant_id->tenant_id", "invocation_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "model_invocation_attempt", "fk_model_attempt_model"))
                .containsExactly("model_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "model_invocation_attempt", "fk_model_attempt_provider"))
                .containsExactly("provider_id->id");

        // 合法尝试可在事务内写入并回滚，证明空表 INSERT/ROLLBACK 与外键可用。
        inTransaction(
                forward, tx -> insertAttempt(tx, TENANT, FIRST_INVOCATION_ID, 1, MODEL_EARLY, PROVIDER_A, "STARTED"));
        assertThat(count(forward, "SELECT COUNT(*) FROM model_invocation_attempt"))
                .isZero();

        assertRejected(
                "chk_model_attempt_status",
                () -> insertAttempt(forward, TENANT, FIRST_INVOCATION_ID, 2, MODEL_EARLY, PROVIDER_A, "BROKEN"));
        assertRejected(
                "chk_model_attempt_no",
                () -> insertAttempt(forward, TENANT, FIRST_INVOCATION_ID, 0, MODEL_EARLY, PROVIDER_A, "STARTED"));
        assertRejected(
                "fk_model_attempt_invocation",
                () -> insertAttempt(
                        forward, TENANT, uuidBytes(UUID.randomUUID()), 1, MODEL_EARLY, PROVIDER_A, "STARTED"));
        assertRejected(
                "fk_model_attempt_provider",
                () -> insertAttempt(
                        forward, TENANT, FIRST_INVOCATION_ID, 1, MODEL_EARLY, UUID.randomUUID(), "STARTED"));
    }

    /** 配额表结构、主键顺序、默认值与 B6 的 upsert/条件 UPDATE 形状一致。 */
    @Test
    @DisplayName("V11 创建 quota_limit/quota_usage 并支持原子准入形状")
    void migration_createsQuotaTablesForAtomicAdmission() {
        List<String> limitColumns = columnList(
                forward,
                FORWARD_SCHEMA,
                "quota_limit",
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table ORDER BY ORDINAL_POSITION");
        assertThat(limitColumns)
                .containsExactly(
                        "tenant_id",
                        "scope_type",
                        "scope_id",
                        "user_id",
                        "window_kind",
                        "limit_count",
                        "updated_by",
                        "updated_at");
        List<String> usageColumns = columnList(
                forward,
                FORWARD_SCHEMA,
                "quota_usage",
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table ORDER BY ORDINAL_POSITION");
        assertThat(usageColumns)
                .containsExactly(
                        "tenant_id",
                        "scope_type",
                        "scope_id",
                        "window_kind",
                        "window_start",
                        "used_count",
                        "updated_at");

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "quota_limit", "PRIMARY"))
                .containsExactly("tenant_id", "scope_type", "scope_id", "window_kind");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "quota_usage", "PRIMARY"))
                .containsExactly("tenant_id", "scope_type", "scope_id", "window_kind", "window_start");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "quota_usage", "idx_quota_usage_cleanup"))
                .containsExactly("window_start");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "quota_limit", "idx_quota_limit_user"))
                .containsExactly("tenant_id", "user_id");

        ColumnInfo usedCount = columnInfo(forward, FORWARD_SCHEMA, "quota_usage", "used_count");
        assertThat(usedCount.type()).isEqualTo("int");
        assertThat(usedCount.nullable()).isEqualTo("NO");
        assertThat(usedCount.defaultValue()).isEqualTo("0");

        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "quota_limit", "fk_quota_limit_tenant"))
                .containsExactly("tenant_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "quota_limit", "fk_quota_limit_user"))
                .containsExactly("tenant_id->tenant_id", "user_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "quota_usage", "fk_quota_usage_tenant"))
                .containsExactly("tenant_id->id");

        Map<String, String> limitChecks = checkClauses(forward, FORWARD_SCHEMA, "quota_limit");
        assertThat(limitChecks)
                .containsKeys(
                        "chk_quota_limit_scope",
                        "chk_quota_limit_window",
                        "chk_quota_limit_scope_user",
                        "chk_quota_limit_count");
        Map<String, String> usageChecks = checkClauses(forward, FORWARD_SCHEMA, "quota_usage");
        assertThat(usageChecks)
                .containsKeys("chk_quota_usage_scope", "chk_quota_usage_window", "chk_quota_usage_count");
        assertThat(usageChecks.get("chk_quota_usage_count")).contains("used_count");

        // B6 准入形状：先 ON DUPLICATE 取行锁，再条件 +1；剩余 0 时 affectedRows=0。
        inTransaction(forward, tx -> {
            tx.sql("""
                            INSERT INTO tenant (id, name, status, plan, retention_days)
                            VALUES ('quota-shape', 'Quota Shape', 'ACTIVE', 'standard', 180)
                            """).update();
            tx.sql("""
                            INSERT INTO quota_limit (
                                tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                            ) VALUES ('quota-shape', 'TENANT', 'quota-shape', NULL, 'DAILY', 1)
                            """).update();
            tx.sql("""
                            INSERT INTO quota_usage (
                                tenant_id, scope_type, scope_id, window_kind, window_start, used_count
                            ) VALUES ('quota-shape', 'TENANT', 'quota-shape', 'DAILY', '2026-11-30 16:00:00', 0)
                            ON DUPLICATE KEY UPDATE used_count = used_count
                            """).update();
            tx.sql("""
                            INSERT INTO quota_usage (
                                tenant_id, scope_type, scope_id, window_kind, window_start, used_count
                            ) VALUES ('quota-shape', 'TENANT', 'quota-shape', 'DAILY', '2026-11-30 16:00:00', 0)
                            ON DUPLICATE KEY UPDATE used_count = used_count
                            """).update();
            int admitted = tx.sql("""
                            UPDATE quota_usage SET used_count = used_count + 1
                             WHERE tenant_id = 'quota-shape' AND scope_type = 'TENANT'
                               AND scope_id = 'quota-shape' AND window_kind = 'DAILY'
                               AND window_start = '2026-11-30 16:00:00'
                               AND used_count < 1
                            """).update();
            int rejected = tx.sql("""
                            UPDATE quota_usage SET used_count = used_count + 1
                             WHERE tenant_id = 'quota-shape' AND scope_type = 'TENANT'
                               AND scope_id = 'quota-shape' AND window_kind = 'DAILY'
                               AND window_start = '2026-11-30 16:00:00'
                               AND used_count < 1
                            """).update();
            assertThat(admitted).isEqualTo(1);
            assertThat(rejected).isZero();
            assertThat(tx.sql("""
                            SELECT used_count FROM quota_usage
                             WHERE tenant_id = 'quota-shape' AND scope_type = 'TENANT'
                               AND scope_id = 'quota-shape' AND window_kind = 'DAILY'
                               AND window_start = '2026-11-30 16:00:00'
                            """).query(Integer.class).single()).isEqualTo(1);
        });
        assertThat(count(forward, "SELECT COUNT(*) FROM tenant WHERE id = 'quota-shape'"))
                .isZero();
        assertThat(count(forward, "SELECT COUNT(*) FROM quota_usage")).isZero();
        assertThat(count(forward, "SELECT COUNT(*) FROM quota_limit")).isZero();
    }

    /** 配额 CHECK 与复合外键拒绝非法维度、负数与跨租户用户。 */
    @Test
    @DisplayName("V11 拒绝非法配额维度与跨租户用户")
    void migration_enforcesQuotaConstraints() {
        assertRejected(
                "chk_quota_limit_scope",
                () -> forward.sql("""
                                INSERT INTO quota_limit (
                                    tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                                ) VALUES (:tenantId, 'NOPE', :tenantId, NULL, 'DAILY', 10)
                                """).param("tenantId", TENANT).update());
        assertRejected("chk_quota_limit_window", () -> insertTenantQuotaLimit(forward, TENANT, "WEEKLY", 10));
        assertRejected("chk_quota_limit_count", () -> insertTenantQuotaLimit(forward, TENANT, "DAILY", -1));
        assertRejected(
                "chk_quota_limit_scope_user",
                () -> forward.sql("""
                                INSERT INTO quota_limit (
                                    tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                                ) VALUES (:tenantId, 'TENANT', :tenantId, :userId, 'DAILY', 10)
                                """)
                        .param("tenantId", TENANT)
                        .param("userId", BinaryUuids.toBytes(MEMBER_USER))
                        .update());
        assertRejected(
                "chk_quota_limit_scope_user",
                () -> forward.sql("""
                                INSERT INTO quota_limit (
                                    tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                                ) VALUES (:tenantId, 'USER', :tenantId, NULL, 'DAILY', 10)
                                """).param("tenantId", TENANT).update());
        assertRejected(
                "chk_quota_usage_scope",
                () -> insertQuotaUsage(forward, TENANT, "NOPE", "DAILY", "2026-11-30 16:00:00"));
        assertRejected(
                "chk_quota_usage_window",
                () -> insertQuotaUsage(forward, TENANT, "TENANT", "WEEKLY", "2026-11-30 16:00:00"));
        assertRejected(
                "chk_quota_usage_count",
                () -> forward.sql("""
                                INSERT INTO quota_usage (
                                    tenant_id, scope_type, scope_id, window_kind, window_start, used_count
                                ) VALUES (:tenantId, 'TENANT', :tenantId, 'DAILY', '2026-11-30 16:00:00', -1)
                                """).param("tenantId", TENANT).update());
        assertRejected(
                "fk_quota_limit_user",
                () -> forward.sql("""
                                INSERT INTO quota_limit (
                                    tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                                ) VALUES ('platform', 'USER', :scopeId, :userId, 'DAILY', 10)
                                """)
                        .param("scopeId", MEMBER_USER.toString())
                        .param("userId", BinaryUuids.toBytes(MEMBER_USER))
                        .update());
        assertRejected(
                "fk_quota_usage_tenant",
                () -> insertQuotaUsage(forward, "missing-tenant", "TENANT", "DAILY", "2026-11-30 16:00:00"));
    }

    /** 空表事务写入后回滚，不留下配额/尝试/成本残留。 */
    @Test
    @DisplayName("V11 空表支持事务 INSERT 与 ROLLBACK")
    void migration_emptyTablesAcceptInsertAndRollback() {
        inTransaction(forward, tx -> {
            insertTenantQuotaLimit(tx, TENANT, "DAILY", 10);
            insertQuotaUsage(tx, TENANT, "TENANT", "DAILY", "2026-11-30 16:00:00");
            insertAttempt(tx, TENANT, FIRST_INVOCATION_ID, 1, MODEL_EARLY, PROVIDER_A, "STARTED");
            tx.sql("""
                            UPDATE model_invocation
                               SET provider_id = :providerId,
                                   user_id = :userId,
                                   cost_day = '2026-11-20',
                                   input_price_per_1m = 3.000000,
                                   output_price_per_1m = 6.000000,
                                   price_currency = 'CNY',
                                   pricing_version = 'v1',
                                   cost_amount = 0.003000,
                                   cost_status = 'PRICED',
                                   attempt_count = 1
                             WHERE tenant_id = :tenantId AND id = :invocationId
                            """)
                    .param("providerId", BinaryUuids.toBytes(PROVIDER_A))
                    .param("userId", BinaryUuids.toBytes(MEMBER_USER))
                    .param("tenantId", TENANT)
                    .param("invocationId", FIRST_INVOCATION_ID)
                    .update();
            assertThat(count(tx, "SELECT COUNT(*) FROM quota_limit")).isEqualTo(1);
            assertThat(count(tx, "SELECT COUNT(*) FROM quota_usage")).isEqualTo(1);
            assertThat(count(tx, "SELECT COUNT(*) FROM model_invocation_attempt"))
                    .isEqualTo(1);
        });
        assertThat(count(forward, "SELECT COUNT(*) FROM quota_limit")).isZero();
        assertThat(count(forward, "SELECT COUNT(*) FROM quota_usage")).isZero();
        assertThat(count(forward, "SELECT COUNT(*) FROM model_invocation_attempt"))
                .isZero();
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM model_invocation
                         WHERE tenant_id = :tenantId AND id = :invocationId AND cost_status = 'USAGE_UNKNOWN'
                        """, "tenantId", TENANT, "invocationId", FIRST_INVOCATION_ID))
                .isEqualTo(1L);
    }

    /** 加列必须 INSTANT、索引必须 INPLACE/LOCK=NONE；provider_credential 顺序不可调换。 */
    @Test
    @DisplayName("V11 DDL 使用 INSTANT/INPLACE 且 provider_credential 顺序正确")
    void migration_ddlUsesInstantAndInplaceAlgorithms() throws Exception {
        String sql = Files.readString(migrationDirectory().resolve("V11__model_gateway_depth.sql"))
                .lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));

        String[] providerParts = sql.split("ALTER TABLE provider_credential", -1);
        assertThat(providerParts).hasSize(5);
        assertThat(providerParts[1])
                .contains("ADD COLUMN label")
                .contains("ADD COLUMN masked_hint")
                .contains("ADD COLUMN fingerprint")
                .contains("ALGORITHM = INSTANT");

        int addColumns = sql.indexOf("ALTER TABLE provider_credential");
        int dropCheck = sql.indexOf("DROP CHECK chk_provider_credential_status");
        int retire = sql.indexOf("SET status = 'DISABLED' WHERE status = 'RETIRED'");
        int newUnique = sql.indexOf("ADD UNIQUE KEY uk_provider_credential_fingerprint");
        int dropOldUnique = sql.indexOf("DROP INDEX uk_provider_credential;");
        assertThat(addColumns).isLessThan(dropCheck);
        assertThat(dropCheck).isLessThan(retire);
        assertThat(retire).isLessThan(newUnique);
        assertThat(newUnique).isLessThan(dropOldUnique);

        String[] invocationParts = sql.split("ALTER TABLE model_invocation", -1);
        assertThat(invocationParts).hasSize(6);
        assertThat(invocationParts[1])
                .contains("ADD COLUMN provider_id")
                .contains("ADD COLUMN cost_status")
                .contains("ALGORITHM = INSTANT")
                .doesNotContain("CHECK (")
                .doesNotContain("CONSTRAINT");
        assertThat(invocationParts[2])
                .contains("idx_model_invocation_tenant_time")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE");
        assertThat(invocationParts[3])
                .contains("idx_model_invocation_user_time")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE");
        assertThat(invocationParts[4])
                .contains("idx_model_invocation_cost_day")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE");
        assertThat(invocationParts[5])
                .contains("idx_model_invocation_provider")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE");

        assertThat(sql).doesNotContain("ADD CONSTRAINT fk_model_invocation_provider");
        assertThat(sql).doesNotContain("ALTER TABLE model_invocation ADD CONSTRAINT");
    }

    /** 权限字典与既有角色回填：租户管理员只读配额/成本，平台管理员得三项平台权限，员工不变。 */
    @Test
    @DisplayName("V11 回填 M04 权限码与角色映射")
    void migration_backfillsM04Permissions() {
        for (String code : List.of(
                PermissionCodes.PLATFORM_MODEL_MANAGE,
                PermissionCodes.QUOTA_READ,
                PermissionCodes.PLATFORM_QUOTA_MANAGE,
                PermissionCodes.COST_READ,
                PermissionCodes.PLATFORM_COST_READ)) {
            assertThat(permissionCount(forward, code)).as(code).isEqualTo(1L);
        }

        assertThat(rolePermissionCount(forward, TENANT, TENANT_ADMIN_ROLE, PermissionCodes.QUOTA_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, TENANT, TENANT_ADMIN_ROLE, PermissionCodes.COST_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, TENANT, TENANT_ADMIN_ROLE, PermissionCodes.PLATFORM_MODEL_MANAGE))
                .isZero();
        assertThat(rolePermissionCount(forward, TENANT, MEMBER_ROLE, PermissionCodes.QUOTA_READ))
                .isZero();
        assertThat(rolePermissionCount(forward, TENANT, MEMBER_ROLE, PermissionCodes.COST_READ))
                .isZero();
        assertThat(rolePermissionCount(
                        forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_MODEL_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_QUOTA_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_COST_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.QUOTA_READ))
                .isZero();
    }

    /** fresh install：V1–V14 后 RoleDefinitions 的每条权限都能在字典中找到并用于角色创建。 */
    @Test
    @DisplayName("空库 V1–V14 后 RoleDefinitions 覆盖 M04 权限")
    void freshDatabase_roleDefinitionsCoverM04Permissions() {
        assertThat(flyway(freshUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("14");
        assertThat(fresh.sql("SELECT purpose FROM model_routing ORDER BY purpose")
                        .query(String.class)
                        .list())
                .containsExactly("CHAT", "REPORT");
        assertThat(switchValues(fresh))
                .containsEntry("routingMode", "ROUTED")
                .containsEntry("failoverEnabled", "true")
                .containsEntry("quotaEnforcementEnabled", "false");
        assertThat(count(fresh, "SELECT COUNT(*) FROM model_routing_entry")).isZero();

        insertTenant(fresh, "freshco");
        insertRoleWithPermissions(fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, RoleDefinitions.platformAdminRole());
        for (RoleDefinition definition : RoleDefinitions.tenantSystemRoles()) {
            // WORKFLOW_EDITOR 由 V15 引入；本测试 fresh schema 的目标是 V14，不创建该角色。
            if (RoleCodes.WORKFLOW_EDITOR.equals(definition.code())) {
                continue;
            }
            insertRoleWithPermissions(fresh, "freshco", roleIdFor(definition.code()), definition);
        }

        assertThat(rolePermissionCount(
                        fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_MODEL_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_QUOTA_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_COST_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, "freshco", TENANT_ADMIN_ROLE, PermissionCodes.QUOTA_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, "freshco", TENANT_ADMIN_ROLE, PermissionCodes.COST_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, "freshco", MEMBER_ROLE, PermissionCodes.QUOTA_READ))
                .isZero();
    }

    /** fresh install 的权限字典必须覆盖 PermissionCodes 的全部常量，且无多余/重复行。 */
    @Test
    @DisplayName("空库 V1–V14 的 permission 字典与 PermissionCodes 全集一一对应")
    void freshDatabase_permissionDictionaryCoversAllPermissionCodes() {
        List<String> seeded = fresh.sql("SELECT code FROM permission ORDER BY code")
                .query(String.class)
                .list();
        List<String> expected = permissionCodeConstants();
        assertThat(seeded).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 读取 PermissionCodes 接口的全部 public static final String 常量。 */
    private static List<String> permissionCodeConstants() {
        List<String> codes = new ArrayList<>();
        for (Field field : PermissionCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    codes.add((String) field.get(null));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(
                            "cannot read PermissionCodes constant: " + field.getName(), exception);
                }
            }
        }
        return codes.stream()
                // V15 新增的 workflow.* 不在 V14 字典；V15MigrationTest 断言完整字典。
                .filter(code -> !code.startsWith("workflow."))
                .toList();
    }

    /** 多 key 回滚：每组 1 行、有 ACTIVE 保留 ACTIVE 否则 RETIRED，结构回到 V10。 */
    @Test
    @DisplayName("V11 多 key 回滚收敛到每组 1 行并回到 V10")
    void rollback_convergesMultiKeyAndReturnsToV10() {
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM (
                            SELECT tenant_id, provider_id, credential_type
                              FROM provider_credential
                             GROUP BY tenant_id, provider_id, credential_type
                            HAVING COUNT(*) <> 1
                        ) grouped
                        """)).isZero();
        assertThat(credentialStatus(rollback, CREDENTIAL_A)).isEqualTo("ACTIVE");
        assertThat(credentialStatus(rollback, CREDENTIAL_C1)).isEqualTo("ACTIVE");
        assertThat(credentialStatus(rollback, CREDENTIAL_B)).isEqualTo("RETIRED");

        assertThat(indexColumns(rollback, ROLLBACK_SCHEMA, "provider_credential", "uk_provider_credential"))
                .containsExactly("tenant_id", "provider_id", "credential_type");
        assertThat(indexExists(rollback, ROLLBACK_SCHEMA, "provider_credential", "uk_provider_credential_fingerprint"))
                .isFalse();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'provider_credential'
                           AND COLUMN_NAME IN ('label', 'fingerprint', 'masked_hint', 'priority',
                                               'consecutive_failures', 'cooldown_until', 'disabled_at',
                                               'disabled_by', 'invalidated_at', 'last_used_at')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        Map<String, String> statusCheck = checkClauses(rollback, ROLLBACK_SCHEMA, "provider_credential");
        assertThat(statusCheck.get("chk_provider_credential_status"))
                .contains("ACTIVE")
                .contains("RETIRED")
                .doesNotContain("INVALID");

        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = :schema
                           AND TABLE_NAME IN ('model_routing', 'model_routing_entry', 'model_gateway_switch',
                                              'model_invocation_attempt', 'quota_limit', 'quota_usage')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'model_invocation'
                           AND COLUMN_NAME IN ('provider_id', 'user_id', 'cost_day', 'input_price_per_1m',
                                               'output_price_per_1m', 'price_currency', 'pricing_version',
                                               'cost_amount', 'cost_status', 'attempt_count')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'model_invocation'
                           AND INDEX_NAME IN ('idx_model_invocation_tenant_time', 'idx_model_invocation_user_time',
                                              'idx_model_invocation_cost_day', 'idx_model_invocation_provider')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM permission
                         WHERE code IN ('platform.model.manage', 'quota.read', 'platform.quota.manage',
                                        'cost.read', 'platform.cost.read')
                        """)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM role_permission
                         WHERE permission_code IN ('platform.model.manage', 'quota.read', 'platform.quota.manage',
                                                   'cost.read', 'platform.cost.read')
                        """)).isZero();

        assertThat(count(rollback, "SELECT COUNT(*) FROM model_invocation")).isEqualTo(HISTORICAL_INVOCATIONS);
        assertThat(count(rollback, "SELECT COUNT(*) FROM app_user WHERE tenant_id = :tenantId", "tenantId", TENANT))
                .isEqualTo(2L);

        // 维护账号删除 V11 历史行后，schema 版本回到 10；不使用 flyway repair。
        rollback.sql("DELETE FROM flyway_schema_history WHERE version = '11'").update();
        assertThat(count(rollback, "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history"))
                .isEqualTo(10L);
        Flyway latest = flyway(rollbackUrl, "root");
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(latest.info().pending())
                .extracting(info -> info.getVersion().getVersion())
                .contains("11");
    }

    /** 构造正向/回滚共用的 V10 数据：1 ACTIVE + 1 RETIRED 跨 provider，旧唯一键仍生效。 */
    private static void seedV10Data(JdbcClient jdbc) {
        insertTenant(jdbc, TENANT);
        insertUser(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_USER, "platform-admin", "Platform Admin");
        insertUser(jdbc, TENANT, TENANT_ADMIN_USER, "acme-admin", "Acme Admin");
        insertUser(jdbc, TENANT, MEMBER_USER, "acme-member", "Acme Member");

        insertRole(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "PLATFORM_ADMIN");
        insertRole(jdbc, TENANT, TENANT_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT, MEMBER_ROLE, "MEMBER");
        insertRolePermission(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "platform.tenant.manage");
        insertRolePermission(jdbc, TENANT, TENANT_ADMIN_ROLE, "workspace.read");

        insertProvider(jdbc, PROVIDER_A, "vendor-a", "https://a.example/v1");
        insertProvider(jdbc, PROVIDER_B, "vendor-b", "https://b.example/v1");
        insertModel(jdbc, MODEL_EARLY, PROVIDER_A, "early-model", "2026-01-01 00:00:00");
        insertModel(jdbc, MODEL_LATE, PROVIDER_A, "late-model", "2026-02-01 00:00:00");
        insertModel(jdbc, MODEL_B, PROVIDER_B, "vendor-b-model", "2026-01-15 00:00:00");

        insertTenantDataKey(jdbc);
        insertV10Credential(jdbc, CREDENTIAL_A, PROVIDER_A, "ACTIVE", "2026-01-01 00:00:00");
        insertV10Credential(jdbc, CREDENTIAL_B, PROVIDER_B, "RETIRED", "2026-01-02 00:00:00");
    }

    /** V11 之后插入多 key：C 组 2 ACTIVE + 1 INVALID，B 组补 1 INVALID（无 ACTIVE）。 */
    private static void seedMultiKeyCredentials(JdbcClient jdbc) {
        insertProvider(jdbc, PROVIDER_C, "vendor-c", "https://c.example/v1");
        insertPoolCredential(
                jdbc, CREDENTIAL_C1, PROVIDER_C, "ACTIVE", "1".repeat(64), "primary", 10, "2026-03-01 00:00:00");
        insertPoolCredential(
                jdbc, CREDENTIAL_C2, PROVIDER_C, "ACTIVE", "2".repeat(64), "secondary", 20, "2026-03-02 00:00:00");
        insertPoolCredential(
                jdbc, CREDENTIAL_C3, PROVIDER_C, "INVALID", "3".repeat(64), "broken", 30, "2026-03-03 00:00:00");
        insertPoolCredential(
                jdbc,
                CREDENTIAL_B_INVALID,
                PROVIDER_B,
                "INVALID",
                "4".repeat(64),
                "broken-b",
                30,
                "2026-03-04 00:00:00");
    }

    /** 执行 V11 回滚：多 key 收敛 → 删表/索引/约束/列 → 删权限 → 删 history。 */
    private static void rollbackV11Objects(JdbcClient jdbc) {
        // 每组只保留 1 行：ACTIVE > DISABLED > INVALID；同状态按 created_at, id 稳定选择。
        jdbc.sql("""
                        DELETE c FROM provider_credential c
                        JOIN (
                            SELECT tenant_id, id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY tenant_id, provider_id, credential_type
                                       ORDER BY CASE status
                                                    WHEN 'ACTIVE' THEN 1
                                                    WHEN 'DISABLED' THEN 2
                                                    ELSE 3
                                                END,
                                                created_at, id
                                   ) AS row_no
                              FROM provider_credential
                        ) ranked ON ranked.tenant_id = c.tenant_id AND ranked.id = c.id
                        WHERE ranked.row_no > 1
                        """).update();
        // 旧枚举只有 ACTIVE|RETIRED；必须先删除 V11 的新 CHECK，再转换状态，否则 ERROR 3819。
        jdbc.sql("""
                        ALTER TABLE provider_credential
                            DROP INDEX uk_provider_credential_fingerprint,
                            DROP INDEX idx_provider_credential_pool
                        """).update();
        jdbc.sql("""
                        ALTER TABLE provider_credential
                            DROP CHECK chk_provider_credential_status,
                            DROP CHECK chk_provider_credential_priority,
                            DROP CHECK chk_provider_credential_failures
                        """).update();
        jdbc.sql("UPDATE provider_credential SET status = 'RETIRED' WHERE status IN ('DISABLED', 'INVALID')")
                .update();

        List<String> statements = List.of(
                "ALTER TABLE provider_credential MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'"
                        + " COMMENT 'ACTIVE|RETIRED'",
                "ALTER TABLE provider_credential ADD UNIQUE KEY uk_provider_credential"
                        + " (tenant_id, provider_id, credential_type)",
                "ALTER TABLE provider_credential ADD CONSTRAINT chk_provider_credential_status"
                        + " CHECK (status IN ('ACTIVE', 'RETIRED'))",
                "ALTER TABLE provider_credential DROP COLUMN label,"
                        + " DROP COLUMN fingerprint,"
                        + " DROP COLUMN masked_hint,"
                        + " DROP COLUMN priority,"
                        + " DROP COLUMN consecutive_failures,"
                        + " DROP COLUMN cooldown_until,"
                        + " DROP COLUMN disabled_at,"
                        + " DROP COLUMN disabled_by,"
                        + " DROP COLUMN invalidated_at,"
                        + " DROP COLUMN last_used_at",
                "DROP TABLE model_invocation_attempt",
                "DROP TABLE quota_usage",
                "DROP TABLE quota_limit",
                "DROP TABLE model_routing_entry",
                "DROP TABLE model_routing",
                "DROP TABLE model_gateway_switch",
                "ALTER TABLE model_invocation DROP INDEX idx_model_invocation_tenant_time,"
                        + " DROP INDEX idx_model_invocation_user_time,"
                        + " DROP INDEX idx_model_invocation_cost_day,"
                        + " DROP INDEX idx_model_invocation_provider",
                "ALTER TABLE model_invocation DROP COLUMN provider_id,"
                        + " DROP COLUMN user_id,"
                        + " DROP COLUMN cost_day,"
                        + " DROP COLUMN input_price_per_1m,"
                        + " DROP COLUMN output_price_per_1m,"
                        + " DROP COLUMN price_currency,"
                        + " DROP COLUMN pricing_version,"
                        + " DROP COLUMN cost_amount,"
                        + " DROP COLUMN cost_status,"
                        + " DROP COLUMN attempt_count",
                "DELETE FROM role_permission WHERE permission_code IN ('platform.model.manage', 'quota.read',"
                        + " 'platform.quota.manage', 'cost.read', 'platform.cost.read')",
                "DELETE FROM permission WHERE code IN ('platform.model.manage', 'quota.read',"
                        + " 'platform.quota.manage', 'cost.read', 'platform.cost.read')");
        for (String statement : statements) {
            jdbc.sql(statement).update();
        }
    }

    /** 用 4 位十进制笛卡尔积生成 1 万条历史调用，避免逐行往返。 */
    private static void seedHistoricalInvocations(JdbcClient jdbc, String tenant, UUID modelId, int count) {
        String digits = "(SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
                + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
                + " UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String numbers = "SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + 1 AS n"
                + " FROM " + digits + " ones"
                + " CROSS JOIN " + digits + " tens"
                + " CROSS JOIN " + digits + " hundreds"
                + " CROSS JOIN " + digits + " thousands";
        jdbc.sql("""
                        INSERT INTO model_invocation (
                            tenant_id, id, model_id, model_key_snapshot, purpose, status, trace_id,
                            started_at, created_at
                        )
                        SELECT :tenantId,
                               UNHEX(LPAD(HEX(seq.n), 32, '0')),
                               :modelId,
                               'early-model',
                               'CHAT',
                               'SUCCEEDED',
                               CONCAT('legacy-trace-', seq.n),
                               DATE_ADD('2026-11-01 00:00:00', INTERVAL seq.n SECOND),
                               NOW(6)
                          FROM (%s) seq
                         WHERE seq.n <= :count
                        """.formatted(numbers))
                .param("tenantId", tenant)
                .param("modelId", BinaryUuids.toBytes(modelId))
                .param("count", count)
                .update();
    }

    /** 事务包裹执行，结束回滚；用于证明空表可写且不污染共享 schema。 */
    private static void inTransaction(JdbcClient jdbc, TransactionWork work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrlForSchema(jdbc), USERNAME, PASSWORD)) {
            connection.setAutoCommit(false);
            // 必须用同一连接构造 JdbcClient，否则 JdbcClient 会另开连接，rollback 看不到写入。
            JdbcClient transactional = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            work.run(transactional);
            connection.rollback();
        } catch (Exception exception) {
            throw new IllegalStateException("transaction test failed", exception);
        }
    }

    /** 事务内工作。 */
    @FunctionalInterface
    private interface TransactionWork {
        /**
         * 执行事务内断言/写入。
         *
         * @param jdbc 与事务同一连接的 JdbcClient
         * @throws Exception 任意失败
         */
        void run(JdbcClient jdbc) throws Exception;
    }

    /** 执行给定目标版本的 Flyway 迁移。 */
    private static void migrate(String url, String user, String target) {
        flyway(url, user, target).migrate();
    }

    /** 构造指定目标版本的 Flyway。 */
    private static Flyway flyway(String url, String user, String target) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    /** 构造不限制目标的 Flyway。 */
    private static Flyway flyway(String url, String user) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .load();
    }

    /** 定位权威迁移目录，避免测试复制一份会漂移的 SQL。 */
    private static Path migrationDirectory() {
        Path moduleRelative = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative.toAbsolutePath().normalize();
        }
        Path repoRelative = Path.of("apps/api/src/main/resources/db/migration");
        if (Files.isDirectory(repoRelative)) {
            return repoRelative.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("cannot locate apps/api/db/migration for migration tests");
    }

    /** 使用 root 创建演练 schema；Testcontainers 只创建正向业务 schema。 */
    private static void createSchema(String schema) {
        String serverUrl = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        try (Connection connection = DriverManager.getConnection(serverUrl, "root", PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE IF NOT EXISTS " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create schema " + schema, exception);
        }
    }

    /** 拼装带测试连接参数的 JDBC URL。 */
    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /** 由 JdbcClient 反查 schema 名称并拼装 URL；仅测试内部使用。 */
    private static String jdbcUrlForSchema(JdbcClient jdbc) {
        String schema = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        return jdbcUrl(schema);
    }

    /** 创建 JDBC 客户端；root 只用于测试自建 schema。 */
    private static JdbcClient jdbcClient(String url, String user) {
        return JdbcClient.create(new DriverManagerDataSource(url, user, PASSWORD));
    }

    /** 种一个租户；platform 由 V2 写入。 */
    private static void insertTenant(JdbcClient jdbc, String tenant) {
        if (PLATFORM_TENANT.equals(tenant)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", tenant).param("name", tenant).update();
    }

    /** 种一个账号。 */
    private static void insertUser(JdbcClient jdbc, String tenant, UUID userId, String username, String displayName) {
        jdbc.sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(userId))
                .param("username", username)
                .param("displayName", displayName)
                .update();
    }

    /** 种一个系统角色。 */
    private static void insertRole(JdbcClient jdbc, String tenant, UUID roleId, String code) {
        jdbc.sql("""
                        INSERT INTO role (tenant_id, id, code, name, is_system)
                        VALUES (:tenantId, :id, :code, :name, TRUE)
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(roleId))
                .param("code", code)
                .param("name", code)
                .update();
    }

    /** 种一条角色权限映射。 */
    private static void insertRolePermission(JdbcClient jdbc, String tenant, UUID roleId, String permissionCode) {
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_code)
                        VALUES (:tenantId, :roleId, :permissionCode)
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .param("permissionCode", permissionCode)
                .update();
    }

    /** 按 RoleDefinitions 创建角色与全部权限映射，模拟开通/引导路径。 */
    private static void insertRoleWithPermissions(
            JdbcClient jdbc, String tenant, UUID roleId, RoleDefinition definition) {
        insertRole(jdbc, tenant, roleId, definition.code());
        for (String permission : definition.permissions()) {
            // V15 新增的 workflow.* 不在 V14 权限字典中；V1–V14 的严格覆盖断言由 V15MigrationTest 承担。
            if (permissionExists(jdbc, permission)) {
                insertRolePermission(jdbc, tenant, roleId, permission);
            }
        }
    }

    /** 判断权限码是否已在本 schema 的字典中。 */
    private static boolean permissionExists(JdbcClient jdbc, String permissionCode) {
        return jdbc.sql("SELECT COUNT(*) FROM permission WHERE code = :code")
                        .param("code", permissionCode)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** 按角色码返回 fresh schema 使用的固定角色 ID。 */
    private static UUID roleIdFor(String code) {
        return switch (code) {
            case "TENANT_ADMIN" -> TENANT_ADMIN_ROLE;
            case "MEMBER" -> MEMBER_ROLE;
            default -> throw new IllegalArgumentException("unexpected tenant role code: " + code);
        };
    }

    /** 种一个供应商。 */
    private static void insertProvider(JdbcClient jdbc, UUID providerId, String code, String baseUrl) {
        jdbc.sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, :name, 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", BinaryUuids.toBytes(providerId))
                .param("code", code)
                .param("name", code)
                .param("baseUrl", baseUrl)
                .update();
    }

    /** 种一个模型；created_at 决定 V11 路由种子顺序。 */
    private static void insertModel(JdbcClient jdbc, UUID modelId, UUID providerId, String modelKey, String createdAt) {
        jdbc.sql("""
                        INSERT INTO model (
                            id, provider_id, model_key, display_name, context_window, max_output_tokens,
                            input_price_per_1m, output_price_per_1m, status, created_at, updated_at
                        ) VALUES (
                            :id, :providerId, :modelKey, :modelKey, 128000, 4096,
                            3.000000, 6.000000, 'ACTIVE', :createdAt, :createdAt
                        )
                        """)
                .param("id", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("modelKey", modelKey)
                .param("createdAt", createdAt)
                .update();
    }

    /** 种平台 DEK；测试密文只引用该行，不含真实密钥。 */
    private static void insertTenantDataKey(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO tenant_data_key (
                            tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id
                        ) VALUES ('platform', :keyId, 'model-credential', :wrappedDek, :wrapNonce,
                                  'AES-256-GCM', 'mk-1')
                        """)
                .param("keyId", DEK_KEY_ID)
                .param("wrappedDek", new byte[] {1, 2, 3, 4})
                .param("wrapNonce", new byte[] {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
                .update();
    }

    /** 种 V10 形态凭据（旧列、ACTIVE|RETIRED）。 */
    private static void insertV10Credential(
            JdbcClient jdbc, UUID credentialId, UUID providerId, String status, String createdAt) {
        jdbc.sql("""
                        INSERT INTO provider_credential (
                            tenant_id, id, provider_id, credential_type, ciphertext, key_id,
                            master_key_id, version, status, created_at, updated_at
                        ) VALUES (
                            'platform', :id, :providerId, 'API_KEY', 'enc:v1:dek-legacy:AAAA', :keyId,
                            'mk-1', 1, :status, :createdAt, :createdAt
                        )
                        """)
                .param("id", BinaryUuids.toBytes(credentialId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("keyId", DEK_KEY_ID)
                .param("status", status)
                .param("createdAt", createdAt)
                .update();
    }

    /** 种 V11 形态凭据（多 key 列）。 */
    private static void insertPoolCredential(
            JdbcClient jdbc,
            UUID credentialId,
            UUID providerId,
            String status,
            String fingerprint,
            String label,
            int priority,
            String createdAt) {
        jdbc.sql("""
                        INSERT INTO provider_credential (
                            tenant_id, id, provider_id, credential_type, ciphertext, key_id,
                            master_key_id, version, status, created_at, updated_at,
                            label, fingerprint, masked_hint, priority, consecutive_failures
                        ) VALUES (
                            'platform', :id, :providerId, 'API_KEY', 'enc:v1:dek-legacy:AAAA', :keyId,
                            'mk-1', 1, :status, :createdAt, :createdAt,
                            :label, :fingerprint, '****a1b2', :priority, 0
                        )
                        """)
                .param("id", BinaryUuids.toBytes(credentialId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("keyId", DEK_KEY_ID)
                .param("status", status)
                .param("label", label)
                .param("fingerprint", fingerprint)
                .param("priority", priority)
                .param("createdAt", createdAt)
                .update();
    }

    /** 种一条 TENANT 维度配额上限。 */
    private static void insertTenantQuotaLimit(JdbcClient jdbc, String tenant, String windowKind, int limit) {
        jdbc.sql("""
                        INSERT INTO quota_limit (
                            tenant_id, scope_type, scope_id, user_id, window_kind, limit_count
                        ) VALUES (:tenantId, 'TENANT', :tenantId, NULL, :windowKind, :limitCount)
                        """)
                .param("tenantId", tenant)
                .param("windowKind", windowKind)
                .param("limitCount", limit)
                .update();
    }

    /** 种一条配额用量。 */
    private static void insertQuotaUsage(
            JdbcClient jdbc, String tenant, String scopeType, String windowKind, String windowStart) {
        jdbc.sql("""
                        INSERT INTO quota_usage (
                            tenant_id, scope_type, scope_id, window_kind, window_start, used_count
                        ) VALUES (:tenantId, :scopeType, :tenantId, :windowKind, :windowStart, 0)
                        """)
                .param("tenantId", tenant)
                .param("scopeType", scopeType)
                .param("windowKind", windowKind)
                .param("windowStart", windowStart)
                .update();
    }

    /** 种一条尝试记录。 */
    private static void insertAttempt(
            JdbcClient jdbc,
            String tenant,
            byte[] invocationId,
            int attemptNo,
            UUID modelId,
            UUID providerId,
            String status) {
        jdbc.sql("""
                        INSERT INTO model_invocation_attempt (
                            tenant_id, invocation_id, attempt_no, model_id, provider_id,
                            credential_id, status, started_at
                        ) VALUES (
                            :tenantId, :invocationId, :attemptNo, :modelId, :providerId,
                            NULL, :status, NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("invocationId", invocationId)
                .param("attemptNo", attemptNo)
                .param("modelId", BinaryUuids.toBytes(modelId))
                .param("providerId", BinaryUuids.toBytes(providerId))
                .param("status", status)
                .update();
    }

    /** 读取凭据状态。 */
    private static String credentialStatus(JdbcClient jdbc, UUID credentialId) {
        return jdbc.sql("""
                        SELECT status FROM provider_credential WHERE tenant_id = 'platform' AND id = :id
                        """)
                .param("id", BinaryUuids.toBytes(credentialId))
                .query(String.class)
                .single();
    }

    /** 读取网关开关键值。 */
    private static Map<String, String> switchValues(JdbcClient jdbc) {
        return jdbc
                .sql("SELECT switch_key, switch_value FROM model_gateway_switch")
                .query((rs, rowNum) -> Map.entry(rs.getString("switch_key"), rs.getString("switch_value")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 读取表/索引的列顺序。 */
    private static List<String> indexColumns(JdbcClient jdbc, String schema, String table, String index) {
        return jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND INDEX_NAME = :index
                         ORDER BY SEQ_IN_INDEX
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("index", index)
                .query(String.class)
                .list();
    }

    /** 判断索引是否存在。 */
    private static boolean indexExists(JdbcClient jdbc, String schema, String table, String index) {
        return count(jdbc, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND INDEX_NAME = :index
                        """, "schema", schema, "table", table, "index", index) > 0;
    }

    /** 读取复合外键的 列->被引用列 对。 */
    private static List<String> foreignKeyColumns(JdbcClient jdbc, String schema, String table, String constraintName) {
        return jdbc.sql("""
                        SELECT CONCAT(COLUMN_NAME, '->', REFERENCED_COLUMN_NAME) AS pair
                          FROM information_schema.KEY_COLUMN_USAGE
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table
                           AND CONSTRAINT_NAME = :constraintName AND REFERENCED_TABLE_NAME IS NOT NULL
                         ORDER BY ORDINAL_POSITION
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("constraintName", constraintName)
                .query(String.class)
                .list();
    }

    /** 读取 CHECK 约束名与子句。 */
    private static Map<String, String> checkClauses(JdbcClient jdbc, String schema, String table) {
        return jdbc
                .sql("""
                        SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                          FROM information_schema.TABLE_CONSTRAINTS tc
                          JOIN information_schema.CHECK_CONSTRAINTS cc
                            ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                           AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                         WHERE tc.TABLE_SCHEMA = :schema
                           AND tc.TABLE_NAME = :table
                           AND tc.CONSTRAINT_TYPE = 'CHECK'
                        """)
                .param("schema", schema)
                .param("table", table)
                .query((rs, rowNum) -> Map.entry(rs.getString("CONSTRAINT_NAME"), rs.getString("CHECK_CLAUSE")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 读取列清单。 */
    private static List<String> columnList(JdbcClient jdbc, String schema, String table, String sql) {
        return jdbc.sql(sql)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .list();
    }

    /** 读取列类型/可空/默认值。 */
    private static ColumnInfo columnInfo(JdbcClient jdbc, String schema, String table, String column) {
        return jdbc.sql("""
                        SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                          FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND COLUMN_NAME = :column
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("column", column)
                .query((rs, rowNum) -> new ColumnInfo(
                        rs.getString("COLUMN_TYPE"), rs.getString("IS_NULLABLE"), rs.getString("COLUMN_DEFAULT")))
                .single();
    }

    /** 统计权限字典行数。 */
    private static long permissionCount(JdbcClient jdbc, String code) {
        return jdbc.sql("SELECT COUNT(*) FROM permission WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    /** 统计某个角色是否持有指定权限。 */
    private static long rolePermissionCount(JdbcClient jdbc, String tenant, UUID roleId, String permissionCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM role_permission
                         WHERE tenant_id = :tenantId AND role_id = :roleId AND permission_code = :permissionCode
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .param("permissionCode", permissionCode)
                .query(Long.class)
                .single();
    }

    /** 单值计数；可变参数按 名值对 绑定。 */
    private static long count(JdbcClient jdbc, String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (int index = 0; index < params.length; index += 2) {
            spec = spec.param((String) params[index], params[index + 1]);
        }
        return spec.query(Long.class).single();
    }

    /** 断言数据库以指定约束名拒绝写入；错误信息必须包含约束名，避免被其他约束误判为通过。 */
    private static void assertRejected(String constraintName, ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(DataAccessException.class).hasMessageContaining(constraintName);
    }

    /** 解析测试常量 UUID。 */
    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    /** 历史调用 ID：与 seed SQL 的 UNHEX(LPAD(HEX(n),32,'0')) 保持同一算法。 */
    private static byte[] historicalInvocationId(int sequence) {
        return uuidBytes(new UUID(0L, sequence));
    }

    /** UUID 转 BINARY(16)。 */
    private static byte[] uuidBytes(UUID value) {
        return BinaryUuids.toBytes(value);
    }

    /** 列信息。 */
    private record ColumnInfo(String type, String nullable, String defaultValue) {}
}
