package com.riccon.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.riccon.api.ApiApplication;
import com.riccon.api.config.CryptoProperties;
import com.riccon.platform.common.crypto.CredentialEnvelope;
import com.riccon.platform.common.crypto.MasterKeyConfigException;
import com.riccon.platform.common.crypto.MasterKeyRing;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

/**
 * API 加密启动校验与日志脱敏。
 *
 * <p>验证 ADR-0006 第 3 节与 ADR-0021 §6.5：主密钥/pepper 缺失、命中测试密钥或长度错误都让 API
 * 在接收流量前失败；显式 {@code test} profile 允许未注入密钥启动，但加密操作仍失败关闭。
 * 启动失败与日志都不得回显密钥原值。
 */
class CryptoStartupValidationTest {

    /** 测试主密钥（32 字节 0x2A，Base64；非生产）。 */
    private static final String MASTER_KEY = base64Of(32, (byte) 0x2A);

    /** 测试 pepper（32 字节 0x11，Base64；非生产）。 */
    private static final String PEPPER = base64Of(32, (byte) 0x11);

    /** 测试上一把主密钥（32 字节 0x22，Base64；非生产）。 */
    private static final String PREVIOUS_KEY = base64Of(32, (byte) 0x22);

    /** 已知测试密钥清单中的值；生产默认必须拒绝。 */
    private static final String KNOWN_TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** 生成固定填充的 Base64 值。 */
    private static String base64Of(int length, byte fill) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 构造配置；空串表示未注入。 */
    private static CryptoProperties properties(String masterKey, String pepper) {
        return new CryptoProperties(masterKey, "", "mk-test", true, pepper);
    }

    /** 默认环境（无 test profile）缺少主密钥必须启动失败。 */
    @Test
    void missingMasterKey_defaultProfile_failsStartup() {
        assertThatThrownBy(() -> new CryptoConfig().masterKeyProvider(properties("", PEPPER), new MockEnvironment()))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("RICCON_MASTER_KEY")
                .hasMessageNotContaining(MASTER_KEY)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.MASTER_KEY_MISSING);
    }

    /** 缺少 pepper 时默认环境同样拒绝启动，避免 key 池无法去重。 */
    @Test
    void missingPepper_defaultProfile_failsStartup() {
        assertThatThrownBy(
                        () -> new CryptoConfig().masterKeyProvider(properties(MASTER_KEY, ""), new MockEnvironment()))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("RICCON_CREDENTIAL_FINGERPRINT_PEPPER")
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.PEPPER_MISSING);
    }

    /** 非 test profile 误用已知测试密钥必须拒绝启动。 */
    @Test
    void knownTestKey_defaultProfile_failsStartup() {
        assertThatThrownBy(() ->
                        new CryptoConfig().masterKeyProvider(properties(KNOWN_TEST_KEY, PEPPER), new MockEnvironment()))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("known test key");
    }

    /** 合法配置创建可用 provider，并完成 canary 自检。 */
    @Test
    void validConfiguration_createsConfiguredProvider() {
        MasterKeyProvider provider =
                new CryptoConfig().masterKeyProvider(properties(MASTER_KEY, PEPPER), new MockEnvironment());

        assertThat(provider.configured()).isTrue();
        assertThat(provider.masterKeyId()).isEqualTo("mk-test");
        assertThat(provider.fingerprint("provider-1", "sk-test-0123456789abcdef"))
                .matches("[0-9a-f]{64}");
    }

    /** 轮换窗口：API 同时接受当前与上一把主密钥，旧 DEK 仍可解开。 */
    @Test
    void previousMasterKey_isWiredIntoProvider() {
        byte[] dek = new byte[32];
        Arrays.fill(dek, (byte) 3);
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing.WrappedDek wrapped =
                MasterKeyRing.load(PREVIOUS_KEY, "", "mk-old", PEPPER, false).wrap(dek, aad);

        MasterKeyProvider provider = new CryptoConfig()
                .masterKeyProvider(
                        new CryptoProperties(MASTER_KEY, PREVIOUS_KEY, "mk-test", true, PEPPER), new MockEnvironment());

        assertThat(provider.hasPreviousKey()).isTrue();
        assertThat(provider.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad)).isEqualTo(dek);
    }

    /** 环境变量优先于 yml 绑定值（部署合同以环境变量为准）。 */
    @Test
    void environmentOverridesBoundConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("RICCON_MASTER_KEY", MASTER_KEY);
        environment.setProperty("RICCON_CREDENTIAL_FINGERPRINT_PEPPER", PEPPER);

        MasterKeyProvider provider = new CryptoConfig()
                .masterKeyProvider(new CryptoProperties("c2hvcnQ=", "", "mk-test", true, ""), environment);

        assertThat(provider.configured()).isTrue();
    }

    /** test profile 允许未注入密钥启动，但 provider 必须失败关闭。 */
    @Test
    void missingMasterKey_testProfile_startsButFailsClosed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        MasterKeyProvider provider = new CryptoConfig().masterKeyProvider(properties("", ""), environment);

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(() -> provider.wrap(new byte[32], new byte[] {1}))
                .isInstanceOf(MasterKeyConfigException.class)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.NOT_CONFIGURED);
    }

    /** test profile 允许仓库内占位密钥，避免测试依赖部署注入。 */
    @Test
    void knownTestKey_testProfile_isAllowed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        MasterKeyProvider provider = new CryptoConfig().masterKeyProvider(properties(KNOWN_TEST_KEY, ""), environment);

        assertThat(provider.configured()).isTrue();
    }

    /**
     * 真实启动路径：缺少 pepper 时进程启动失败，根因只含字段说明，日志不含主密钥原值。
     *
     * <p>不连接数据库、不执行迁移，只验证 Spring 装配阶段；主密钥通过命令行注入后由我们的启动校验抛出。
     */
    @Test
    void startupFailure_neverLogsMasterKey() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            SpringApplication application = new SpringApplication(ApiApplication.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            String[] args = {
                "--server.port=0",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/riccon?useSSL=false&allowPublicKeyRetrieval=true",
                "--spring.datasource.username=none",
                "--spring.datasource.password=none",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--spring.flyway.enabled=false",
                "--spring.session.jdbc.initialize-schema=never",
                "--riccon.worker.client.service-token-secret=test-service-token-secret-0123456789abcdef",
                "--riccon.crypto.master-key=" + MASTER_KEY,
                "--riccon.crypto.fingerprint-pepper="
            };

            Throwable failure = catchThrowable(() -> application.run(args));

            assertThat(failure).as("缺少 pepper 时 API 必须启动失败").isNotNull();
            Throwable rootCause = failure;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }
            assertThat(rootCause).isInstanceOf(MasterKeyConfigException.class);
            assertThat(rootCause.getMessage())
                    .contains("RICCON_CREDENTIAL_FINGERPRINT_PEPPER")
                    .doesNotContain(MASTER_KEY);
            assertThat(appender.list.stream()
                            .map(CryptoStartupValidationTest::logText)
                            .toList())
                    .noneMatch(text -> text.contains(MASTER_KEY));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 把日志事件（消息 + 异常链）拼成可扫描文本。
     *
     * @param event 日志事件
     * @return 文本
     */
    private static String logText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        while (throwable != null) {
            text.append(' ').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
            throwable = throwable.getCause();
        }
        return text.toString();
    }
}
