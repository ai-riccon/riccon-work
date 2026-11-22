package com.riccon.api.admin.model.gateway.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 全局网关开关行（{@code model_gateway_switch}）。
 *
 * <p>该表按开关键存三行，每行独立保存 {@code version}、修改人与修改时间；管理端把它们作为一个整组读写，
 * 因此服务层会用三行中最大的版本号作为整组版本，并在保存时要求三行都按同一期望版本推进（ADR-0021 §3.4）。
 *
 * @param key 开关键：routingMode、failoverEnabled、quotaEnforcementEnabled
 * @param value 数据库原始值
 * @param version 该行乐观锁版本
 * @param updatedBy 最近修改的平台管理员账号 ID；迁移种子为空
 * @param updatedAt 最近修改时间（UTC）
 */
public record GatewaySwitchRow(String key, String value, int version, UUID updatedBy, Instant updatedAt) {}
