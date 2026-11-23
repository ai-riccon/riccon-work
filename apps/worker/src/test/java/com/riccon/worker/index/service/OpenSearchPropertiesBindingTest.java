package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.config.OpenSearchProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 索引版本/前缀环境变量绑定测试（TASK-0193 配置修复）。
 *
 * <p>锁定两套键都真正生效、不出现“按 ADR 设置却静默跑 v1”的假绿：
 *
 * <ul>
 *   <li>ADR-0023 键 {@code RICCON_OPENSEARCH_INDEX_VERSION} / {@code RICCON_OPENSEARCH_INDEX_PREFIX}
 *       通过 application.yml 占位符解析；
 *   <li>Spring 的 {@code RICCON_WORKER_OPENSEARCH_INDEX_VERSION} /
 *       {@code RICCON_WORKER_OPENSEARCH_INDEX_PREFIX} 因环境变量优先级更高仍可覆盖；
 *   <li>都未设置时使用部署默认 {@code v1} / {@code riccon-kb}。
 * </ul>
 *
 * <p>测试把真实的 {@code application.yml} 作为最低优先级属性源加载，并用
 * {@link SystemEnvironmentPropertySource} 模拟环境变量；不启动完整 Worker 栈、不连外部服务。
 * 每个用例先移除宿主 JVM 的 {@code systemEnvironment}/{@code systemProperties}，避免 CI 或
 * M05 gate 导出的 {@code RICCON_OPENSEARCH_INDEX_VERSION} 等变量让“默认 v1”用例假失败
 * （REV-TASK-0169-001 F-009）。
 */
class OpenSearchPropertiesBindingTest {

    /** 只加载 application.yml + 配置绑定的最小上下文。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withInitializer(context -> {
                for (PropertySource<?> source : loadApplicationYaml()) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            });

    /** ADR 键设置 v2 时必须绑定为 v2，而不是静默 v1。 */
    @Test
    @DisplayName("RICCON_OPENSEARCH_INDEX_VERSION=v2 绑定为 v2")
    void adrIndexVersionKey_bindsToV2() {
        runner.withInitializer(isolateHostIndexEnvironment())
                .withInitializer(environmentWith(Map.of("RICCON_OPENSEARCH_INDEX_VERSION", "v2")))
                .run(context -> assertThat(
                                context.getBean(OpenSearchProperties.class).indexVersion())
                        .isEqualTo("v2"));
    }

    /** Spring 键仍可覆盖 ADR 键/默认值。 */
    @Test
    @DisplayName("RICCON_WORKER_OPENSEARCH_INDEX_VERSION=v2 仍可覆盖")
    void workerIndexVersionKey_stillOverrides() {
        runner.withInitializer(isolateHostIndexEnvironment())
                .withInitializer(environmentWith(Map.of("RICCON_WORKER_OPENSEARCH_INDEX_VERSION", "v2")))
                .run(context -> assertThat(
                                context.getBean(OpenSearchProperties.class).indexVersion())
                        .isEqualTo("v2"));
    }

    /** 宿主即使导出 v2/worker 键，未显式注入时仍使用 v1/riccon-kb 默认值。 */
    @Test
    @DisplayName("宿主 env 设 v2 时默认仍为 v1/riccon-kb")
    void defaults_areV1AndRicconKb() {
        runner.withInitializer(isolateHostIndexEnvironment()).run(context -> {
            OpenSearchProperties properties = context.getBean(OpenSearchProperties.class);
            assertThat(properties.indexVersion()).isEqualTo("v1");
            assertThat(properties.indexPrefix()).isEqualTo("riccon-kb");
        });
    }

    /** 脚本使用的 ADR 前缀键同样通过占位符生效。 */
    @Test
    @DisplayName("RICCON_OPENSEARCH_INDEX_PREFIX 绑定为自定义前缀")
    void adrIndexPrefixKey_bindsToPrefix() {
        runner.withInitializer(isolateHostIndexEnvironment())
                .withInitializer(environmentWith(Map.of("RICCON_OPENSEARCH_INDEX_PREFIX", "riccon-kb-adr")))
                .run(context -> assertThat(
                                context.getBean(OpenSearchProperties.class).indexPrefix())
                        .isEqualTo("riccon-kb-adr"));
    }

    /** Spring 前缀键仍可覆盖 ADR 键。 */
    @Test
    @DisplayName("RICCON_WORKER_OPENSEARCH_INDEX_PREFIX 仍可覆盖")
    void workerIndexPrefixKey_stillOverrides() {
        runner.withInitializer(isolateHostIndexEnvironment())
                .withInitializer(environmentWith(Map.of("RICCON_WORKER_OPENSEARCH_INDEX_PREFIX", "riccon-kb-spring")))
                .run(context -> assertThat(
                                context.getBean(OpenSearchProperties.class).indexPrefix())
                        .isEqualTo("riccon-kb-spring"));
    }

    /**
     * 加载真实的 application.yml（worker 主资源）。
     *
     * @return YAML 属性源
     */
    private static List<PropertySource<?>> loadApplicationYaml() {
        try {
            return new YamlPropertySourceLoader().load("worker-application", new ClassPathResource("application.yml"));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load worker application.yml", ex);
        }
    }

    /**
     * 移除宿主 JVM 的环境变量/系统属性属性源，隔离 `RICCON_*` 索引键。
     *
     * <p>只移除这两个标准属性源；`application.yml` 与测试显式注入的属性源保持不变，
     * 因此默认值用例仍能验证 YAML 的 `:v1` / `:riccon-kb` 回退。
     *
     * @return 上下文初始化器
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolateHostIndexEnvironment() {
        return context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.remove("systemEnvironment");
            sources.remove("systemProperties");
        };
    }

    /**
     * 模拟 Spring 环境变量属性源（优先级最高）。
     *
     * @param values 环境变量名值对
     * @return 上下文初始化器
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> environmentWith(
            Map<String, Object> values) {
        return context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-environment", values));
    }

    /** 测试用配置类。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenSearchProperties.class)
    static class BindingConfiguration {}
}
