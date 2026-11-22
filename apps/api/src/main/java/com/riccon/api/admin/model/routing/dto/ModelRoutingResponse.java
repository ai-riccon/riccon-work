package com.riccon.api.admin.model.routing.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 单条路由链响应（OpenAPI {@code ModelRouting}）。
 *
 * <p>{@code updatedAt} 使用带 {@code +08:00} 偏移的 ISO 8601；{@code updatedBy} 可为空（迁移种子没有操作者）。
 * 停用模型仍保留在 {@code entries} 中并携带 {@code status=DISABLED}，供管理端展示与运行时跳过
 * （ADR-0021 §4.1）。
 *
 * @param purpose 用途
 * @param version 乐观锁版本
 * @param entries 有序条目
 * @param updatedAt 最近保存时间
 * @param updatedBy 最近修改的平台管理员账号 ID；可为空
 */
public record ModelRoutingResponse(
        String purpose,
        int version,
        List<ModelRoutingEntryResponse> entries,
        OffsetDateTime updatedAt,
        UUID updatedBy) {}
