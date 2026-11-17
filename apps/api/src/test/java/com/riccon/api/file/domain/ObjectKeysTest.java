package com.riccon.api.file.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 对象 key 格式与租户前缀校验。 */
class ObjectKeysTest {

    /** key 固定带租户前缀、类别与 UTC 年份。 */
    @Test
    void fileKey_formatsTenantCategoryYearAndId() {
        UUID fileId = UUID.fromString("01930000-0000-7000-8000-000000000001");
        String key = ObjectKeys.fileKey("tenant-a", fileId, Instant.parse("2026-11-17T10:00:00Z"));
        assertThat(key).isEqualTo("tenants/tenant-a/file/2026/" + fileId);
    }

    /** 前缀校验拒绝其他租户、空值与纯对象名。 */
    @Test
    void requireTenantPrefix_rejectsOutsideTenant() {
        assertThatThrownBy(() -> ObjectKeys.requireTenantPrefix("tenant-a", "tenants/tenant-b/file/2026/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside tenant prefix");
        assertThatThrownBy(() -> ObjectKeys.requireTenantPrefix("tenant-a", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectKeys.requireTenantPrefix("tenant-a", "file/2026/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
