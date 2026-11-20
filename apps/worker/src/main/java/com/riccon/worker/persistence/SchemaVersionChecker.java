package com.riccon.worker.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 */
@Component
@ConditionalOnProperty(name = "riccon.worker.schema.validation-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(JdbcClient.class)
public class SchemaVersionChecker implements ApplicationRunner {

    /** 期望的 schema 最大版本，对应 {@code apps/api/src/main/resources/db/migration/V1..V11}。 */
    public static final int EXPECTED_SCHEMA_VERSION = 11;

    /** 稳定错误码前缀，启动失败日志只输出版本与错误码，不输出连接串。 */
    public static final String ERROR_CODE = "RICCON-WORKER-001";

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(SchemaVersionChecker.class);

    /** 只读 Flyway 元数据表的 JDBC 客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建 schema 版本校验器。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public SchemaVersionChecker(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Spring Boot 启动回调。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
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
