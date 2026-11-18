package com.riccon.api.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地热测试栈（external 模式）连接参数与隔离校验。
 *
 * <p>默认行为完全不变：未设置 {@code RICCON_TEST_SERVICES} 时，API 集成测试继续使用
 * Testcontainers 启动一次性 MySQL/RustFS，CI 与模块 gate 不受影响。只有显式设置
 * {@code RICCON_TEST_SERVICES=external} 且提供 {@code RICCON_TEST_SERVICES_NAMESPACE}
 * 时，才复用 {@code deploy/compose/docker-compose.test.yml} 启动的共享测试栈（MySQL 13306、
 * OpenSearch 19200、RustFS 19000）。
 *
 * <p>隔离约定：数据库名由 namespace 派生（{@code riccon_test_<namespace>_api}），S3 桶名同样
 * 带 namespace。JVM 启动时通过 MySQL {@code GET_LOCK} 抢占命名锁；如果同一 namespace 已被
 * 另一个测试 JVM 占用，立即失败而不是静默共用数据库。每个 agent 必须使用自己的 namespace
 * （例如 {@code builder-0054}），且不得并行共用。
 *
 * <p>schema 版本约定：数据库版本不得高于代码内最新迁移；Flyway 对“数据库版本超前”只告警，
 * 这里在启动时显式失败，避免把测试跑在未来 schema 上。低于代码版本的库由 Flyway 正常前向
 * 迁移。迁移/回滚测试（例如 {@code V9MigrationTest}）不读取本类，始终使用一次性容器。
 */
public final class ExternalTestServices {

    /** 显式启用外部热栈的取值。 */
    private static final String MODE_EXTERNAL = "external";

    /** 外部模式必填：每个 agent 独立的命名空间。 */
    private static final String NAMESPACE_ENV = "RICCON_TEST_SERVICES_NAMESPACE";

    /** Flyway 迁移文件名。 */
    private static final Pattern MIGRATION_FILENAME = Pattern.compile("V(\\d+)__.*\\.sql");

    /** 命名锁连接；JVM 存活期间保持打开，连接断开时 MySQL 自动释放锁。 */
    private static Connection lockConnection;

    /** 工具类禁止实例化。 */
    private ExternalTestServices() {}

    /**
     * 判断是否启用 external 热栈。
     *
     * <p>取值拼写错误时直接失败，避免误以为是外部模式却悄悄回落到 Testcontainers。
     *
     * @return 启用时返回 {@code true}
     */
    public static boolean enabled() {
        String mode = System.getenv("RICCON_TEST_SERVICES");
        if (mode == null || mode.isBlank()) {
            return false;
        }
        if (MODE_EXTERNAL.equalsIgnoreCase(mode.trim())) {
            return true;
        }
        throw new IllegalStateException(
                "unsupported RICCON_TEST_SERVICES=" + mode + "; unset it or use '" + MODE_EXTERNAL + "'");
    }

    /**
     * 返回当前 agent 的命名空间。
     *
     * @return 通过校验的小写命名空间
     */
    public static String namespace() {
        String namespace = System.getenv(NAMESPACE_ENV);
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException(NAMESPACE_ENV + " is required when RICCON_TEST_SERVICES=external; "
                    + "each agent must use its own namespace (for example builder-0054)");
        }
        String trimmed = namespace.trim();
        // 同时用于 MySQL 库名、S3 桶名与锁名，禁止首尾连字符与路径字符。
        if (!trimmed.matches("[a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?")) {
            throw new IllegalStateException(
                    NAMESPACE_ENV + " must match [a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?: " + trimmed);
        }
        return trimmed;
    }

    /**
     * 返回当前模块在命名空间内的数据库名。
     *
     * @return 数据库名
     */
    public static String databaseName() {
        return "riccon_test_" + namespace().replace('-', '_') + "_api";
    }

    /**
     * 返回外部 MySQL 的 JDBC URL。
     *
     * @return JDBC URL
     */
    public static String jdbcUrl() {
        return "jdbc:mysql://" + env("RICCON_TEST_MYSQL_HOST", "127.0.0.1") + ":"
                + env("RICCON_TEST_MYSQL_PORT", "13306") + "/" + databaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /**
     * 返回外部 MySQL 用户名；compose 测试栈固定创建 riccon 用户，不支持覆盖。
     *
     * @return 用户名
     */
    public static String username() {
        return "riccon";
    }

    /**
     * 返回外部 MySQL 密码，与 compose 的 {@code RICCON_DB_PASSWORD} 保持同源。
     *
     * @return 密码
     */
    public static String password() {
        return env("RICCON_DB_PASSWORD", "riccon_test_only");
    }

    /**
     * 校验数据库可用并抢占命名锁。
     *
     * <p>数据库必须由 {@code pnpm test-services prepare --namespace <namespace>} 预先创建；
     * 缺失、不可达或已被占用时以明确错误失败，不回落到 Testcontainers。
     */
    public static void verifyAndLock() {
        String lockName = "riccon_test_" + namespace().replace('-', '_') + "_api";
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, lockName);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 1) {
                        connection.close();
                        throw new IllegalStateException("external test database " + databaseName()
                                + " is already in use by another test JVM; use a unique "
                                + NAMESPACE_ENV + " for each agent");
                    }
                }
            }
            verifySchemaNotAhead(connection);
            lockConnection = connection;
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(ExternalTestServices::releaseLock, "riccon-external-test-lock"));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "external test database " + databaseName() + " is unreachable; start and prepare the stack: "
                            + "pnpm test-services start && pnpm test-services prepare --namespace " + namespace(),
                    exception);
        }
    }

    /**
     * 校验数据库 schema 不超前于代码内最新迁移。
     *
     * <p>Flyway 默认只警告“数据库版本比本地迁移新”，不会阻止启动；热栈可能残留未来分支的
     * 库，这里显式失败并要求 reset。数据库表缺失（首次运行）或版本落后时放行，由 Flyway
     * 正常建库/前向迁移。
     *
     * @param connection 已连接的测试数据库
     * @throws SQLException 读取迁移历史失败
     */
    private static void verifySchemaNotAhead(Connection connection) throws SQLException {
        int latestLocal = latestLocalMigrationVersion();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet table = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'
                    """)) {
                table.next();
                if (table.getInt(1) == 0) {
                    return;
                }
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1
                    """)) {
                result.next();
                int applied = result.getInt(1);
                if (result.wasNull()) {
                    throw new IllegalStateException("external test schema has no successful migration; reset it with: "
                            + "pnpm test-services reset --namespace " + namespace());
                }
                if (applied > latestLocal) {
                    throw new IllegalStateException("external test schema version " + applied
                            + " is newer than the code's latest V" + latestLocal + "; reset it with: "
                            + "pnpm test-services reset --namespace " + namespace());
                }
            }
        }
    }

    /**
     * 读取代码内最新迁移版本。
     *
     * @return 最大迁移序号
     */
    private static int latestLocalMigrationVersion() {
        Path directory = Path.of("src/main/resources/db/migration");
        if (!Files.isDirectory(directory)) {
            directory = Path.of("apps/api/src/main/resources/db/migration");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException(
                    "cannot locate apps/api/db/migration to validate external test schema version");
        }
        Path scanDirectory = directory;
        try (Stream<Path> files = Files.list(scanDirectory)) {
            return files.map(path -> path.getFileName().toString())
                    .map(MIGRATION_FILENAME::matcher)
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElseThrow(() -> new IllegalStateException(
                            "no migration scripts found in " + scanDirectory.toAbsolutePath()));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to scan migration scripts in " + scanDirectory, exception);
        }
    }

    /**
     * 返回外部 RustFS 的 S3 端点。
     *
     * @return HTTP 端点
     */
    public static String storageEndpoint() {
        return env("RICCON_TEST_S3_ENDPOINT", "http://127.0.0.1:19000");
    }

    /**
     * 返回外部 RustFS 访问键；与 compose 的 {@code RICCON_S3_ACCESS_KEY} 同源。
     *
     * @return 访问键
     */
    public static String storageAccessKey() {
        return env("RICCON_S3_ACCESS_KEY", "riccon_test_only");
    }

    /**
     * 返回外部 RustFS 密钥；与 compose 的 {@code RICCON_S3_SECRET_KEY} 同源。
     *
     * @return 访问密钥
     */
    public static String storageSecretKey() {
        return env("RICCON_S3_SECRET_KEY", "riccon_test_only_secret");
    }

    /**
     * 返回当前模式使用的测试桶名。
     *
     * <p>默认模式沿用 {@code riccon-test}；external 模式为
     * {@code riccon-test-<namespace>-api}，避免并行 agent 共用对象。
     *
     * @return 桶名
     */
    public static String bucketName() {
        return enabled() ? "riccon-test-" + namespace() + "-api" : "riccon-test";
    }

    /** 释放命名锁；JVM 退出时执行，失败只影响锁的及时释放。 */
    private static void releaseLock() {
        if (lockConnection == null) {
            return;
        }
        try (PreparedStatement statement = lockConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, "riccon_test_" + namespace().replace('-', '_') + "_api");
            statement.execute();
        } catch (SQLException ignored) {
            // 连接已断开时 MySQL 会自行释放锁；退出钩子不再抛出异常。
        } finally {
            try {
                lockConnection.close();
            } catch (SQLException ignored) {
                // 关闭失败不影响进程退出。
            }
        }
    }

    /**
     * 读取环境变量，未设置或为空时返回默认值。
     *
     * @param name 变量名
     * @param fallback 默认值
     * @return 变量值
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
