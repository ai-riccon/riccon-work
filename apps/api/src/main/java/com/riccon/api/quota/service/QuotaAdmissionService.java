package com.riccon.api.quota.service;

import com.riccon.api.quota.config.QuotaEnforcementProperties;
import com.riccon.api.quota.config.QuotaProperties;
import com.riccon.api.quota.domain.QuotaEffectiveLimit;
import com.riccon.api.quota.domain.QuotaExceededException;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUnavailableException;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.api.quota.domain.QuotaWindowBoundary;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.repository.QuotaRepository;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配额准入（ADR-0022 §1.3-R「预建行独立事务 + 热路径共享锁 + 行级原子更新」）。
 *
 * <p>一次创建拆成两个阶段：
 *
 * <ol>
 *   <li>{@link #prepare} 在业务事务之外运行独立短事务，只补齐缺失的 4 个窗口行；冷路径取 {@code tenant}
 *       X 锁串行补行，提交即释放，不覆盖业务事务。
 *   <li>{@link #admit} 在创建 run 的既有事务（{@code MANDATORY}）内按固定顺序执行 4 条原子 UPDATE；不取
 *       tenant 锁、不补行，条件更新的 {@code affectedRows} 仍是唯一准入判据。
 * </ol>
 *
 * <p>业务事务在配额更新前对 {@code tenant} 行取共享锁（{@link #lockTenantShared}）：S 相互兼容，同租户创建
 * 不再整体串行，只与冷路径补行和 M06 重试的 X 互斥。锁序固定为 tenant S/X → 业务行 → TENANT/DAILY →
 * USER/DAILY → TENANT/MONTHLY → USER/MONTHLY；禁止在已持 S 的事务内申请 tenant X（S→X 升级会死锁）。
 *
 * <p>热路径主事务不自动重试（避免重复扣减或重复创建 run）；死锁/锁等待映射为 503 由调用方人工重试。冷路径
 * 补行只插入缺失行、幂等且不改计数，允许最多 2 次带抖动的有限重试。
 */
@Service
public class QuotaAdmissionService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(QuotaAdmissionService.class);

    /** 同一事务内的固定准入顺序（窗口外层、维度内层）。 */
    private static final List<QuotaScope> SCOPE_ORDER = List.of(QuotaScope.TENANT, QuotaScope.USER);

    /** 冷路径补行失败后的最大重试次数（首次执行之外）。 */
    private static final int PREPARE_MAX_RETRIES = 2;

    /** 补行重试的最小抖动（毫秒）。 */
    private static final long PREPARE_RETRY_MIN_MILLIS = 10L;

    /** 补行重试的最大抖动（毫秒）。 */
    private static final long PREPARE_RETRY_MAX_MILLIS = 50L;

    /** 配额仓储。 */
    private final QuotaRepository quotaRepository;

    /** 部署默认值。 */
    private final QuotaProperties quotaProperties;

    /** 执行开关覆盖。 */
    private final QuotaEnforcementProperties enforcementProperties;

    /** 指标。 */
    private final QuotaMetrics quotaMetrics;

    /** 冷路径补行短事务模板（独立提交，不复用业务事务连接）。 */
    private final TransactionTemplate prepareTransaction;

    /**
     * 创建准入服务。
     *
     * @param quotaRepository 配额仓储
     * @param quotaProperties 部署默认值
     * @param enforcementProperties 执行开关覆盖
     * @param quotaMetrics 指标
     * @param transactionManager 事务管理器，用于冷路径独立短事务
     */
    public QuotaAdmissionService(
            QuotaRepository quotaRepository,
            QuotaProperties quotaProperties,
            QuotaEnforcementProperties enforcementProperties,
            QuotaMetrics quotaMetrics,
            PlatformTransactionManager transactionManager) {
        this.quotaRepository = Objects.requireNonNull(quotaRepository, "quotaRepository must not be null");
        this.quotaProperties = Objects.requireNonNull(quotaProperties, "quotaProperties must not be null");
        this.enforcementProperties =
                Objects.requireNonNull(enforcementProperties, "enforcementProperties must not be null");
        this.quotaMetrics = Objects.requireNonNull(quotaMetrics, "quotaMetrics must not be null");
        this.prepareTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        // 补行必须独立提交；调用前已用无活动事务守卫保证不会与业务事务嵌套。
        this.prepareTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 在业务事务之外准备一次准入：补齐缺失的窗口行并冻结本次准入使用的窗口边界。
     *
     * <p>4 个窗口行齐全时只做一次非锁定存在性查询，不开启事务；缺失时才进入独立短事务，先取 {@code tenant}
     * X 锁让同租户补行串行，再在锁内重新确认缺失集合、只插入仍缺失的行。这样既避免首次创建配额行的 ODKU
     * 共享锁升级死锁，也不会把补行时间计入业务事务的临界区。
     *
     * <p>禁止在活动事务内调用：那会在一笔请求里占用第二条连接（{@code REQUIRES_NEW} 嵌套），50 并发下可能
     * 耗尽 Hikari 池。本方法 fail-fast 抛出 {@link IllegalStateException}，不静默降级。
     *
     * @param tenantId 当前租户
     * @param userId 发起人
     * @param now 本次准入的基准时间（整个请求复用同一窗口边界，避免跨零点不一致）
     * @return 准入上下文，供 {@link #admit} 使用
     * @throws IllegalStateException 在活动事务内调用
     * @throws QuotaUnavailableException 配额存储不可用或租户行缺失
     */
    public AdmissionContext prepare(TenantId tenantId, UUID userId, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("quota prepare must run outside an active transaction");
        }
        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);
        if (!usageRowsComplete(tenantId, userId, boundaries)) {
            fillMissingUsageRows(tenantId, userId, boundaries);
        }
        return new AdmissionContext(tenantId, userId, now, boundaries);
    }

    /**
     * 在业务事务内对租户行取共享锁（创建 run 路径）。
     *
     * <p>J1/J2 必须在任何业务行锁之前调用；M06 创建可在只读校验之后、配额更新之前调用。S 相互兼容，只用于
     * 与冷路径补行 X、M06 重试 X 互斥，不承担配额判据。
     *
     * @param tenantId 当前租户
     * @throws QuotaUnavailableException 租户行缺失或存储不可用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenantShared(TenantId tenantId) {
        lockTenant(tenantId, false);
    }

    /**
     * 在业务事务内对租户行取排他锁（M06 重试路径）。
     *
     * <p>必须在只读校验之后、活跃运行 count/update 之前调用，且此前不得持有任何业务行锁。它与所有创建事务的 S
     * 互斥，保证重试恢复运行时的并发计数精确；重试不扣配额。
     *
     * @param tenantId 当前租户
     * @throws QuotaUnavailableException 租户行缺失或存储不可用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenantExclusive(TenantId tenantId) {
        lockTenant(tenantId, true);
    }

    /**
     * 在当前事务内为一次新 run 做准入并计数。
     *
     * <p>只执行 4 条原子 UPDATE，不取 tenant 锁、不补行；是否准入只由条件更新的 {@code affectedRows} 决定。
     * 条件更新返回 0 时再读一次用量行区分原因：行存在且已满 → 429；行缺失 → 503（fail-closed，不能把补行
     * 失败误报成“配额已满”）。关闭执行或明确不限时仍无条件累加，只跳过拒绝。
     *
     * @param context {@link #prepare} 返回的准入上下文
     * @throws QuotaExceededException 任一维度/窗口已满
     * @throws QuotaUnavailableException 配额存储、开关不可用或窗口行缺失
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(AdmissionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        TenantId tenantId = context.tenantId();
        UUID userId = context.userId();
        try {
            boolean enforce = enforcementEnabled();
            Map<String, Integer> explicitLimits = explicitLimits(quotaRepository.findAdmissionLimits(tenantId, userId));
            for (QuotaWindowBoundary boundary : context.boundaries()) {
                for (QuotaScope scope : SCOPE_ORDER) {
                    String scopeId = scope == QuotaScope.TENANT ? tenantId.value() : userId.toString();
                    String limitKey = key(scope, boundary.window());
                    QuotaEffectiveLimit effective = quotaProperties.resolve(
                            scope,
                            boundary.window(),
                            explicitLimits.containsKey(limitKey),
                            explicitLimits.get(limitKey));
                    if (!enforce || effective.unlimited()) {
                        admitUnconditionally(context, scope, scopeId, boundary, effective, enforce);
                    } else {
                        admitConditionally(context, userId, scope, scopeId, boundary, effective);
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
     * 关闭执行或明确不限时的无条件累加。
     *
     * <p>行必然由 {@link #prepare} 补齐；更新 0 行说明补行失败或行被并发清理，按存储不可用 fail-closed，不能
     * 当成“已满”拒绝。关闭执行时仍记录用量与超限观察指标，只是不抛 429。
     *
     * @param context 准入上下文
     * @param scope 维度
     * @param scopeId 维度标识
     * @param boundary 窗口边界
     * @param effective 生效限额
     * @param enforce 是否执行限额比较
     */
    private void admitUnconditionally(
            AdmissionContext context,
            QuotaScope scope,
            String scopeId,
            QuotaWindowBoundary boundary,
            QuotaEffectiveLimit effective,
            boolean enforce) {
        int updated = quotaRepository.incrementUnconditional(
                context.tenantId(), scope, scopeId, boundary.window(), boundary.windowStart(), context.now());
        if (updated == 0) {
            LOG.error(
                    "quota.admission.row_missing tenantId={} scopeType={} window={}",
                    context.tenantId().value(),
                    scope,
                    boundary.window());
            throw new QuotaUnavailableException();
        }
        if (effective.limit() == null) {
            return;
        }
        int used = requireCurrentUsed(context.tenantId(), scope, scopeId, boundary);
        quotaMetrics.recordAdmission(scope, boundary.window(), used, effective.limit());
        if (!enforce && used > effective.limit()) {
            quotaMetrics.recordOverLimitObserved(scope, boundary.window());
        }
    }

    /**
     * 有限额且执行开启时的条件累加；0 行时区分“已满 429”与“行缺失 503”。
     *
     * @param context 准入上下文
     * @param userId 发起人，仅用于构造超限异常
     * @param scope 维度
     * @param scopeId 维度标识
     * @param boundary 窗口边界
     * @param effective 生效限额
     * @throws QuotaExceededException 当前窗口已满
     * @throws QuotaUnavailableException 窗口行缺失
     */
    private void admitConditionally(
            AdmissionContext context,
            UUID userId,
            QuotaScope scope,
            String scopeId,
            QuotaWindowBoundary boundary,
            QuotaEffectiveLimit effective) {
        int updated = quotaRepository.incrementConditional(
                context.tenantId(),
                scope,
                scopeId,
                boundary.window(),
                boundary.windowStart(),
                effective.limit(),
                context.now());
        if (updated == 0) {
            // 条件更新 0 行有两种可能：已满或行缺失。先按主键读一次区分，不能把补行失败误报成 429。
            Optional<Integer> used = quotaRepository.findUsed(
                    context.tenantId(), scope, scopeId, boundary.window(), boundary.windowStart());
            if (used.isEmpty()) {
                LOG.error(
                        "quota.admission.row_missing tenantId={} scopeType={} window={}",
                        context.tenantId().value(),
                        scope,
                        boundary.window());
                throw new QuotaUnavailableException();
            }
            quotaMetrics.recordRejection(scope, boundary.window());
            throw new QuotaExceededException(
                    context.tenantId().value(),
                    userId,
                    scope,
                    scopeId,
                    boundary.window(),
                    effective.limit(),
                    used.get(),
                    boundary.resetsAt());
        }
        int used = requireCurrentUsed(context.tenantId(), scope, scopeId, boundary);
        quotaMetrics.recordAdmission(scope, boundary.window(), used, effective.limit());
    }

    /**
     * 读取准入后已用量；行缺失视为存储异常（fail-closed）。
     *
     * @param tenantId 租户
     * @param scope 维度
     * @param scopeId 维度标识
     * @param boundary 窗口边界
     * @return 已用量
     * @throws QuotaUnavailableException 行缺失
     */
    private int requireCurrentUsed(TenantId tenantId, QuotaScope scope, String scopeId, QuotaWindowBoundary boundary) {
        return quotaRepository
                .findUsed(tenantId, scope, scopeId, boundary.window(), boundary.windowStart())
                .orElseThrow(QuotaUnavailableException::new);
    }

    /**
     * 判断 4 个窗口行是否齐全（非锁定查询，可在业务事务之外执行）。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @param boundaries 当前窗口
     * @return 全部存在时返回 {@code true}
     * @throws QuotaUnavailableException 查询失败
     */
    private boolean usageRowsComplete(TenantId tenantId, UUID userId, List<QuotaWindowBoundary> boundaries) {
        try {
            Set<String> present = usageKeys(tenantId, userId, boundaries);
            for (QuotaWindowBoundary boundary : boundaries) {
                for (QuotaScope scope : SCOPE_ORDER) {
                    String scopeId = scope == QuotaScope.TENANT ? tenantId.value() : userId.toString();
                    if (!present.contains(usageKey(scope, scopeId, boundary.window()))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (DataAccessException exception) {
            LOG.error("quota.prepare.check_failure tenantId={} userId={}", tenantId.value(), userId, exception);
            quotaMetrics.recordStoreFailure("store");
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 在独立短事务内补齐缺失的窗口行。
     *
     * <p>只对缺失行执行 ODKU；先取 tenant X 让同租户补行串行并在锁内重新确认，避免 MySQL 首次插入同一主键时
     * 的共享锁升级死锁。补行幂等且不改变 used_count；最大 2 次抖动重试只针对死锁/锁等待，最终失败映射 503。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @param boundaries 当前窗口
     * @throws QuotaUnavailableException 租户行缺失、重试耗尽或非锁类存储故障
     */
    private void fillMissingUsageRows(TenantId tenantId, UUID userId, List<QuotaWindowBoundary> boundaries) {
        String lastReason = null;
        for (int attempt = 0; attempt <= PREPARE_MAX_RETRIES; attempt++) {
            try {
                prepareTransaction.executeWithoutResult(status -> insertMissingUsageRows(tenantId, userId, boundaries));
                return;
            } catch (DeadlockLoserDataAccessException exception) {
                lastReason = "deadlock";
                LOG.warn("quota.prepare.deadlock tenantId={} attempt={}", tenantId.value(), attempt + 1, exception);
            } catch (CannotAcquireLockException exception) {
                lastReason = lockFailureReason(exception);
                LOG.warn(
                        "quota.prepare.{} tenantId={} attempt={}",
                        lastReason,
                        tenantId.value(),
                        attempt + 1,
                        exception);
            } catch (DataAccessException exception) {
                LOG.error("quota.prepare.store_failure tenantId={}", tenantId.value(), exception);
                quotaMetrics.recordStoreFailure("store");
                throw new QuotaUnavailableException();
            }
            if (attempt < PREPARE_MAX_RETRIES) {
                pauseBeforePrepareRetry();
            }
        }
        quotaMetrics.recordStoreFailure(lastReason == null ? "deadlock" : lastReason);
        throw new QuotaUnavailableException();
    }

    /**
     * 在补行短事务内执行：取 tenant X → 锁内重查 → 只插入仍缺失的行。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @param boundaries 当前窗口
     */
    private void insertMissingUsageRows(TenantId tenantId, UUID userId, List<QuotaWindowBoundary> boundaries) {
        if (!quotaRepository.lockTenantExclusive(tenantId)) {
            throw new QuotaUnavailableException();
        }
        Set<String> present = usageKeys(tenantId, userId, boundaries);
        for (QuotaWindowBoundary boundary : boundaries) {
            for (QuotaScope scope : SCOPE_ORDER) {
                String scopeId = scope == QuotaScope.TENANT ? tenantId.value() : userId.toString();
                if (!present.contains(usageKey(scope, scopeId, boundary.window()))) {
                    quotaRepository.ensureUsageRow(tenantId, scope, scopeId, boundary.window(), boundary.windowStart());
                }
            }
        }
    }

    /**
     * 查询当前窗口已存在的用量行并转成 {@code scope:scopeId:window} 键集合。
     *
     * @param tenantId 当前租户
     * @param userId 当前用户
     * @param boundaries 当前窗口
     * @return 已存在行的键集合
     */
    private Set<String> usageKeys(TenantId tenantId, UUID userId, List<QuotaWindowBoundary> boundaries) {
        Set<Instant> starts = new LinkedHashSet<>();
        for (QuotaWindowBoundary boundary : boundaries) {
            starts.add(boundary.windowStart());
        }
        Set<String> keys = new HashSet<>();
        for (QuotaUsageRow row : quotaRepository.findWindowUsageRows(tenantId, userId, List.copyOf(starts))) {
            keys.add(usageKey(row.scopeType(), row.scopeId(), row.window()));
        }
        return keys;
    }

    /**
     * 生成窗口行键。
     *
     * @param scope 维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @return 键
     */
    private static String usageKey(QuotaScope scope, String scopeId, QuotaWindow window) {
        return scope.name() + ":" + scopeId + ":" + window.name();
    }

    /**
     * 补行重试前的 10–50ms 抖动等待，避免多个补行事务同步重试再次碰撞。
     *
     * @throws QuotaUnavailableException 线程被中断
     */
    private static void pauseBeforePrepareRetry() {
        long millis = ThreadLocalRandom.current().nextLong(PREPARE_RETRY_MIN_MILLIS, PREPARE_RETRY_MAX_MILLIS + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QuotaUnavailableException();
        }
    }

    /**
     * 在业务事务内对租户行加锁；失败按死锁/锁等待/存储故障映射 503。
     *
     * @param tenantId 当前租户
     * @param exclusive true 取 X 锁，false 取 S 锁
     * @throws QuotaUnavailableException 租户行缺失或存储不可用
     */
    private void lockTenant(TenantId tenantId, boolean exclusive) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String mode = exclusive ? "exclusive" : "shared";
        try {
            boolean present = exclusive
                    ? quotaRepository.lockTenantExclusive(tenantId)
                    : quotaRepository.lockTenantShared(tenantId);
            if (!present) {
                throw new QuotaUnavailableException();
            }
        } catch (DeadlockLoserDataAccessException exception) {
            LOG.warn("quota.lock.deadlock tenantId={} mode={}", tenantId.value(), mode, exception);
            quotaMetrics.recordStoreFailure("deadlock");
            throw new QuotaUnavailableException();
        } catch (CannotAcquireLockException exception) {
            String reason = lockFailureReason(exception);
            LOG.warn("quota.lock.{} tenantId={} mode={}", reason, tenantId.value(), mode, exception);
            quotaMetrics.recordStoreFailure(reason);
            throw new QuotaUnavailableException();
        } catch (DataAccessException exception) {
            LOG.error("quota.lock.store_failure tenantId={} mode={}", tenantId.value(), mode, exception);
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
     * 生成限额映射键。
     *
     * @param scope 维度
     * @param window 窗口
     * @return 键
     */
    private static String key(QuotaScope scope, QuotaWindow window) {
        return scope.name() + ":" + window.name();
    }

    /**
     * 一次准入冻结的上下文。
     *
     * <p>{@code now} 与 {@code boundaries} 在 {@link #prepare} 时一次性确定，{@link #admit} 不再读时钟：这样即使
     * 请求恰好跨过零点/月初，补行、计数与业务写入仍使用同一组窗口，不会出现“补的是今天的行、扣的是明天的行”。
     *
     * @param tenantId 当前租户
     * @param userId 发起人
     * @param now 准入基准时间
     * @param boundaries 当前日/月窗口
     */
    public record AdmissionContext(TenantId tenantId, UUID userId, Instant now, List<QuotaWindowBoundary> boundaries) {

        /** 保证上下文完整且窗口不可变。 */
        public AdmissionContext {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(now, "now must not be null");
            Objects.requireNonNull(boundaries, "boundaries must not be null");
            boundaries = List.copyOf(boundaries);
            if (boundaries.isEmpty()) {
                throw new IllegalArgumentException("boundaries must not be empty");
            }
        }
    }
}
