package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.api.ApiApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * OPS-003：真实启动路径的配置失败脱敏。
 *
 * <p>{@code RICCON_OIDC_IDPS} 只以 String 承接、在应用内解析；本测试用完整 {@link SpringApplication} 启动
 * （不连数据库、不跑迁移），断言启动失败、根因消息只含字段路径/错误类型，日志与异常链均不含 clientSecret
 * 或原始 JSON 片段。
 */
class OidcStartupValidationTest {

    /** 哨兵密钥。 */
    private static final String SECRET = "super-secret-client-value-do-not-log";

    /** malformed JSON 中的原文片段。 */
    private static final String BROKEN_FRAGMENT = "BROKEN-JSON-FRAGMENT";

    /** malformed JSON 启动失败且不泄漏 secret。 */
    @Test
    void malformedIdps_failsStartupWithoutLeakingSecret() {
        String malformed = """
                [{"tenantCode":"acme","issuer":"https://idp.example","clientId":"riccon","clientSecret":"%s"} %s]
                """.formatted(SECRET, BROKEN_FRAGMENT);

        StartupResult result = startWith(malformed);

        assertThat(result.failure()).isNotNull();
        assertThat(result.rootCause()).isInstanceOf(IllegalStateException.class);
        assertThat(result.rootCause().getMessage())
                .contains("riccon.oidc.idps")
                .contains("is not valid JSON")
                .doesNotContain(SECRET)
                .doesNotContain(BROKEN_FRAGMENT);
        assertThat(result.logs())
                .noneMatch(text -> text.contains(SECRET))
                .noneMatch(text -> text.contains(BROKEN_FRAGMENT));
    }

    /** 缺 clientSecret 启动失败：只报告字段路径，不泄漏整段配置。 */
    @Test
    void missingClientSecret_failsStartupWithFieldPathOnly() {
        String json = """
                [{"tenantCode":"acme","issuer":"https://idp.example","clientId":"riccon","note":"%s"}]
                """.formatted(SECRET);

        StartupResult result = startWith(json);

        assertThat(result.failure()).isNotNull();
        assertThat(result.rootCause().getMessage())
                .contains("riccon.oidc.idps[0].clientSecret")
                .doesNotContain(SECRET);
        assertThat(result.logs()).noneMatch(text -> text.contains(SECRET));
    }

    /**
     * 使用完整应用上下文启动；预期在 OIDC 注册表初始化时失败。
     *
     * @param idps 原始配置 JSON
     * @return 启动结果
     */
    private static StartupResult startWith(String idps) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            SpringApplication application = new SpringApplication(ApiApplication.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            application.setAdditionalProfiles("test");
            // 命令行属性优先级高于 application-test.yml；不连接数据库、不执行迁移，只验证启动期配置解析路径。
            String[] args = {
                "--server.port=0",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/riccon?useSSL=false&allowPublicKeyRetrieval=true",
                "--spring.datasource.username=none",
                "--spring.datasource.password=none",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--spring.flyway.enabled=false",
                "--spring.session.jdbc.initialize-schema=never",
                "--riccon.oidc.allow-http=true",
                "--riccon.oidc.idps=" + idps
            };
            Throwable failure = catchThrowable(() -> application.run(args));
            Throwable rootCause = failure;
            while (rootCause != null && rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            return new StartupResult(
                    failure,
                    rootCause,
                    appender.list.stream()
                            .map(OidcStartupValidationTest::logText)
                            .toList());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 把日志事件（消息 + 异常链消息）拼成可扫描文本。
     *
     * @param event 日志事件
     * @return 文本
     */
    private static String logText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        while (throwable != null) {
            text.append('\n').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
            throwable = throwable.getCause();
        }
        return text.toString();
    }

    /**
     * 启动结果。
     *
     * @param failure 启动异常
     * @param rootCause 根因
     * @param logs 日志文本
     */
    private record StartupResult(Throwable failure, Throwable rootCause, List<String> logs) {}
}
