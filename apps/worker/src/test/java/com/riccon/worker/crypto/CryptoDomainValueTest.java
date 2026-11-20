package com.riccon.worker.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.crypto.domain.TenantDataKeyRecord;
import org.junit.jupiter.api.Test;

/**
 * 加密域值对象的边界分支。
 *
 * <p>覆盖 {@link SecretValue} 的空值/空白校验、脱敏与相等比较，以及 {@link TenantDataKeyRecord}
 * 对可空数组的防御性复制分支；只断言既有生产行为，不改变实现。
 */
class CryptoDomainValueTest {

    /** null 明文被拒绝，避免空凭据进入解密链路。 */
    @Test
    void secretValue_nullValue_rejected() {
        assertThatThrownBy(() -> new SecretValue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /** 空串与全空白分别命中 isBlank 分支。 */
    @Test
    void secretValue_blankValue_rejected() {
        assertThatThrownBy(() -> new SecretValue(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new SecretValue("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /** 相等比较覆盖 null、相同与不同三种分支，且不依赖对象身份。 */
    @Test
    void secretValue_matches_handlesNullEqualityAndDifference() {
        SecretValue value = new SecretValue("sk-test-0123456789abcdef");

        assertThat(value.matches(null)).isFalse();
        assertThat(value.matches(new SecretValue("sk-test-0123456789abcdef"))).isTrue();
        assertThat(value.matches(new SecretValue("sk-test-ffffffffffffffff"))).isFalse();
    }

    /** toString 与 reveal 语义：日志只能看到脱敏占位。 */
    @Test
    void secretValue_toStringIsRedacted() {
        SecretValue value = new SecretValue("sk-test-0123456789abcdef");

        assertThat(value.toString()).isEqualTo(SecretValue.REDACTED).doesNotContain("sk-test");
        assertThat(value.reveal()).isEqualTo("sk-test-0123456789abcdef");
    }

    /** 记录对 null 数组保持 null，覆盖防御性复制的空值分支。 */
    @Test
    void tenantDataKeyRecord_nullArrays_arePreserved() {
        TenantDataKeyRecord record =
                new TenantDataKeyRecord("platform", "dek-1", "model-credential", null, null, "AES-256-GCM", "mk-test");

        assertThat(record.wrappedDek()).isNull();
        assertThat(record.wrapNonce()).isNull();
    }
}
