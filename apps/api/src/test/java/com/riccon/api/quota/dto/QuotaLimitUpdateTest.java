package com.riccon.api.quota.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.quota.domain.QuotaInvalidRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 配额整体替换请求的解析与校验（OpenAPI {@code QuotaUpdateRequest}）。
 */
class QuotaLimitUpdateTest {

    /** JSON 解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 两个键都存在时接受整数与 null。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void parse_validBody_returnsLimits() throws Exception {
        QuotaLimitUpdate update = QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":5,\"monthly\":null}}"));

        assertThat(update.daily()).isEqualTo(5);
        assertThat(update.monthly()).isNull();
    }

    /**
     * 缺月度键返回配额 400。
     */
    @Test
    void parse_missingMonthly_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":5}}")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("daily and monthly");
    }

    /**
     * 顶层出现未知键返回配额 400。
     */
    @Test
    void parse_unknownTopLevelKey_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":5,\"monthly\":1},\"x\":1}")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("only contain");
    }

    /**
     * limits 内含未知键返回配额 400。
     */
    @Test
    void parse_unknownLimitKey_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":5,\"monthly\":1,\"weekly\":1}}")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("daily and monthly");
    }

    /**
     * 负数返回配额 400。
     */
    @Test
    void parse_negative_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":-1,\"monthly\":1}}")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("negative");
    }

    /**
     * 小数返回配额 400。
     */
    @Test
    void parse_decimal_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("{\"limits\":{\"daily\":1.5,\"monthly\":1}}")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("integer");
    }

    /**
     * 非对象请求体返回配额 400。
     */
    @Test
    void parse_nonObject_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(json("[]")))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("object");
    }

    /**
     * 空请求体返回配额 400。
     */
    @Test
    void parse_nullBody_fails() {
        assertThatThrownBy(() -> QuotaLimitUpdate.parse(null))
                .isInstanceOf(QuotaInvalidRequestException.class)
                .hasMessageContaining("object");
    }

    /**
     * 解析 JSON 文本。
     *
     * @param text JSON
     * @return 树
     */
    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
