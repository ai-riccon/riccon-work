package com.riccon.worker.persistence;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Worker 启动时的 schema 版本校验。
 *
 * <p>迁移只由 {@code apps/api} 执行（ADR-0010）。Worker 不加载 Flyway，只查询
 * {@code flyway_schema_history} 的最大成功版本；表缺失、查询失败或版本不符都抛异常，
 * 让进程启动失败，绝不在错误 schema 上接收任务。
 *
 * <p>版本常量当前由 Worker 维护；TASK-0012 在 {@code platform-common} 建立
 * {@code SchemaVersion.EXPECTED} 后应切换为共享常量，避免两处漂移。
 *
 * <p>M10（OPS-002/004）：期望版本随 V10 升到 10。V10 只新增审计导出请求表、审计查询索引与权限映射，
 * Worker 不读取这些对象，但必须在 V9 之后、V10 之前保持版本严格校验，避免在未知 schema 上领取任务。
 * 因此发布只能协调切换，不能纯滚动：停旧 worker → 停 API 写 → 应用 V10 → 起新 API → 起新 worker。
 * DB 到 10 后旧 worker（expected=9）重启会以 {@link #ERROR_CODE} 启动失败；回滚时顺序严格相反，
 * 先回滚 API 代码与 V10 结构并删除 history 行，再启动旧 worker（expected=9），不能先起旧 worker。
 *
 * <p>M04：期望版本升到 11。V11 新增路由/开关/尝试/配额/成本表与 {@code provider_credential} 多 key
 * 唯一键；Worker 写入 {@code model_invocation_attempt} 并读取 key 池，因此不得在 V10 库上接收任务。
 * 生产发布顺序见 ADR-0021 §10.2：preflight → 备份 → 一次性迁移 Job → 新 API → drain 旧 Worker →
 * 新 Worker；V11 之后不得重启旧 Worker（回滚窗口以
 * {@code RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false} 启动并登记工单，仅 M04 前最后一个版本）。
 *
 * <p>回滚窗口：校验关闭时必须输出一次固定 WARN {@value #VALIDATION_DISABLED_WARN_CODE}，文案含
 * {@code schema validation disabled}，供 B10 RUNBOOK 的启动日志断言识别；校验启用时绝不输出该 WARN，
 * 行为与版本校验保持不变。
 *
 * <p>注册方式：本类必须是普通 {@code @Component} 并由构造器直接依赖 {@link JdbcClient}。不得再使用
 * {@code @ConditionalOnBean(JdbcClient.class)}：组件扫描阶段条件早于 JDBC 自动配置求值，会导致真实
 * Worker 上下文根本不创建该 Bean，启动校验与回滚 WARN 同时失效（V2 F-002）。
 */
@Component
public class SchemaVersionChecker implements ApplicationRunner {

    /** 期望的 schema 最大版本，对应 {@code apps/api/src/main/resources/db/migration/V1..V11}。 */
    public static final int EXPECTED_SCHEMA_VERSION = 11;

    /** 稳定错误码前缀，启动失败日志只输出版本与错误码，不输出连接串。 */
    public static final String ERROR_CODE = "RICCON-WORKER-001";

    /** 回滚窗口固定 WARN 码；仅 {@code validation-enabled=false} 时输出。 */
    public static final String VALIDATION_DISABLED_WARN_CODE = "RICCON-WORKER-002";

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(SchemaVersionChecker.class);

    /** 只读 Flyway 元数据表的 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /** 是否执行 schema 版本严格校验；false 仅用于回滚窗口。 */
    private final boolean validationEnabled;

    /** 防止同一进程内重复调用 run 时重复输出回滚 WARN。 */
    private final AtomicBoolean validationDisabledWarned = new AtomicBoolean();

    /**
     * 创建 schema 版本校验器。
     *
     * @param jdbcClient Spring JDBC 客户端
     * @param validationEnabled 是否执行版本校验；false 仅用于回滚窗口
     */
    public SchemaVersionChecker(
            JdbcClient jdbcClient,
            @Value("${riccon.worker.schema.validation-enabled:true}") boolean validationEnabled) {
        this.jdbcClient = jdbcClient;
        this.validationEnabled = validationEnabled;
    }

    /**
     * Spring Boot 启动回调。
     *
     * <p>校验关闭时只输出固定回滚 WARN 并跳过版本查询；校验启用时行为与 M02–M04 基线完全一致。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!validationEnabled) {
            if (validationDisabledWarned.compareAndSet(false, true)) {
                LOG.warn(
                        "{} schema validation disabled; worker is running in rollback window, expected schema version={}",
                        VALIDATION_DISABLED_WARN_CODE,
                        EXPECTED_SCHEMA_VERSION);
            }
            return;
        }
        check();
    }

    /**
     * 读取并校验 schema 版本。
     *
     * @return 数据库中的实际最大成功版本
     * @throws IllegalStateException 表缺失、查询失败或版本不符
     */
    public int check() {
        Integer actual;
        try {
            actual = jdbcClient.sql("""
                            SELECT MAX(CAST(version AS UNSIGNED)) AS version
                              FROM flyway_schema_history
                             WHERE success = 1
                            """).query(Integer.class).optional().orElse(null);
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    ERROR_CODE + " schema version check failed: flyway_schema_history is missing or unreadable", ex);
        }
        if (actual == null) {
            throw new IllegalStateException(ERROR_CODE + " schema version check failed: no successful migration found");
        }
        if (actual != EXPECTED_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    ERROR_CODE + " schema version mismatch: expected=" + EXPECTED_SCHEMA_VERSION + " actual=" + actual);
        }
        LOG.info("schema.version.checked expected={} actual={}", EXPECTED_SCHEMA_VERSION, actual);
        return actual;
    }
}
