package com.riccon.api.audit.service;

import com.riccon.api.audit.repository.AuditQueryFilter;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已准备好的流式导出描述。
 *
 * <p>在写 CSV 前完成申请人、状态、过期与行数校验，并先写 {@code data.exported} 审计；CSV 生成阶段不再改变
 * 授权结论（ADR-0020 第 2 节）。
 *
 * @param exportId 导出请求 ID
 * @param filter 已固化过滤器
 * @param filtersJson 规范化过滤器 JSON（写入 CSV 元数据）
 * @param filtersHash 过滤器 SHA-256
 * @param rowCount 下载前重新计数的实际行数
 * @param generatedAt 生成时间（带偏移）
 */
public record AuditDownload(
        UUID exportId,
        AuditQueryFilter filter,
        String filtersJson,
        String filtersHash,
        long rowCount,
        OffsetDateTime generatedAt) {}
