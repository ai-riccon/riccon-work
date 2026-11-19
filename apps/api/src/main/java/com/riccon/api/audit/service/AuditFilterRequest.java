package com.riccon.api.audit.service;

/**
 * 导出申请过滤器原文（与查询过滤器同构）。
 *
 * <p>字段全部为可空原文，由 {@link AuditQueryFilters} 统一校验与固化；服务层不信任客户端自报行数或租户。
 *
 * @param occurredFrom 起始时间原文，可为空
 * @param occurredTo 结束时间原文，可为空
 * @param action 动作名，可为空
 * @param actorId 操作者 ID 原文，可为空
 * @param targetType 目标类型原文，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果原文，可为空
 */
public record AuditFilterRequest(
        String occurredFrom,
        String occurredTo,
        String action,
        String actorId,
        String targetType,
        String targetId,
        String outcome) {}
