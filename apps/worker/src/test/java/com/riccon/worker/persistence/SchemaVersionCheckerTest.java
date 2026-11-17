package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.support.TestSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** schema 版本校验测试：缺少表、落后、超前、匹配四种情况。 */
class SchemaVersionCheckerTest extends WorkerIntegrationTestBase {

    /** 匹配期望版本时返回实际版本。 */
    @Test
    void check_matchingVersion_passes() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(
                url(database), "root", mysql.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION);

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
                url(database), "root", mysql.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION - 1);

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
                url(database), "root", mysql.getPassword(), SchemaVersionChecker.EXPECTED_SCHEMA_VERSION + 1);

        assertThatThrownBy(() -> checker(database).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actual=" + (SchemaVersionChecker.EXPECTED_SCHEMA_VERSION + 1));
    }

    /** 没有任何成功记录也必须拒绝。 */
    @Test
    void check_noSuccessfulMigration_fails() {
        String database = createDatabase();
        TestSchema.createFlywayHistory(url(database), "root", mysql.getPassword(), 0);

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
        try (Connection connection = DriverManager.getConnection(rootUrl(), "root", mysql.getPassword());
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
                JdbcClient.create(new DriverManagerDataSource(url(database), "root", mysql.getPassword())));
    }

    /**
     * 返回独立数据库的 JDBC URL。
     *
     * @param database 数据库名
     * @return URL
     */
    private static String url(String database) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    /**
     * 返回 root 连接 URL。
     *
     * @return URL
     */
    private static String rootUrl() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }
}
