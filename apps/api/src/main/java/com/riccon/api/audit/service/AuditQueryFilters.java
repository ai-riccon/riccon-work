package com.riccon.api.audit.service;

import com.riccon.api.audit.repository.AuditQueryFilter;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 审计过滤器解析与时间范围校验（ADR-0020 第 1/2 节）。
 *
 * <p>时间参数必须带偏移；缺省窗口为最近 24 小时；跨度不超过 180 天；结束时间不得晚于服务器时间 5 分钟以上。
 * 校验失败统一返回 {@code RICCON-AUDIT-400}，不泄露内部时间或 SQL。
 */
public final class AuditQueryFilters {

    /** 团队默认时区偏移（Asia/Shanghai）。 */
    public static final ZoneOffset TEAM_OFFSET = ZoneOffset.ofHours(8);

    /** 最大可查跨度（天）。 */
    public static final int MAX_RANGE_DAYS = 180;

    /** 允许的客户端时钟超前量。 */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /** 默认查询窗口。 */
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** 动作名最大长度。 */
    private static final int MAX_ACTION_LENGTH = 100;

    /** 目标 ID 最大长度。 */
    private static final int MAX_TARGET_ID_LENGTH = 64;

    /** 工具类禁止实例化。 */
    private AuditQueryFilters() {}

    /**
     * 解析并规范化过滤器；缺省时间在服务端补齐后固化。
     *
     * @param occurredFrom 起始时间原文，可为空
     * @param occurredTo 结束时间原文，可为空
     * @param action 动作名，可为空
     * @param actorId 操作者 ID 原文，可为空
     * @param targetType 目标类型原文，可为空
     * @param targetId 目标 ID，可为空
     * @param outcome 结果原文，可为空
     * @param now 当前时间
     * @return 规范化过滤器
     * @throws AuditInvalidRequestException 时间无偏移、跨度超限或枚举/长度非法
     */
    public static AuditQueryFilter resolve(
            String occurredFrom,
            String occurredTo,
            String action,
            String actorId,
            String targetType,
            String targetId,
            String outcome,
            Instant now) {
        OffsetDateTime nowOffset = now.atOffset(TEAM_OFFSET);
        OffsetDateTime resolvedTo = isBlank(occurredTo) ? nowOffset : parseTime("occurredTo", occurredTo);
        OffsetDateTime resolvedFrom =
                isBlank(occurredFrom) ? resolvedTo.minus(DEFAULT_WINDOW) : parseTime("occurredFrom", occurredFrom);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new AuditInvalidRequestException("occurredFrom must not be after occurredTo");
        }
        if (resolvedTo.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw new AuditInvalidRequestException("occurredTo must not be in the future");
        }
        if (Duration.between(resolvedFrom, resolvedTo).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new AuditInvalidRequestException("time range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        return new AuditQueryFilter(
                resolvedFrom,
                resolvedTo,
                normalizeText(action, "action", MAX_ACTION_LENGTH),
                parseActorId(actorId),
                parseTargetType(targetType),
                normalizeText(targetId, "targetId", MAX_TARGET_ID_LENGTH),
                parseOutcome(outcome));
    }

    /**
     * 解析带偏移的 ISO 8601 时间并统一为 +08:00。
     *
     * @param field 字段名
     * @param value 原文
     * @return 规范化时间
     * @throws AuditInvalidRequestException 缺少偏移或格式非法
     */
    private static OffsetDateTime parseTime(String field, String value) {
        try {
            return OffsetDateTime.parse(value.strip()).withOffsetSameInstant(TEAM_OFFSET);
        } catch (DateTimeParseException exception) {
            // OffsetDateTime 只接受带偏移的时间；无偏移的 LocalDateTime 会在这里失败。
            throw new AuditInvalidRequestException(field + " must be ISO 8601 with offset");
        }
    }

    /**
     * 解析操作者 ID。
     *
     * @param value 原文，可为空
     * @return 账号 ID；未提供时为空
     * @throws AuditInvalidRequestException 不是 UUID
     */
    private static UUID parseActorId(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new AuditInvalidRequestException("actorId must be a UUID");
        }
    }

    /**
     * 解析目标类型。
     *
     * @param value 原文，可为空
     * @return 目标类型；未提供时为空
     * @throws AuditInvalidRequestException 不是合法枚举
     */
    private static AuditTargetType parseTargetType(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return AuditTargetType.valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new AuditInvalidRequestException("targetType is not supported");
        }
    }

    /**
     * 解析结果枚举。
     *
     * @param value 原文，可为空
     * @return 结果；未提供时为空
     * @throws AuditInvalidRequestException 不是合法枚举
     */
    private static AuditOutcome parseOutcome(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return AuditOutcome.valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new AuditInvalidRequestException("outcome is not supported");
        }
    }

    /**
     * 校验可选字符串长度。
     *
     * @param value 原文
     * @param field 字段名
     * @param maxLength 最大长度
     * @return 去空白文本；空值返回 {@code null}
     * @throws AuditInvalidRequestException 超长
     */
    private static String normalizeText(String value, String field, int maxLength) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new AuditInvalidRequestException(field + " exceeds " + maxLength + " characters");
        }
        return trimmed;
    }

    /**
     * 判断字符串是否为空或空白。
     *
     * @param value 值
     * @return 为空时返回 {@code true}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
