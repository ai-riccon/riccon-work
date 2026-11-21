package com.riccon.api.admin.model.gateway.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 网关开关响应（OpenAPI {@code ModelGatewaySwitches}）。
 *
 * <p>值为“当前生效值”：环境变量覆盖存在时，被覆盖的开关返回部署值，其余开关回落到数据库。
 * {@code source=ENVIRONMENT} 表示至少一个开关被环境变量覆盖，此时写接口固定 409；
 * {@code envOverride} 只列出实际被覆盖的开关名与值，未覆盖时返回 {@code null}（ADR-0021 §3.4）。
 *
 * @param routingMode 生效路由模式
 * @param failoverEnabled 生效降级开关
 * @param quotaEnforcementEnabled 生效配额执行开关
 * @param version 整组乐观锁版本（数据库三行中的最大版本）
 * @param source 生效来源：DATABASE 或 ENVIRONMENT
 * @param envOverride 环境变量覆盖的开关名与值；无覆盖时为空
 * @param updatedAt 最近修改时间（Asia/Shanghai，带 +08:00 偏移）
 * @param updatedBy 最近修改的平台管理员账号 ID；迁移种子为空
 */
public record ModelGatewaySwitchResponse(
        String routingMode,
        boolean failoverEnabled,
        boolean quotaEnforcementEnabled,
        int version,
        String source,
        Map<String, Object> envOverride,
        OffsetDateTime updatedAt,
        UUID updatedBy) {}
