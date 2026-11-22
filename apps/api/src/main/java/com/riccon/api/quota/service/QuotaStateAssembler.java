package com.riccon.api.quota.service;

import com.riccon.api.quota.config.QuotaProperties;
import com.riccon.api.quota.domain.QuotaEffectiveLimit;
import com.riccon.api.quota.domain.QuotaLimitRow;
import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaUsageRow;
import com.riccon.api.quota.domain.QuotaWindow;
import com.riccon.api.quota.domain.QuotaWindowBoundary;
import com.riccon.api.quota.domain.QuotaWindows;
import com.riccon.api.quota.dto.QuotaWindowState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把限额行、用量行与窗口边界组装为响应状态。
 *
 * <p>租户端与平台端共用同一解析逻辑，避免两套默认值/来源口径漂移；顺序固定 DAILY、MONTHLY。
 */
public final class QuotaStateAssembler {

    /** 工具类禁止实例化。 */
    private QuotaStateAssembler() {}

    /**
     * 组装窗口状态。
     *
     * @param boundaries 窗口边界
     * @param scope 维度
     * @param limits 显式限额行
     * @param usages 用量行
     * @param quotaProperties 部署默认值
     * @return 窗口状态
     */
    public static List<QuotaWindowState> assemble(
            List<QuotaWindowBoundary> boundaries,
            QuotaScope scope,
            List<QuotaLimitRow> limits,
            List<QuotaUsageRow> usages,
            QuotaProperties quotaProperties) {
        Map<QuotaWindow, Integer> explicit = new HashMap<>();
        for (QuotaLimitRow limit : limits) {
            explicit.put(limit.window(), limit.limit());
        }
        Map<QuotaWindow, Integer> usedByWindow = new HashMap<>();
        for (QuotaUsageRow usage : usages) {
            usedByWindow.put(usage.window(), usage.used());
        }
        List<QuotaWindowState> states = new ArrayList<>(boundaries.size());
        for (QuotaWindowBoundary boundary : boundaries) {
            boolean explicitPresent = explicit.containsKey(boundary.window());
            QuotaEffectiveLimit effective =
                    quotaProperties.resolve(scope, boundary.window(), explicitPresent, explicit.get(boundary.window()));
            states.add(new QuotaWindowState(
                    boundary.window(),
                    effective.limit(),
                    usedByWindow.getOrDefault(boundary.window(), 0),
                    QuotaWindows.format(boundary.resetsAt().toInstant()),
                    effective.source()));
        }
        return states;
    }
}
