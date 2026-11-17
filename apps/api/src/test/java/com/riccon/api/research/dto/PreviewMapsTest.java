package com.riccon.api.research.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 审批预览响应护栏单元测试。
 *
 * <p>验证空预览与超限预览都满足 OpenAPI {@code maxProperties: 16}，避免历史行或回归把超限数据透出。
 */
class PreviewMapsTest {

    /** 空预览返回空不可变 Map。 */
    @Test
    void limit_nullOrEmpty_returnsEmpty() {
        assertThat(PreviewMaps.limit(null)).isEmpty();
        assertThat(PreviewMaps.limit(Map.of())).isEmpty();
    }

    /** 超过 16 个键时只保留前 16 个。 */
    @Test
    void limit_tooManyKeys_truncatesTo16() {
        Map<String, Object> preview = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            preview.put("key" + index, "value" + index);
        }
        Map<String, Object> limited = PreviewMaps.limit(preview);
        assertThat(limited).hasSize(16);
        assertThat(limited).containsEntry("key0", "value0").doesNotContainKey("key16");
    }

    /** JSON null 值必须保留；Map.copyOf 会拒绝 null，不能用它做防御性复制。 */
    @Test
    void limit_nullValue_isPreserved() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("region", null);
        assertThat(PreviewMaps.limit(preview)).containsEntry("region", null);
    }
}
