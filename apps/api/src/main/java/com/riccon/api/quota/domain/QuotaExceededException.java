package com.riccon.api.quota.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * 配额超限；使用 {@code RICCON-QUOTA-429} 与专用响应体，和“模型不可用”区分。
 *
 * <p>异常只在 run 创建事务内由 {@link QuotaAdmissionService} 抛出；事务回滚后由 Web 边界写
 * {@code quota.exceeded} 拒绝审计，因此异常必须携带审计所需的租户、发起人与窗口信息。
 */
public class QuotaExceededException extends RicconException {

    /** 配额所属租户。 */
    private final String tenantId;

    /** 发起本次请求的用户。 */
    private final UUID actorUserId;

    /** 触发的维度。 */
    private final QuotaScope scopeType;

    /** 触发维度的 scopeId（租户代码或用户 UUID 字符串）。 */
    private final String scopeId;

    /** 触发的窗口。 */
    private final QuotaWindow window;

    /** 生效限额（必为有限值）。 */
    private final int limit;

    /** 拒绝时的已用量。 */
    private final int used;

    /** 窗口重置时间（带 +08:00 偏移）。 */
    private final OffsetDateTime resetsAt;

    /**
     * 创建超限异常。
     *
     * @param tenantId 租户
     * @param actorUserId 发起人
     * @param scopeType 触发的维度
     * @param scopeId 维度标识
     * @param window 窗口
     * @param limit 生效限额
     * @param used 已用量
     * @param resetsAt 重置时间
     */
    public QuotaExceededException(
            String tenantId,
            UUID actorUserId,
            QuotaScope scopeType,
            String scopeId,
            QuotaWindow window,
            int limit,
            int used,
            OffsetDateTime resetsAt) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCodes.QUOTA_EXCEEDED,
                "Quota exceeded",
                "已达" + (window == QuotaWindow.DAILY ? "日" : "月") + "限额，" + QuotaWindows.format(resetsAt.toInstant())
                        + "后恢复。");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType must not be null");
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        this.resetsAt = Objects.requireNonNull(resetsAt, "resetsAt must not be null");
        if (limit < 0 || used < 0) {
            throw new IllegalArgumentException("limit and used must not be negative");
        }
        this.limit = limit;
        this.used = used;
    }

    /**
     * 返回租户。
     *
     * @return 租户代码
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * 返回发起人。
     *
     * @return 用户 ID
     */
    public UUID actorUserId() {
        return actorUserId;
    }

    /**
     * 返回触发的维度。
     *
     * @return TENANT 或 USER
     */
    public QuotaScope scopeType() {
        return scopeType;
    }

    /**
     * 返回维度标识。
     *
     * @return 租户代码或用户 UUID 字符串
     */
    public String scopeId() {
        return scopeId;
    }

    /**
     * 返回触发的窗口。
     *
     * @return DAILY 或 MONTHLY
     */
    public QuotaWindow window() {
        return window;
    }

    /**
     * 返回生效限额。
     *
     * @return 限额
     */
    public int limit() {
        return limit;
    }

    /**
     * 返回拒绝时已用量。
     *
     * @return 已用量
     */
    public int used() {
        return used;
    }

    /**
     * 返回窗口重置时间。
     *
     * @return 带偏移时间
     */
    public OffsetDateTime resetsAt() {
        return resetsAt;
    }
}
