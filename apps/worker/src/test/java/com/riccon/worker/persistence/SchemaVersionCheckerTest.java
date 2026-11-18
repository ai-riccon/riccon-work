package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.support.ExternalTestServices;
import com.riccon.worker.support.TestSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
        return new SchemaVersionChecker(
                JdbcClient.create(new DriverManagerDataSource(url(database), "root", MYSQL.getPassword())));
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
