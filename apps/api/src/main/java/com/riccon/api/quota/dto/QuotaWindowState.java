package com.riccon.api.quota.dto;

import com.riccon.api.quota.domain.QuotaLimitSource;
import com.riccon.api.quota.domain.QuotaWindow;

/**
 * 单个窗口的配额状态，对应 OpenAPI {@code QuotaWindowState}。
 *
 * @param window 窗口
 * @param limit 生效限额；{@code null} 表示不限
 * @param used 已用量
 * @param resetsAt 下一次重置时间（Asia/Shanghai，带偏移）
 * @param source 限额来源
 */
public record QuotaWindowState(QuotaWindow window, Integer limit, int used, String resetsAt, QuotaLimitSource source) {}
