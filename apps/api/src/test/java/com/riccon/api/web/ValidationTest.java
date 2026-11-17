package com.riccon.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.error.BadRequestException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 请求字段校验。 */
class ValidationTest {

    /** 文本去除首尾空白并校验长度。 */
    @Test
    void requireTextTrimsAndValidatesLength() {
        assertThat(Validation.requireText("  alice  ", "username", 1, 10)).isEqualTo("alice");
        assertThatThrownBy(() -> Validation.requireText(null, "username", 1, 10))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Validation.requireText("  ", "username", 1, 10))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Validation.requireText("abcdefghijk", "username", 1, 10))
                .isInstanceOf(BadRequestException.class);
    }

    /** 模式校验拒绝不匹配值。 */
    @Test
    void requirePatternValidatesFormat() {
        Pattern pattern = Pattern.compile("^[a-z]+$");
        assertThat(Validation.requirePattern("abc", "code", pattern, 10)).isEqualTo("abc");
        assertThatThrownBy(() -> Validation.requirePattern("ABC", "code", pattern, 10))
                .isInstanceOf(BadRequestException.class);
    }

    /** 角色列表必须非空、去重且元素非空。 */
    @Test
    void requireRolesRejectsEmptyAndDuplicates() {
        assertThat(Validation.requireRoles(List.of("MEMBER", "TENANT_ADMIN"), "roles"))
                .containsExactly("MEMBER", "TENANT_ADMIN");
        assertThatThrownBy(() -> Validation.requireRoles(List.of(), "roles")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Validation.requireRoles(List.of("MEMBER", "MEMBER"), "roles"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Validation.requireRoles(java.util.Arrays.asList("MEMBER", null), "roles"))
                .isInstanceOf(BadRequestException.class);
    }

    /** 可选范围校验。 */
    @Test
    void requireRangeAllowsNullAndRejectsOutOfRange() {
        assertThat(Validation.requireRange(null, "days", 30, 3650)).isNull();
        assertThat(Validation.requireRange(90, "days", 30, 3650)).isEqualTo(90);
        assertThatThrownBy(() -> Validation.requireRange(29, "days", 30, 3650)).isInstanceOf(BadRequestException.class);
    }

    /** 必填对象。 */
    @Test
    void requireNonNullRejectsNull() {
        assertThat(Validation.requireNonNull("x", "field")).isEqualTo("x");
        assertThatThrownBy(() -> Validation.requireNonNull(null, "field")).isInstanceOf(BadRequestException.class);
    }
}
