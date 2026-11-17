package com.riccon.api.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.error.BadRequestException;
import org.junit.jupiter.api.Test;

/** 分页参数校验。 */
class PageQueryTest {

    /** 缺省值必须为 page=1、size=20。 */
    @Test
    void defaultsAreApplied() {
        PageQuery page = PageQuery.of(null, null);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.offset()).isZero();
    }

    /** 非法页码与每页大小必须拒绝。 */
    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> PageQuery.of(0, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageQuery.of(1, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageQuery.of(1, 101)).isInstanceOf(BadRequestException.class);
    }

    /** 总页数向上取整，空结果为零。 */
    @Test
    void totalPagesRoundsUp() {
        PageQuery page = PageQuery.of(1, 20);
        assertThat(page.totalPages(0)).isZero();
        assertThat(page.totalPages(1)).isEqualTo(1);
        assertThat(page.totalPages(21)).isEqualTo(2);
        assertThat(page.offset()).isZero();
        assertThat(PageQuery.of(3, 10).offset()).isEqualTo(20);
    }
}
