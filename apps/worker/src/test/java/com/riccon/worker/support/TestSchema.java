package com.riccon.worker.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 测试 schema 初始化。
 *
 * <p>Worker 模块不依赖 Flyway（ADR-0010），集成测试直接读取 {@code apps/api} 的权威迁移
 * 并通过 Spring {@link ScriptUtils} 执行，避免复制一份会漂移的表结构。随后写入
 * {@code flyway_schema_history}，让启动校验看到与生产一致的版本记录。
 */
public final class TestSchema {

    /** 工具类禁止实例化。 */
    private TestSchema() {}

    /**
     * 解析迁移目录；优先系统属性，其次相对 worker 模块的仓库布局。
     *
     * @return 迁移目录
     */
    public static Path migrationDirectory() {
        String configured = System.getProperty("riccon.test.migrations-dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path fromModule = Path.of("../api/src/main/resources/db/migration");
        if (Files.isDirectory(fromModule)) {
            return fromModule.toAbsolutePath().normalize();
        }
        Path fromRoot = Path.of("apps/api/src/main/resources/db/migration");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("cannot locate apps/api/db/migration for worker integration tests");
    }

    /**
     * 按版本顺序执行所有迁移。
     *
     * @param jdbcUrl 测试数据库 URL
     * @param username 用户名
     * @param password 密码
     */
    public static void applyMigrations(String jdbcUrl, String username, String password) {
        Path directory = migrationDirectory();
        List<Path> scripts;
        try (Stream<Path> files = Files.list(directory)) {
            scripts = files.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(TestSchema::versionOf))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to list migration scripts", ex);
        }
        if (scripts.isEmpty()) {
            throw new IllegalStateException("no migration scripts found in " + directory);
        }
        for (Path script : scripts) {
            try {
                // V1 基线只有注释，ScriptUtils 会把空脚本视为错误；注释-only 脚本直接跳过。
                String source = Files.readString(script, StandardCharsets.UTF_8);
                if (source.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                        .findAny()
                        .isEmpty()) {
                    continue;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("failed to read migration " + script, ex);
            }
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                // 迁移中的 DATETIME(6) 约定为 UTC；测试连接与生产保持一致。
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET time_zone = '+00:00'");
                }
                ScriptUtils.executeSqlScript(
                        connection,
                        new org.springframework.core.io.support.EncodedResource(
                                new FileSystemResource(script), StandardCharsets.UTF_8));
            } catch (SQLException ex) {
                throw new IllegalStateException("failed to apply migration " + script, ex);
            }
        }
    }

    /**
     * 创建 Flyway 元数据表并写入成功版本记录。
     *
     * @param jdbcUrl 测试数据库 URL
     * @param username 用户名
     * @param password 密码
     * @param maxVersion 最大成功版本
     */
    public static void createFlywayHistory(String jdbcUrl, String username, String password, int maxVersion) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
                statement.execute("""
                        CREATE TABLE flyway_schema_history (
                            installed_rank INT NOT NULL,
                            version VARCHAR(50),
                            description VARCHAR(200) NOT NULL,
                            type VARCHAR(20) NOT NULL,
                            script VARCHAR(1000) NOT NULL,
                            checksum INT,
                            installed_by VARCHAR(100) NOT NULL,
                            installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            execution_time INT NOT NULL,
                            success BOOLEAN NOT NULL,
                            PRIMARY KEY (installed_rank),
                            KEY flyway_schema_history_s_idx (success)
                        )
                        """);
            }
            insertVersions(connection, maxVersion);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to create flyway_schema_history", ex);
        }
    }

    /**
     * 写入指定版本范围的成功记录。
     *
     * @param connection 连接
     * @param maxVersion 最大版本
     * @throws SQLException 写入失败
     */
    public static void insertVersions(Connection connection, int maxVersion) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM flyway_schema_history");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum,
                     installed_by, installed_on, execution_time, success)
                VALUES (?, ?, ?, 'SQL', ?, NULL, 'test', CURRENT_TIMESTAMP, 0, TRUE)
                """)) {
            for (int version = 1; version <= maxVersion; version++) {
                statement.setInt(1, version);
                statement.setString(2, Integer.toString(version));
                statement.setString(3, "version " + version);
                statement.setString(4, "V" + version + "__test.sql");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 从文件名解析版本号。
     *
     * @param path 迁移文件
     * @return 版本号
     */
    private static int versionOf(Path path) {
        String name = path.getFileName().toString();
        int end = name.indexOf("__");
        return Integer.parseInt(name.substring(1, end));
    }
}
