package com.riccon.api.knowledge.connector.domain;

/**
 * 最近一次同步计数（{@code kb_connector.last_sync_summary}）。
 *
 * <p>{@code complete=false} 表示 list 不完整或触发上限，此时不得传播删除（ADR-0023 第 6.3 节）。
 *
 * @param scanned 扫描对象数
 * @param created 新建文档数
 * @param updated 内容更新文档数
 * @param skipped 跳过对象数
 * @param failed 失败对象数
 * @param deleted 删除传播数
 * @param complete list 是否完整
 */
public record ConnectorSyncSummary(
        long scanned, long created, long updated, long skipped, long failed, long deleted, boolean complete) {}
