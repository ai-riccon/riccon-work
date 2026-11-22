package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.worker.WorkerIntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 真实 Worker 上下文启动时 SchemaVersionChecker 必须被创建并执行版本校验（V2 F-002）。
 *
 * <p>使用独立属性键生成独立 Spring 上下文；静态 appender 在上下文刷新前挂到 checker logger，
 * 捕获 ApplicationRunner 启动日志。旧实现 {@code @ConditionalOnBean(JdbcClient.class)} 会让
 * checker Bean 缺失，autowire 直接失败，本测试因此能防止回归。
 */
@TestPropertySource(properties = "riccon.worker.schema.startup-probe=enabled")
class SchemaVersionCheckerStartupEnabledTest extends WorkerIntegrationTestBase {

    /** 启动日志 appender；类初始化早于 Spring 上下文刷新。 */
    private static final ListAppender<ILoggingEvent> STARTUP_LOGS = new ListAppender<>();

    static {
        STARTUP_LOGS.start();
        logger().addAppender(STARTUP_LOGS);
    }

    /** 真实上下文中的版本校验器。 */
    @Autowired
    private SchemaVersionChecker schemaVersionChecker;

    /** 启动时必须执行版本校验并记录 schema.version.checked，且不输出回滚 WARN。 */
    @Test
    @DisplayName("真实 Worker 启动执行 schema 版本校验")
    void startup_runsSchemaVersionCheck() {
        assertThat(schemaVersionChecker).isNotNull();
        assertThat(STARTUP_LOGS.list)
                .filteredOn(event -> event.getFormattedMessage().contains("schema.version.checked"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("expected=" + SchemaVersionChecker.EXPECTED_SCHEMA_VERSION)
                        .contains("actual=" + SchemaVersionChecker.EXPECTED_SCHEMA_VERSION));
        assertThat(STARTUP_LOGS.list)
                .filteredOn(event ->
                        event.getFormattedMessage().contains(SchemaVersionChecker.VALIDATION_DISABLED_WARN_CODE))
                .isEmpty();
    }

    /** 用例结束后移除 appender，避免污染其他上下文的日志断言。 */
    @AfterAll
    static void detachStartupLogs() {
        logger().detachAppender(STARTUP_LOGS);
        STARTUP_LOGS.stop();
    }

    /** 返回 checker 的 logback logger。 */
    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(SchemaVersionChecker.class);
    }
}
