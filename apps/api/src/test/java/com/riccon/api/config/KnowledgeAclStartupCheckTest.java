package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 知识库 ACL 启动检查的 WARN 与指标断言（M05-B1 F-013，ADR-0023 第 5.8 节）。
 *
 * <p>{@code enforcementEnabled=false} 时指标必须为 1 且输出固定 {@code RICCON-KB-001} WARN；
 * true 时指标必须为 0 且不得再输出该 WARN。测试直接调用 {@link KnowledgeAclStartupCheck#run}，
 * 用 {@link SimpleMeterRegistry} 与 Logback {@link ListAppender} 锁定可观察性契约。
 */
class KnowledgeAclStartupCheckTest {

    /** 关闭强制时 gauge=1 并输出固定 WARN。 */
    @Test
    @DisplayName("关闭 ACL 强制时 gauge=1 且有固定 WARN")
    void enforcementDisabled_registersGaugeOneAndWarns() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeAclStartupCheck.class);
        ListAppender<ILoggingEvent> logs = attach(logger);
        try {
            new KnowledgeAclStartupCheck(new KnowledgeAclProperties(false, 200), registry).run(null);

            assertThat(registry.get(KnowledgeAclStartupCheck.METRIC_NAME)
                            .gauge()
                            .value())
                    .as("关闭强制时指标值必须为 1")
                    .isEqualTo(1.0d);
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains(KnowledgeAclStartupCheck.WARN_CODE)
                        .contains("knowledge acl enforcement disabled")
                        .contains("DEPT/USERS");
            });
        } finally {
            detach(logger, logs);
        }
    }

    /** 开启强制时 gauge=0 且不再输出关闭 WARN。 */
    @Test
    @DisplayName("开启 ACL 强制时 gauge=0 且无 WARN")
    void enforcementEnabled_registersGaugeZeroWithoutWarn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeAclStartupCheck.class);
        ListAppender<ILoggingEvent> logs = attach(logger);
        try {
            new KnowledgeAclStartupCheck(new KnowledgeAclProperties(true, 200), registry).run(null);

            assertThat(registry.get(KnowledgeAclStartupCheck.METRIC_NAME)
                            .gauge()
                            .value())
                    .as("开启强制时指标值必须为 0")
                    .isZero();
            assertThat(logs.list)
                    .as("开启强制时不得输出 RICCON-KB-001")
                    .noneSatisfy(event ->
                            assertThat(event.getFormattedMessage()).contains(KnowledgeAclStartupCheck.WARN_CODE));
        } finally {
            detach(logger, logs);
        }
    }

    /**
     * 挂载日志收集器。
     *
     * @param logger 目标 logger
     * @return 收集器
     */
    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 卸载日志收集器。
     *
     * @param logger 目标 logger
     * @param appender 收集器
     */
    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }
}
