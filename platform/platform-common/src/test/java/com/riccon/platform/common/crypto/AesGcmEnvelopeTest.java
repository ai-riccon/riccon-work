package com.riccon.platform.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link AesGcmEnvelope}、{@link EnvelopeKey} 与 {@link EnvelopeKeyRing} 的安全语义测试。 */
class AesGcmEnvelopeTest {

    /** 审批 AAD 中的租户。 */
    private static final String TENANT = "tenant-a";

    /** 审批 AAD 中的审批 ID。 */
    private static final String APPROVAL_ID = "0193f6b4-6a4c-7c1e-9d3a-5f0b2c8e4a10";

    /** 测试明文。 */
    private static final byte[] PLAINTEXT =
            "{\"market\":\"企业协同办公\",\"region\":\"华东\"}".getBytes(StandardCharsets.UTF_8);

    /** 当前测试密钥。 */
    private static final EnvelopeKey CURRENT = key("dk-current", 1);

    /** 轮换窗口内的上一把测试密钥。 */
    private static final EnvelopeKey PREVIOUS = key("dk-previous", 2);

    /** 生成固定测试密钥；只允许出现在 src/test。 */
    private static EnvelopeKey key(String keyId, int fill) {
        byte[] secret = new byte[AesGcmEnvelope.KEY_BYTES];
        Arrays.fill(secret, (byte) fill);
        return new EnvelopeKey(keyId, secret);
    }

    /** 加密后应能用同一密钥与 AAD 还原明文，并携带 key id。 */
    @Test
    void sealAndOpenRoundTrip() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);

        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad);
        byte[] opened = AesGcmEnvelope.open(CURRENT, sealed.ciphertext(), aad);

        assertThat(sealed.keyId()).isEqualTo("dk-current");
        assertThat(sealed.ciphertext()).hasSize(AesGcmEnvelope.MIN_PAYLOAD_BYTES + PLAINTEXT.length);
        assertThat(opened).isEqualTo(PLAINTEXT);
    }

    /** 空明文也应产生合法载荷并正确还原。 */
    @Test
    void sealAndOpenEmptyPlaintext() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);

        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(CURRENT, new byte[0], aad);

        assertThat(sealed.ciphertext()).hasSize(AesGcmEnvelope.MIN_PAYLOAD_BYTES);
        assertThat(AesGcmEnvelope.open(CURRENT, sealed.ciphertext(), aad)).isEmpty();
    }

    /** 每次加密必须使用新 nonce，禁止确定性密文。 */
    @Test
    void sealUsesFreshNonce() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);

        byte[] first = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();
        byte[] second = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();

        assertThat(first).isNotEqualTo(second);
        assertThat(Arrays.copyOfRange(first, 1, 1 + AesGcmEnvelope.NONCE_BYTES))
                .isNotEqualTo(Arrays.copyOfRange(second, 1, 1 + AesGcmEnvelope.NONCE_BYTES));
    }

    /** 密文任意位被篡改必须认证失败。 */
    @Test
    void openRejectsTamperedCiphertext() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        byte[] payload = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();
        payload[payload.length - 1] ^= 0x01;

        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, payload, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope authentication failed");
    }

    /** nonce 被篡改同样必须失败，不能返回错误明文。 */
    @Test
    void openRejectsTamperedNonce() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        byte[] payload = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();
        payload[1] ^= 0x01;

        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, payload, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope authentication failed");
    }

    /** 不支持的版本必须在解密前拒绝，避免把未来格式当当前格式解析。 */
    @Test
    void openRejectsUnsupportedVersion() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        byte[] payload = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();
        payload[0] = 2;

        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, payload, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope version is not supported");
    }

    /** 载荷为空、过短或 null 时必须失败。 */
    @Test
    void openRejectsMalformedPayload() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        byte[] tooShort = new byte[AesGcmEnvelope.MIN_PAYLOAD_BYTES - 1];

        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, null, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope format is invalid");
        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, new byte[0], aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope format is invalid");
        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, tooShort, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope format is invalid");
    }

    /** 错误密钥即使 key id 相同也必须认证失败。 */
    @Test
    void openRejectsWrongKey() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad);
        EnvelopeKey wrong = key("dk-current", 9);

        assertThatThrownBy(() -> AesGcmEnvelope.open(wrong, sealed.ciphertext(), aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope authentication failed");
    }

    /** 错误 AAD（换审批或换租户）必须认证失败。 */
    @Test
    void openRejectsWrongAad() {
        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, AesGcmEnvelope.aad(TENANT, APPROVAL_ID));
        byte[] otherApproval = AesGcmEnvelope.aad(TENANT, "0193f6b4-6a4c-7c1e-9d3a-000000000000");
        byte[] otherTenant = AesGcmEnvelope.aad("tenant-b", APPROVAL_ID);

        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, sealed.ciphertext(), otherApproval))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope authentication failed");
        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, sealed.ciphertext(), otherTenant))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope authentication failed");
    }

    /** 加密参数缺失时必须在入口拒绝。 */
    @Test
    void sealRejectsMissingArguments() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);

        assertThatThrownBy(() -> AesGcmEnvelope.seal(null, PLAINTEXT, aad)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AesGcmEnvelope.seal(CURRENT, null, aad)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AesGcmEnvelope.seal(CURRENT, PLAINTEXT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("aad must not be empty");
        assertThatThrownBy(() -> AesGcmEnvelope.seal(CURRENT, PLAINTEXT, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("aad must not be empty");
    }

    /** 解密参数缺失时必须在入口拒绝。 */
    @Test
    void openRejectsMissingArguments() {
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        byte[] payload = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad).ciphertext();

        assertThatThrownBy(() -> AesGcmEnvelope.open((EnvelopeKey) null, payload, aad))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AesGcmEnvelope.open(CURRENT, payload, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("aad must not be empty");
        assertThatThrownBy(() -> AesGcmEnvelope.open((EnvelopeKeyRing) null, "dk-current", payload, aad))
                .isInstanceOf(NullPointerException.class);
    }

    /** 密钥环应按 key id 解析当前与历史密钥。 */
    @Test
    void keyRingResolvesCurrentAndPrevious() {
        EnvelopeKeyRing ring = new EnvelopeKeyRing("dk-current", List.of(PREVIOUS, CURRENT));
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(PREVIOUS, PLAINTEXT, aad);

        assertThat(ring.current()).isEqualTo(CURRENT);
        assertThat(ring.keyIds()).containsExactlyInAnyOrder("dk-current", "dk-previous");
        assertThat(AesGcmEnvelope.open(ring, sealed.keyId(), sealed.ciphertext(), aad))
                .isEqualTo(PLAINTEXT);
    }

    /** 未知 key id 必须失败关闭，不能尝试其他密钥。 */
    @Test
    void keyRingRejectsUnknownKeyId() {
        EnvelopeKeyRing ring = new EnvelopeKeyRing("dk-current", List.of(CURRENT));
        byte[] aad = AesGcmEnvelope.aad(TENANT, APPROVAL_ID);
        AesGcmEnvelope.Sealed sealed = AesGcmEnvelope.seal(CURRENT, PLAINTEXT, aad);

        assertThatThrownBy(() -> AesGcmEnvelope.open(ring, "dk-retired", sealed.ciphertext(), aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope key id is not available");
        assertThatThrownBy(() -> AesGcmEnvelope.open(ring, null, sealed.ciphertext(), aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope key id is not available");
        assertThatThrownBy(() -> AesGcmEnvelope.open(ring, " ", sealed.ciphertext(), aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("envelope key id is not available");
        assertThatThrownBy(() -> ring.require("dk-retired")).isInstanceOf(EnvelopeException.class);
    }

    /** 密钥环禁止空集合、缺失当前密钥与重复 key id。 */
    @Test
    void keyRingRejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new EnvelopeKeyRing("dk-current", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one envelope key is required");
        assertThatThrownBy(() -> new EnvelopeKeyRing("dk-missing", List.of(CURRENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current envelope key id is not present: dk-missing");
        assertThatThrownBy(() -> new EnvelopeKeyRing("dk-current", List.of(CURRENT, key("dk-current", 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate envelope key id: dk-current");
        assertThatThrownBy(() -> new EnvelopeKeyRing("dk-current", Arrays.asList(CURRENT, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnvelopeKeyRing(null, List.of(CURRENT))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnvelopeKeyRing("dk-current", null)).isInstanceOf(NullPointerException.class);
    }

    /** key id 必须满足字符集与长度约束。 */
    @Test
    void envelopeKeyRejectsInvalidKeyId() {
        byte[] secret = new byte[AesGcmEnvelope.KEY_BYTES];

        assertThatThrownBy(() -> new EnvelopeKey(null, secret)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnvelopeKey("", secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId must be 1-64 characters");
        assertThatThrownBy(() -> new EnvelopeKey("bad id", secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId must be 1-64 characters");
        assertThatThrownBy(() -> new EnvelopeKey("a".repeat(65), secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId must be 1-64 characters");
        assertThatThrownBy(() -> new EnvelopeKey("bad|id", secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId must be 1-64 characters");
    }

    /** 密钥长度必须恰为 32 字节。 */
    @Test
    void envelopeKeyRejectsInvalidSecretLength() {
        assertThatThrownBy(() -> new EnvelopeKey("dk-1", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnvelopeKey("dk-1", new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secret must be exactly 32 bytes");
        assertThatThrownBy(() -> new EnvelopeKey("dk-1", new byte[33]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secret must be exactly 32 bytes");
    }

    /** 密钥材料必须防御性复制，构造后修改入参不得影响密钥。 */
    @Test
    void envelopeKeyDefensiveCopiesSecret() {
        byte[] secret = new byte[AesGcmEnvelope.KEY_BYTES];
        EnvelopeKey key = new EnvelopeKey("dk-1", secret);
        secret[0] = 7;
        byte[] returned = key.secret();
        returned[1] = 9;

        assertThat(key.secret()[0]).isZero();
        assertThat(key.secret()[1]).isZero();
    }

    /** 密钥相等性包含 key id 与密钥材料。 */
    @Test
    void envelopeKeyEqualsAndHashCode() {
        EnvelopeKey same = new EnvelopeKey("dk-current", CURRENT.secret());
        EnvelopeKey otherSecret = key("dk-current", 5);
        EnvelopeKey otherId = key("dk-other", 1);

        assertThat(CURRENT).isEqualTo(CURRENT).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(CURRENT).isNotEqualTo(otherSecret).isNotEqualTo(otherId).isNotEqualTo("dk-current");
        assertThat(CURRENT).isNotEqualTo(null);
    }

    /** toString 只暴露 key id，不输出密钥材料。 */
    @Test
    void envelopeKeyToStringOmitsSecret() {
        assertThat(CURRENT.toString()).isEqualTo("EnvelopeKey[keyId=dk-current]");
    }

    /** AAD 必须是稳定的 UTF-8 拼接。 */
    @Test
    void aadBuildsStableBytes() {
        assertThat(AesGcmEnvelope.aad(TENANT, APPROVAL_ID))
                .isEqualTo((TENANT + "|" + APPROVAL_ID).getBytes(StandardCharsets.UTF_8));
    }

    /** AAD 片段为空或包含分隔符时必须拒绝，避免拼接歧义。 */
    @Test
    void aadRejectsInvalidParts() {
        assertThatThrownBy(() -> AesGcmEnvelope.aad(null, APPROVAL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not be blank");
        assertThatThrownBy(() -> AesGcmEnvelope.aad(" ", APPROVAL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not be blank");
        assertThatThrownBy(() -> AesGcmEnvelope.aad("tenant|a", APPROVAL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId must not contain '|'");
        assertThatThrownBy(() -> AesGcmEnvelope.aad(TENANT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("approvalId must not be blank");
        assertThatThrownBy(() -> AesGcmEnvelope.aad(TENANT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("approvalId must not be blank");
        assertThatThrownBy(() -> AesGcmEnvelope.aad(TENANT, "approval|1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("approvalId must not contain '|'");
    }

    /** 加密结果必须防御性复制密文。 */
    @Test
    void sealedDefensiveCopiesCiphertext() {
        byte[] payload = new byte[AesGcmEnvelope.MIN_PAYLOAD_BYTES];
        payload[0] = 1;
        AesGcmEnvelope.Sealed sealed = new AesGcmEnvelope.Sealed("dk-1", payload);
        payload[1] = 7;
        byte[] returned = sealed.ciphertext();
        returned[2] = 9;

        assertThat(sealed.ciphertext()[1]).isZero();
        assertThat(sealed.ciphertext()[2]).isZero();
        assertThat(sealed.keyId()).isEqualTo("dk-1");
    }

    /** 加密结果禁止 null 字段。 */
    @Test
    void sealedRejectsNullFields() {
        assertThatThrownBy(() -> new AesGcmEnvelope.Sealed(null, new byte[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AesGcmEnvelope.Sealed("dk-1", null)).isInstanceOf(NullPointerException.class);
    }
}
