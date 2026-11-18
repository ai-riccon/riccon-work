package com.riccon.worker.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地热测试栈（external 模式）连接参数与隔离校验。
 *
 * <p>默认行为完全不变：未设置 {@code RICCON_TEST_SERVICES} 时，Worker 集成测试继续使用
 * Testcontainers 启动一次性 MySQL/OpenSearch/RustFS，CI 与模块 gate 不受影响。只有显式设置
 * {@code RICCON_TEST_SERVICES=external} 且提供 {@code RICCON_TEST_SERVICES_NAMESPACE}
 * 时，才复用 {@code deploy/compose/docker-compose.test.yml} 启动的共享测试栈。
 *
 * <p>隔离约定：数据库名 {@code riccon_test_<namespace>_worker}、S3 桶名与 OpenSearch 索引前缀
 * 都由 namespace 派生。JVM 启动时通过 MySQL {@code GET_LOCK} 抢占命名锁；同名 namespace 已被
 * 另一个测试 JVM 占用时立即失败。每个 agent 必须使用自己的 namespace，且不得并行共用。
 *
 * <p>迁移/回滚测试（例如 {@code V9MigrationTest}、{@code SchemaVersionCheckerTest} 的 external
 * 分支）不依赖本类的热栈连接，始终使用一次性容器。
 */
public final class ExternalTestServices {

    /** 显式启用外部热栈的取值。 */
    private static final String MODE_EXTERNAL = "external";

    /** 外部模式必填：每个 agent 独立的命名空间。 */
    private static final String NAMESPACE_ENV = "RICCON_TEST_SERVICES_NAMESPACE";

    /** OpenSearch 索引列表中的 index 字段。 */
    private static final Pattern INDEX_FIELD = Pattern.compile("\"index\"\\s*:\\s*\"([^\"]+)\"");

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
        // 同时用于 MySQL 库名、S3 桶名、索引前缀与锁名，禁止首尾连字符与路径字符。
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
        return "riccon_test_" + namespace().replace('-', '_') + "_worker";
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
     * 返回外部 OpenSearch 端点。
     *
     * @return HTTP 端点
     */
    public static String openSearchUrl() {
        return env("RICCON_TEST_OPENSEARCH_URL", "http://127.0.0.1:19200");
    }

    /**
     * 返回 external 模式使用的索引前缀。
     *
     * <p>默认模式返回 {@code riccon-kb}（与 application.yml 一致）；external 模式返回
     * {@code riccon-kb-<namespace>-worker}，避免并行 agent 共用索引。
     *
     * @return 索引前缀
     */
    public static String indexPrefix() {
        return enabled() ? "riccon-kb-" + namespace() + "-worker" : "riccon-kb";
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
     * {@code riccon-test-<namespace>-worker}，避免并行 agent 共用对象。
     *
     * @return 桶名
     */
    public static String bucketName() {
        return enabled() ? "riccon-test-" + namespace() + "-worker" : "riccon-test";
    }

    /**
     * 校验数据库可用并抢占命名锁。
     *
     * <p>数据库必须由 {@code pnpm test-services prepare --namespace <namespace>} 预先创建；
     * 缺失、不可达或已被占用时以明确错误失败，不回落到 Testcontainers。
     */
    public static void verifyAndLock() {
        String lockName = "riccon_test_" + namespace().replace('-', '_') + "_worker";
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
     * 清空当前 namespace 的 OpenSearch 索引。
     *
     * <p>容器模式下索引随容器重建天然为空；热栈必须在每次 JVM 启动时删除 namespace 前缀下的
     * 旧索引，否则固定测试租户 ID 会读到上一次运行的数据，导致假失败。删除范围严格限制在
     * 当前 namespace 前缀内。
     */
    public static void purgeSearchIndices() {
        String baseUrl = openSearchUrl().replaceAll("/+$", "");
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String health = baseUrl + "/_cluster/health?wait_for_status=yellow&timeout=30s";
        HttpResponse<String> healthResponse = send(client, health, "GET");
        if (healthResponse.statusCode() != 200) {
            throw new IllegalStateException("external OpenSearch is not ready at " + baseUrl
                    + "; start the stack with: pnpm test-services start");
        }
        String prefix = indexPrefix() + "-";
        String listing = send(client, baseUrl + "/_cat/indices?format=json&h=index", "GET")
                .body();
        Matcher matcher = INDEX_FIELD.matcher(listing);
        List<String> stale = new ArrayList<>();
        while (matcher.find()) {
            String index = matcher.group(1);
            if (index.startsWith(prefix)) {
                stale.add(index);
            }
        }
        for (String index : stale) {
            HttpResponse<String> deleted = send(client, baseUrl + "/" + index, "DELETE");
            if (deleted.statusCode() != 200) {
                throw new IllegalStateException(
                        "failed to delete stale OpenSearch index " + index + ": HTTP " + deleted.statusCode());
            }
        }
    }

    /** 发送 HTTP 请求；连接或 IO 失败时转为明确错误，避免测试在错误环境上继续。 */
    private static HttpResponse<String> send(HttpClient client, String url, String method) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("external OpenSearch request failed: " + url, exception);
        }
    }

    /** 释放命名锁；JVM 退出时执行，失败只影响锁的及时释放。 */
    private static void releaseLock() {
        if (lockConnection == null) {
            return;
        }
        try (PreparedStatement statement = lockConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, "riccon_test_" + namespace().replace('-', '_') + "_worker");
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
