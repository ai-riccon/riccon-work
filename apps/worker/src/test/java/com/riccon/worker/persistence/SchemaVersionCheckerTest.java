package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.support.ExternalTestServices;
import com.riccon.worker.support.TestSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * schema 版本校验测试：缺少表、落后、超前、匹配四种情况。
 *
 * <p>该测试会创建/删除数据库，属于迁移类测试，绝不能连接 external 热栈：默认模式复用
 * {@link WorkerIntegrationTestBase} 的共享一次性容器以保持启动次数不变；external 模式必须
 * 启动自己的一次性容器。
 */
@Tag("migration")
class SchemaVersionCheckerTest {

    /** 测试数据库容器；external 模式为独立一次性容器。 */
    private static final MySQLContainer MYSQL = resolveMysql();

    /**
     * 解析 schema 版本测试使用的 MySQL 容器。
     *
     * @return 默认模式为共享容器，external 模式为独立一次性容器
     */
    private static MySQLContainer resolveMysql() {
        if (!ExternalTestServices.enabled()) {
            return WorkerIntegrationTestBase.sharedMysql();
        }
        MySQLContainer container = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
                .withDatabaseName("riccon")
                .withUsername("riccon")
                .withPassword("riccon_test_only")
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--log-bin-trust-function-creators=1");
        container.start();
        return container;
    }

    /** 匹配期望版本时返回实际版本。 */
    @Test
    void check_matchingVersion_passes() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(
                url(database), "root", MYSQL.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);

        assertThat(checker(database).check()).isEqualTo(SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
    }

    /** 缺少 flyway_schema_history 必须拒绝启动。 */
    @Test
    void check_missingHistoryTable_fails() {
        String database = createDatabase();

        assertThatThrownBy(() -> checker(database).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionChecker.ERROR_CODE)
                .hasMessageContaining("flyway_schema_history");
    }

    /** 版本落后必须拒绝启动，并输出 expected/actual。 */
    @Test
    void check_versionBehind_fails() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(
                url(database), "root", MYSQL.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION - 1);

        assertThatThrownBy(() -> checker(database).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=" + SchemaVersionChecker.EXPECTED_SCHEMA_VERSION)
                .hasMessageContaining("actual=" + (SchemaVersionChecker.EXPECTED_SCHEMA_VERSION - 1));
    }

    /** 版本超前同样拒绝，避免旧 Worker 使用新 schema。 */
    @Test
    void check_versionAhead_fails() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(
                url(database), "root", MYSQL.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION + 1);

        assertThatThrownBy(() -> checker(database).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actual=" + (SchemaVersionChecker.EXPECTED_SCHEMA_VERSION + 1));
    }

    /** 没有任何成功记录也必须拒绝。 */
    @Test
    void check_noSuccessfulMigration_fails() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(url(database), "root", MYSQL.getPassword(), 0);

        assertThatThrownBy(() -> checker(database).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no successful migration");
    }

    /**
     * 回滚窗口：校验关闭时输出一次固定 WARN，重复调用 run 不重复输出。
     */
    @Test
    void run_validationDisabled_logsFixedRollbackWarningExactlyOnce() {
        String database = createDatabase();
        Logger logger = workerSchemaLogger();
        ListAppender<ILoggingEvent> appender = attachAppender(logger);
        try {
            SchemaVersionChecker checker = checker(database, false);
            checker.run(new DefaultApplicationArguments(new String[0]));
            checker.run(new DefaultApplicationArguments(new String[0]));

            List<ILoggingEvent> warnings = rollbackWarnings(appender);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains(SchemaVersionChecker.VALIDATION_DISABLED_WARN_CODE)
                    .contains("RICCON-WORKER-002")
                    .contains("schema validation disabled");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 校验启用且版本匹配时执行正常校验，不输出回滚 WARN。 */
    @Test
    void run_validationEnabled_doesNotLogRollbackWarning() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(
                url(database), "root", MYSQL.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
        Logger logger = workerSchemaLogger();
        ListAppender<ILoggingEvent> appender = attachAppender(logger);
        try {
            SchemaVersionChecker checker = checker(database, true);
            checker.run(new DefaultApplicationArguments(new String[0]));

            assertThat(rollbackWarnings(appender)).isEmpty();
            assertThat(checker.check()).isEqualTo(SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 校验启用但版本不符时仍按原行为启动失败，且不输出回滚 WARN。 */
    @Test
    void run_validationFailure_doesNotLogRollbackWarning() {
        String database = createDatabase();
        Logger logger = workerSchemaLogger();
        ListAppender<ILoggingEvent> appender = attachAppender(logger);
        try {
            SchemaVersionChecker checker = checker(database, true);
            assertThatThrownBy(() -> checker.run(new DefaultApplicationArguments(new String[0])))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SchemaVersionChecker.ERROR_CODE);

            assertThat(rollbackWarnings(appender)).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 返回 Worker schema 校验器 logger。 */
    private static Logger workerSchemaLogger() {
        return (Logger) LoggerFactory.getLogger(SchemaVersionChecker.class);
    }

    /** 挂载并启动一个仅在当前用例生效的 logback appender。 */
    private static ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 过滤出固定回滚 WARN 事件。 */
    private static List<ILoggingEvent> rollbackWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event ->
                        event.getFormattedMessage().contains(SchemaVersionChecker.VALIDATION_DISABLED_WARN_CODE))
                .toList();
    }

    /**
     * 创建独立数据库，避免修改共享测试库的版本记录。
     *
     * @return 数据库名
     */
    private String createDatabase() {
        String name = "schema_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(rootUrl(), "root", MYSQL.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name + "`");
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to create schema test database", ex);
        }
        return name;
    }

    /**
     * 构造指向独立数据库的校验器。
     *
     * @param database 数据库名
     * @return 校验器
     */
    private SchemaVersionChecker checker(String database) {
        return checker(database, true);
    }

    /**
     * 构造指定校验开关的校验器。
     *
     * @param database 数据库名
     * @param validationEnabled 是否启用版本校验
     * @return 校验器
     */
    private SchemaVersionChecker checker(String database, boolean validationEnabled) {
        return new SchemaVersionChecker(
                JdbcClient.create(new DriverManagerDataSource(url(database), "root", MYSQL.getPassword())),
                validationEnabled);
    }

    /**
     * 返回独立数据库的 JDBC URL。
     *
     * @param database 数据库名
     * @return URL
     */
    private static String url(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    /**
     * 返回 root 连接 URL。
     *
     * @return URL
     */
    private static String rootUrl() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }
}
