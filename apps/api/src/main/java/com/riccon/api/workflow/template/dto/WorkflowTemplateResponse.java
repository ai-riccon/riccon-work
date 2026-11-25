package com.riccon.api.workflow.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.template.domain.WorkflowTemplateEntry;
import com.riccon.platform.domain.workflow.WorkflowTemplateSource;
import com.riccon.platform.domain.workflow.WorkflowTemplateVisibility;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 模板列表项（契约 {@code WorkflowTemplate}）。
 *
 * <p>M06 只返回内置目录：{@code source}/{@code visibility} 恒为 {@code BUILT_IN}；{@code upgradeAvailable}
 * 恒为 {@code false}，升级提示与 {@code latestBuiltInVersion} 由 M06b 计算（ADR-0024 §12，AC-10/25）。
 * 不返回图 JSON、节点配置或工具参数，避免把模板内部实现暴露给无编辑权限的读者。
 *
 * @param id 模板 ID
 * @param name 展示名
 * @param scenario 适用场景
 * @param description 说明；可为空
 * @param source 模板来源
 * @param visibility 可见范围
 * @param ownerUserId 所有者；内置模板为空
 * @param builtInVersion 内置版本
 * @param latestBuiltInVersion 最新内置版本；M06 不做升级检测，恒为空
 * @param upgradeAvailable 是否有升级；M06 恒为 {@code false}
 * @param updatedAt 最近更新时间（+08:00）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowTemplateResponse(
        UUID id,
        String name,
        String scenario,
        String description,
        WorkflowTemplateSource source,
        WorkflowTemplateVisibility visibility,
        UUID ownerUserId,
        Integer builtInVersion,
        Integer latestBuiltInVersion,
        boolean upgradeAvailable,
        OffsetDateTime updatedAt) {

    /** 对外时间偏移（Asia/Shanghai，UTC+08:00）。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 从目录条目构造列表项。
     *
     * @param entry 目录条目
     * @return 列表项
     */
    public static WorkflowTemplateResponse from(WorkflowTemplateEntry entry) {
        return new WorkflowTemplateResponse(
                entry.id(),
                entry.name(),
                entry.scenario(),
                entry.description(),
                WorkflowTemplateSource.BUILT_IN,
                entry.visibility(),
                null,
                entry.version(),
                null,
                false,
                display(entry.updatedAt()));
    }

    /**
     * 把 UTC 时间转换为带 +08:00 偏移的展示时间。
     *
     * @param instant UTC 时间；可空
     * @return 展示时间或 {@code null}
     */
    private static OffsetDateTime display(Instant instant) {
        return instant == null ? null : instant.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }
}
