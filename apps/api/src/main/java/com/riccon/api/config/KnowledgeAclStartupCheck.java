package com.riccon.api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 知识库 ACL 灰度启动检查与指标（ADR-0023 第 5.8 节）。
 *
 * <p>关闭强制时输出固定 WARN {@value #WARN_CODE}，并暴露
 * {@code riccon_kb_acl_enforcement_disabled=1}（Micrometer 名 {@value #METRIC_NAME}）。此时
 * {@code DEPT}/{@code USERS} 文档会按 {@code TENANT} 全租户可见，只允许用于非敏感测试文档；
 * M05 gate 必须以开启状态取证，不得用关闭模式判 pass。
 *
 * <p>本类只做可观察性，不参与授权判定；授权谓词由知识库访问层按同一配置读取布尔值。
 */
@Component
public class KnowledgeAclStartupCheck implements ApplicationRunner {

    /** 关闭 ACL 强制时的固定 WARN 码，供 RUNBOOK 与日志检索。 */
    public static final String WARN_CODE = "RICCON-KB-001";

    /** 指标名；Prometheus 暴露为 {@code riccon_kb_acl_enforcement_disabled}。 */
    public static final String METRIC_NAME = "riccon.kb.acl.enforcement.disabled";

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeAclStartupCheck.class);

    /** ACL 开关与上限。 */
    private final KnowledgeAclProperties properties;

    /** 指标注册表。 */
    private final MeterRegistry meterRegistry;

    /**
     * 创建启动检查。
     *
     * @param properties ACL 配置
     * @param meterRegistry 指标注册表
     */
    public KnowledgeAclStartupCheck(KnowledgeAclProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 注册指标并在关闭强制时输出固定 WARN。
     *
     * <p>指标值 1 表示未强制、0 表示已启用；不要在每次请求时重复注册或读取配置。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        Gauge.builder(METRIC_NAME, properties, value -> value.enforcementEnabled() ? 0d : 1d)
                .description("知识库文档级 ACL 是否关闭强制（1=关闭，DEPT/USERS 文档按 TENANT 可见）")
                .register(meterRegistry);
        if (!properties.enforcementEnabled()) {
            LOG.warn(
                    "{} knowledge acl enforcement disabled; DEPT/USERS documents are visible to all tenant members"
                            + " until RICCON_KB_ACL_ENFORCEMENT_ENABLED=true (ADR-0023 5.8)",
                    WARN_CODE);
        }
    }
}
