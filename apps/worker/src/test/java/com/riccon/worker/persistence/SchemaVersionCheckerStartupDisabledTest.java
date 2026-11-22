package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.worker.WorkerIntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 真实 Worker 上下文在回滚窗口（validation=false）仍创建 checker 并输出固定 WARN。
 *
 * <p>使用独立属性键生成独立上下文；静态 appender 捕获启动日志。断言 WARN 恰好一次、
 * 不执行 schema.version.checked，防止回滚演练依赖的 RICCON-WORKER-002 再次失效。
 */
@TestPropertySource(
        properties = {"riccon.worker.schema.startup-probe=disabled", "riccon.worker.schema.validation-enabled=false"})
class SchemaVersionCheckerStartupDisabledTest extends WorkerIntegrationTestBase {

    /** 启动日志 appender；类初始化早于 Spring 上下文刷新。 */
    private static final ListAppender<ILoggingEvent> STARTUP_LOGS = new ListAppender<>();

    static {
        STARTUP_LOGS.start();
        logger().addAppender(STARTUP_LOGS);
    }

    /** 回滚窗口上下文中的校验器；Bean 必须存在。 */
    @Autowired
    private SchemaVersionChecker schemaVersionChecker;

    /** 关闭校验时输出一次固定 WARN，且不执行版本查询日志。 */
    @Test
    @DisplayName("回滚窗口启动输出固定 WARN RICCON-WORKER-002")
    void startup_validationDisabled_logsFixedWarningExactlyOnce() {
        assertThat(schemaVersionChecker).isNotNull();
        List<ILoggingEvent> warnings = STARTUP_LOGS.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event ->
                        event.getFormattedMessage().contains(SchemaVersionChecker.VALIDATION_DISABLED_WARN_CODE))
                .toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .contains("RICCON-WORKER-002")
                .contains("schema validation disabled");
        assertThat(STARTUP_LOGS.list)
                .filteredOn(event -> event.getFormattedMessage().contains("schema.version.checked"))
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
