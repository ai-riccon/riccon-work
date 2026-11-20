package com.riccon.api.quota.service;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.quota.domain.QuotaExceededException;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配额拒绝审计。
 *
 * <p>拒绝发生时，创建 run 的外层事务已经回滚；本服务使用 {@code REQUIRES_NEW} 在独立事务写
 * {@code quota.exceeded}，避免审计被回滚掉，也避免在持有配额行锁时再申请第二个数据库连接造成连接池死锁。
 * 审计失败直接向上抛出，由 Web 边界返回 500 而不是 429（“无审计不拒绝”的 fail-closed，ADR-0022 第 1.5 节）。
 */
@Service
public class QuotaAuditService {

    /** 审计写入。 */
    private final AuditService auditService;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建拒绝审计服务。
     *
     * @param auditService 审计写入
     * @param clock UTC 时钟
     */
    public QuotaAuditService(AuditService auditService, Clock clock) {
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 写一条 {@code quota.exceeded}。
     *
     * @param exception 超限异常，携带租户、发起人与窗口信息
     * @param traceId 追踪 ID
     * @param requestId 请求 ID，可为空
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExceeded(QuotaExceededException exception, String traceId, String requestId) {
        Objects.requireNonNull(exception, "exception must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeType", exception.scopeType().name());
        details.put("scopeId", exception.scopeId());
        details.put("window", exception.window().name());
        details.put("limit", exception.limit());
        details.put("used", exception.used());
        details.put("resetsAt", QuotaWindows.format(exception.resetsAt().toInstant()));
        TenantId tenantId = TenantId.of(exception.tenantId());
        Instant now = clock.instant();
        TenantContext.runWithTenant(
                tenantId,
                () -> auditService.append(AuditEvent.create(
                        now,
                        tenantId.value(),
                        AuditActorType.USER,
                        exception.actorUserId().toString(),
                        AuditActions.QUOTA_EXCEEDED,
                        AuditTargetType.QUOTA,
                        exception.scopeId(),
                        AuditOutcome.DENIED,
                        traceId,
                        requestId,
                        null,
                        details)));
    }
}
