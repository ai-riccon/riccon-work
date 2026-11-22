package com.riccon.api.model.routing.domain;

import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 单个用途的模型路由链读模型。
 *
 * <p>链头行保存 {@code version} 乐观锁与最近修改人；条目按 {@code position} 升序。本模型只做读，写入口是
 * 平台管理员 API，且保存后下一次模型请求立即读取新链（ADR-0021 §4.3）。
 *
 * @param purpose 用途
 * @param version 乐观锁版本
 * @param updatedAt 最近保存时间（UTC）
 * @param updatedBy 最近修改的平台管理员账号 ID；可为空
 * @param entries 有序条目；空链返回空列表
 */
public record RoutingChain(
        ModelPurpose purpose, int version, Instant updatedAt, UUID updatedBy, List<RoutingEntry> entries) {

    /** 防御性复制，避免读模型被调用方修改。 */
    public RoutingChain {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }
}
