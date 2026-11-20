package com.riccon.api.quota.service;

import com.riccon.api.quota.config.QuotaEnforcementProperties;
import com.riccon.api.quota.config.QuotaProperties;
import com.riccon.api.quota.domain.QuotaEffectiveLimit;
import com.riccon.api.quota.domain.QuotaExceededException;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUnavailableException;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.api.quota.domain.QuotaWindowBoundary;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.repository.QuotaRepository;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配额准入（ADR-0022 第 1.3 节）。
 *
 * <p>必须在创建 run 的既有事务内调用（{@code MANDATORY}），计数累加与 {@code agent_run} 插入要么一起提交，
 * 要么一起回滚。调用方先在租户行上取串行锁，再按固定顺序 TENANT/DAILY → TENANT/MONTHLY → USER/DAILY →
 * USER/MONTHLY 计数；所有事务同序，避免首次创建配额行时的 ODKU 共享锁升级死锁。
 *
 * <p>有限额时使用 {@code UPDATE ... WHERE used_count < :limit} 的条件累加；MySQL InnoDB 的当前读与行锁保证
 * 剩余 1 次时恰好 1 个事务成功，不存在“先查后写”的竞态。关闭执行时仍然计数，只跳过比较与拒绝。
 *
 * <p>本类不做自动重试：重试可能重复扣减或重复创建 run；死锁/锁等待映射为 503 由调用方人工重试。
 */
@Service
public class QuotaAdmissionService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(QuotaAdmissionService.class);

    /** 同一事务内的固定准入顺序。 */
    private static final List<QuotaScope> SCOPE_ORDER = List.of(QuotaScope.TENANT, QuotaScope.USER);

    /** 配额仓储。 */
    private final QuotaRepository quotaRepository;

    /** 部署默认值。 */
    private final QuotaProperties quotaProperties;

    /** 执行开关覆盖。 */
    private final QuotaEnforcementProperties enforcementProperties;

    /** 指标。 */
    private final QuotaMetrics quotaMetrics;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建准入服务。
     *
     * @param quotaRepository 配额仓储
     * @param quotaProperties 部署默认值
     * @param enforcementProperties 执行开关覆盖
     * @param quotaMetrics 指标
     * @param clock UTC 时钟
     */
    public QuotaAdmissionService(
            QuotaRepository quotaRepository,
            QuotaProperties quotaProperties,
            QuotaEnforcementProperties enforcementProperties,
            QuotaMetrics quotaMetrics,
            Clock clock) {
        this.quotaRepository = Objects.requireNonNull(quotaRepository, "quotaRepository must not be null");
        this.quotaProperties = Objects.requireNonNull(quotaProperties, "quotaProperties must not be null");
        this.enforcementProperties =
                Objects.requireNonNull(enforcementProperties, "enforcementProperties must not be null");
        this.quotaMetrics = Objects.requireNonNull(quotaMetrics, "quotaMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 在当前准入事务内提前锁定租户行。
     *
     * <p>必须在调用方取得任何其他行锁（会话、幂等记录、报告任务）之前调用，保证所有事务的加锁顺序一致；
     * 否则可能出现“先锁业务行、再等租户锁”与“持租户锁、插入业务行”的交叉死锁。锁定只防止首次创建配额行
     * 的 ODKU 共享锁升级死锁，不改变“先查幂等、后扣配额”的语义。
     *
     * @param tenantId 当前租户
     * @throws QuotaUnavailableException 租户行缺失或存储不可用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        try {
            if (!quotaRepository.lockTenant(tenantId)) {
                throw new QuotaUnavailableException();
            }
        } catch (DeadlockLoserDataAccessException exception) {
            LOG.warn("quota.lock.deadlock tenantId={}", tenantId.value(), exception);
            quotaMetrics.recordStoreFailure("deadlock");
            throw new QuotaUnavailableException();
        } catch (CannotAcquireLockException exception) {
            String reason = lockFailureReason(exception);
            LOG.warn("quota.lock.{} tenantId={}", reason, tenantId.value(), exception);
            quotaMetrics.recordStoreFailure(reason);
            throw new QuotaUnavailableException();
        } catch (DataAccessException exception) {
            LOG.error("quota.lock.store_failure tenantId={}", tenantId.value(), exception);
            quotaMetrics.recordStoreFailure("store");
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 在当前事务内为一次新 run 做准入并计数。
     *
     * @param tenantId 当前租户
     * @param userId 发起人
     * @throws QuotaExceededException 任一维度/窗口已满
     * @throws QuotaUnavailableException 配额存储或全局开关不可用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(TenantId tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Instant now = clock.instant();
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);
        try {
            // 幂等调用：调用方若已提前 lockTenant，这里只是再次确认同一行锁；未提前调用时也能在配额操作前串行。
            if (!quotaRepository.lockTenant(tenantId)) {
                throw new QuotaUnavailableException();
            }
            boolean enforce = enforcementEnabled();
            Map<String, Integer> explicitLimits = explicitLimits(quotaRepository.findAdmissionLimits(tenantId, userId));
            for (QuotaWindowBoundary boundary : boundaries) {
                for (QuotaScope scope : SCOPE_ORDER) {
                    String scopeId = scope == QuotaScope.TENANT ? tenantId.value() : userId.toString();
                    String limitKey = key(scope, boundary.window());
                    QuotaEffectiveLimit effective = quotaProperties.resolve(
                            scope,
                            boundary.window(),
                            explicitLimits.containsKey(limitKey),
                            explicitLimits.get(limitKey));
                    quotaRepository.ensureUsageRow(tenantId, scope, scopeId, boundary.window(), boundary.windowStart());
                    if (!enforce || effective.unlimited()) {
                        quotaRepository.incrementUnconditional(
                                tenantId, scope, scopeId, boundary.window(), boundary.windowStart(), now);
                    } else if (quotaRepository.incrementConditional(
                                    tenantId,
                                    scope,
                                    scopeId,
                                    boundary.window(),
                                    boundary.windowStart(),
                                    effective.limit(),
                                    now)
                            == 0) {
                        // 条件更新返回 0 表示当前窗口已满：读取精确 used 供审计/响应，然后抛出由外层事务回滚。
                        int used = currentUsed(tenantId, scope, scopeId, boundary, effective.limit());
                        quotaMetrics.recordRejection(scope, boundary.window());
                        throw new QuotaExceededException(
                                tenantId.value(),
                                userId,
                                scope,
                                scopeId,
                                boundary.window(),
                                effective.limit(),
                                used,
                                boundary.resetsAt());
                    }
                    int used = currentUsed(tenantId, scope, scopeId, boundary, 0);
                    quotaMetrics.recordAdmission(scope, boundary.window(), used, effective.limit());
                    if (!enforce && effective.limit() != null && used > effective.limit()) {
                        quotaMetrics.recordOverLimitObserved(scope, boundary.window());
                    }
                }
            }
        } catch (DeadlockLoserDataAccessException exception) {
            LOG.warn("quota.admission.deadlock tenantId={} userId={}", tenantId.value(), userId, exception);
            quotaMetrics.recordStoreFailure("deadlock");
            throw new QuotaUnavailableException();
        } catch (CannotAcquireLockException exception) {
            String reason = lockFailureReason(exception);
            LOG.warn("quota.admission.{} tenantId={} userId={}", reason, tenantId.value(), userId, exception);
            quotaMetrics.recordStoreFailure(reason);
            throw new QuotaUnavailableException();
        } catch (DataAccessException exception) {
            LOG.error("quota.admission.store_failure tenantId={} userId={}", tenantId.value(), userId, exception);
            quotaMetrics.recordStoreFailure("store");
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 判断锁失败类型，供指标标签使用。
     *
     * <p>MySQL 1213（死锁）在 Spring 中可能被翻译为 {@link CannotAcquireLockException}，不能只看异常子类；
     * 用最具体原因的消息区分 deadlock 与普通锁等待。
     *
     * @param exception 数据访问异常
     * @return deadlock 或 lock_wait
     */
    private static String lockFailureReason(DataAccessException exception) {
        Throwable root = exception.getMostSpecificCause();
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("deadlock") ? "deadlock" : "lock_wait";
    }

    /**
     * 解析当前生效的执行开关：环境变量覆盖优先，否则读数据库开关。
     *
     * @return true 表示执行限额比较与拒绝
     * @throws QuotaUnavailableException 开关行缺失或值非法
     */
    private boolean enforcementEnabled() {
        Optional<Boolean> override = enforcementProperties.enabledOverride();
        if (override.isPresent()) {
            return override.get();
        }
        String raw = quotaRepository.findEnforcementSwitch().orElse(null);
        if (raw == null) {
            LOG.error("quota.enforcement.switch_missing key=quotaEnforcementEnabled");
            throw new QuotaUnavailableException();
        }
        if ("true".equalsIgnoreCase(raw.strip())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.strip())) {
            return false;
        }
        LOG.error("quota.enforcement.switch_invalid value={}", raw);
        throw new QuotaUnavailableException();
    }

    /**
     * 把显式限额行整理成 {@code scope:window -> limit} 映射。
     *
     * @param rows 显式限额行
     * @return 限额映射
     */
    private static Map<String, Integer> explicitLimits(List<QuotaLimitRow> rows) {
        Map<String, Integer> limits = new HashMap<>();
        for (QuotaLimitRow row : rows) {
            limits.put(key(row.scopeType(), row.window()), row.limit());
        }
        return limits;
    }

    /**
     * 读取当前已用量；行缺失时使用回退值。
     *
     * @param tenantId 租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param boundary 窗口边界
     * @param fallback 回退值
     * @return 已用量
     */
    private int currentUsed(
            TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindowBoundary boundary, int fallback) {
        return quotaRepository
                .findUsed(tenantId, scope, scopeId, boundary.window(), boundary.windowStart())
                .orElse(fallback);
    }

    /**
     * 生成限额映射键。
     *
     * @param scope 维度
     * @param window 窗口
     * @return 键
     */
    private static String key(QuotaScope scope, QuotaWindow window) {
        return scope.name() + ":" + window.name();
    }
}
