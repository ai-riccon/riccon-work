package com.riccon.api.research.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批入参脱敏预览的响应侧护栏。
 *
 * <p>完整参数只以密文保存在服务端（ADR-0017），返回给审批 UI 的 {@code paramsPreview} 必须满足 OpenAPI 的
 * {@code maxProperties: 16}。Worker 写入时已做键级白名单，这里再按契约上限截断，避免历史行或未来回归把超限
 * 预览直接透出；本类不做语义脱敏，脱敏仍由写入方负责。
 */
final class PreviewMaps {

    /** OpenAPI {@code paramsPreview} 允许的最大顶层键数。 */
    private static final int MAX_PROPERTIES = 16;

    /** 工具类禁止实例化。 */
    private PreviewMaps() {}

    /**
     * 截断预览到契约上限。
     *
     * @param preview 原始预览；可为空
     * @return 不超过 16 个键的不可变副本
     */
    static Map<String, Object> limit(Map<String, Object> preview) {
        if (preview == null || preview.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> limited = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : preview.entrySet()) {
            if (limited.size() >= MAX_PROPERTIES) {
                break;
            }
            if (entry.getKey() != null) {
                limited.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(limited);
    }
}
