package com.riccon.api.audit.repository;

import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已规范化并固化的审计查询过滤器（ADR-0020 第 1/2 节）。
 *
 * <p>时间字段必须带偏移且统一为 {@code Asia/Shanghai}（UTC+08:00）；{@code occurredFrom}/{@code occurredTo}
 * 在服务层补齐默认值后固化，导出申请把同一份条件保存到数据库，下载时按保存条件重新计数。
 * 过滤器不包含租户，租户永远来自已认证主体。
 *
 * @param occurredFrom 起始时间（带偏移，含）
 * @param occurredTo 结束时间（带偏移，含）
 * @param action 精确匹配的动作名，可为空
 * @param actorId 精确匹配的操作者账号 ID，可为空
 * @param targetType 目标类型，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果，可为空
 */
public record AuditQueryFilter(
        OffsetDateTime occurredFrom,
        OffsetDateTime occurredTo,
        String action,
        UUID actorId,
        AuditTargetType targetType,
        String targetId,
        AuditOutcome outcome) {}
