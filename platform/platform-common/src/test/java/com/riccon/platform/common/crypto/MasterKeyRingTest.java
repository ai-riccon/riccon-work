package com.riccon.platform.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@link MasterKeyRing} 的启动校验、包裹/解包、指纹与脱敏语义。
 *
 * <p>测试主密钥全部是仓库内的占位值，不对应任何真实环境；断言重点是可失败性：缺失/非法配置必须
 * 启动失败，AAD 或密文被篡改必须认证失败，异常与 {@code toString()} 不得包含密钥材料。
 */
class MasterKeyRingTest {

    /** 测试当前主密钥（非仓库夹具、非生产；避开已知测试密钥清单）。 */
    private static final String CURRENT = base64Of(32, (byte) 0x33);

    /** 测试上一把主密钥（非仓库夹具、非生产；避开已知测试密钥清单）。 */
    private static final String PREVIOUS = base64Of(32, (byte) 0x44);

    /** 测试指纹 pepper（32 字节 0x07，仅测试）。 */
    private static final String PEPPER = base64Of(32, (byte) 7);

    /** 已知测试密钥清单中的第一个值；默认必须拒绝。 */
    private static final String KNOWN_TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** 仓库实际使用的测试主密钥（application-test.yml / TestCredentials / WorkerIntegrationTestBase）。 */
    private static final String REPOSITORY_TEST_KEY = "cmljY29uLXdvcmtlci10ZXN0LW1hc3Rlci1rZXktMzI=";

    /** 仓库实际使用的测试上一把密钥。 */
    private static final String REPOSITORY_PREVIOUS_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 仓库测试主密钥的无填充写法（历史上可绕过精确字符串比较）。 */
    private static final String REPOSITORY_TEST_KEY_UNPADDED = "cmljY29uLXdvcmtlci10ZXN0LW1hc3Rlci1rZXktMzI";

    /** URL-safe 哨兵的标准 Base64 写法（含 {@code +}/{@code /}）。 */
    private static final String URL_SAFE_SENTINEL_STANDARD = "+/v7+/v7+/v7+/v7+/v7+/v7+/v7+/v7+/v7+/v7+/s=";

    /** 同一哨兵的 URL-safe 写法（含 {@code -}/{@code _}），解码后字节相同。 */
    private static final String URL_SAFE_SENTINEL_URL_SAFE = "-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_s=";

    /** 随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成固定填充的 Base64 值。 */
    private static String base64Of(int length, byte fill) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 生成 32 字节随机 DEK。 */
    private static byte[] newDek() {
        byte[] dek = new byte[MasterKeyRing.KEY_BYTES];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /** 构造有效的测试密钥环。 */
    private static MasterKeyRing ring() {
        return MasterKeyRing.load(CURRENT, "", "mk-test", PEPPER, false);
    }

    /** 合法配置装配成功，并携带标识与轮换状态。 */
    @Test
    void load_validConfiguration_isConfigured() {
        MasterKeyRing ring = MasterKeyRing.load(CURRENT, PREVIOUS, "mk-test", PEPPER, true);

        assertThat(ring.configured()).isTrue();
        assertThat(ring.masterKeyId()).isEqualTo("mk-test");
        assertThat(ring.hasPreviousKey()).isTrue();
        assertThat(ring.pepperConfigured()).isTrue();
    }

    /** 未配置 pepper 时仍可加解密，但指纹能力必须失败关闭。 */
    @Test
    void load_withoutPepper_isAllowedButFingerprintFailsClosed() {
        MasterKeyRing ring = MasterKeyRing.load(CURRENT, "", "mk-test", "", true);

        assertThat(ring.pepperConfigured()).isFalse();
        assertThatThrownBy(() -> ring.fingerprint("provider-1", "sk-test-0123456789abcdef"))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("RICCON_CREDENTIAL_FINGERPRINT_PEPPER")
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.PEPPER_MISSING);
    }

    /** 缺失当前主密钥必须拒绝启动，错误只报告环境变量名。 */
    @Test
    void load_missingMasterKey_failsWithoutLeaking() {
        assertThatThrownBy(() -> MasterKeyRing.load("", "", "mk-test", PEPPER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_MASTER_KEY")
                .hasMessageNotContaining(CURRENT)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.MASTER_KEY_MISSING);
    }

    /** 长度不足必须拒绝启动。 */
    @Test
    void load_shortMasterKey_fails() {
        assertThatThrownBy(() -> MasterKeyRing.load("c2hvcnQ=", "", "mk-test", PEPPER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    /** 非法 Base64 必须拒绝启动且不回显原值。 */
    @Test
    void load_invalidBase64_failsWithoutEchoingValue() {
        assertThatThrownBy(() -> MasterKeyRing.load("not-base64!!!", "", "mk-test", PEPPER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64")
                .hasMessageNotContaining("not-base64!!!");
    }

    /** 生产默认拒绝已知测试密钥。 */
    @Test
    void load_knownTestKey_rejectedByDefault() {
        assertThatThrownBy(() -> MasterKeyRing.load(KNOWN_TEST_KEY, "", "mk-test", PEPPER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known test key");
    }

    /** 显式关闭拒绝后允许占位密钥，供测试夹具与 test profile 使用。 */
    @Test
    void load_knownTestKey_allowedWhenRejectionDisabled() {
        MasterKeyRing ring = MasterKeyRing.load(KNOWN_TEST_KEY, "", "mk-test", PEPPER, false);

        assertThat(ring.configured()).isTrue();
    }

    /** 仓库实际使用的测试主密钥/上一把密钥默认必须拒绝（F-001 清单覆盖）。 */
    @Test
    void load_repositoryTestKeys_rejectedByDefault() {
        assertThatThrownBy(() -> MasterKeyRing.load(REPOSITORY_TEST_KEY, "", "mk-test", PEPPER, true))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("known test key")
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.TEST_KEY_REJECTED);
        assertThatThrownBy(() -> MasterKeyRing.load(REPOSITORY_PREVIOUS_KEY, "", "mk-test", PEPPER, true))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("known test key");
    }

    /** 无填充写法解码为同一 32 字节，必须同样命中拒绝，不能按原始字符串比较。 */
    @Test
    void load_unpaddedRepositoryTestKey_rejected() {
        assertThatThrownBy(() -> MasterKeyRing.load(REPOSITORY_TEST_KEY_UNPADDED, "", "mk-test", PEPPER, true))
                .isInstanceOf(MasterKeyConfigException.class)
                .hasMessageContaining("known test key")
                .hasMessageNotContaining(REPOSITORY_TEST_KEY_UNPADDED)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.TEST_KEY_REJECTED);
    }

    /** URL-safe 字母表写法解码后与标准写法相同，也必须被拒绝。 */
    @Test
    void load_urlSafeVariantOfKnownKey_rejected() {
        assertThatThrownBy(() -> MasterKeyRing.load(URL_SAFE_SENTINEL_STANDARD, "", "mk-test", PEPPER, true))
                .isInstanceOf(MasterKeyConfigException.class)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.TEST_KEY_REJECTED);
        assertThatThrownBy(() -> MasterKeyRing.load(URL_SAFE_SENTINEL_URL_SAFE, "", "mk-test", PEPPER, true))
                .isInstanceOf(MasterKeyConfigException.class)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.TEST_KEY_REJECTED);
    }

    /** 显式关闭拒绝时，仓库测试密钥可用于 test profile / 直接构造。 */
    @Test
    void load_repositoryTestKey_allowedWhenRejectionDisabled() {
        MasterKeyRing ring = MasterKeyRing.load(REPOSITORY_TEST_KEY, REPOSITORY_PREVIOUS_KEY, "mk-test", PEPPER, false);

        assertThat(ring.configured()).isTrue();
        assertThat(ring.hasPreviousKey()).isTrue();
    }

    /** 关闭拒绝时，URL-safe 变体仍可正常装配（说明拒绝来自字节比较，而不是解码失败）。 */
    @Test
    void load_urlSafeVariant_allowedWhenRejectionDisabled() {
        MasterKeyRing ring = MasterKeyRing.load(URL_SAFE_SENTINEL_URL_SAFE, "", "mk-test", "", false);

        assertThat(ring.configured()).isTrue();
    }

    /** 非法 pepper 长度必须拒绝启动。 */
    @Test
    void load_shortPepper_fails() {
        assertThatThrownBy(() -> MasterKeyRing.load(CURRENT, "", "mk-test", "c2hvcnQ=", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_CREDENTIAL_FINGERPRINT_PEPPER")
                .hasMessageContaining("32 bytes");
    }

    /** 主密钥标识为空或过长必须拒绝启动。 */
    @Test
    void load_invalidMasterKeyId_fails() {
        assertThatThrownBy(() -> MasterKeyRing.load(CURRENT, "", " ", PEPPER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_MASTER_KEY_ID");
    }

    /** 未配置占位环的任何加密操作必须失败关闭，而不是返回明文或空值。 */
    @Test
    void unconfigured_operationsFailClosed() {
        MasterKeyRing ring = MasterKeyRing.unconfigured("mk-1");

        assertThat(ring.configured()).isFalse();
        assertThatThrownBy(() -> ring.wrap(newDek(), new byte[] {1}))
                .isInstanceOf(MasterKeyConfigException.class)
                .extracting(ex -> ((MasterKeyConfigException) ex).reason())
                .isEqualTo(MasterKeyConfigException.Reason.NOT_CONFIGURED);
        assertThatThrownBy(() -> ring.unwrap(new byte[12], new byte[32], new byte[] {1}))
                .isInstanceOf(MasterKeyConfigException.class);
        assertThatThrownBy(() -> ring.fingerprint("provider-1", "sk-test-0123456789abcdef"))
                .isInstanceOf(MasterKeyConfigException.class);
    }

    /** 包裹后可用同一把主密钥解包，DEK 原样返回。 */
    @Test
    void wrapAndUnwrap_roundTrip() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing ring = ring();

        MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);
        byte[] unwrapped = ring.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad);

        assertThat(unwrapped).isEqualTo(dek);
    }

    /** 轮换窗口内，旧 DEK 可由上一把主密钥解包。 */
    @Test
    void unwrap_previousKey_succeeds() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing writer = MasterKeyRing.load(PREVIOUS, "", "mk-old", PEPPER, false);
        MasterKeyRing reader = MasterKeyRing.load(CURRENT, PREVIOUS, "mk-test", PEPPER, false);

        MasterKeyRing.WrappedDek wrapped = writer.wrap(dek, aad);

        assertThat(reader.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad)).isEqualTo(dek);
    }

    /** AAD 不匹配（跨租户/跨用途）必须认证失败。 */
    @Test
    void unwrap_wrongAad_fails() {
        byte[] dek = newDek();
        MasterKeyRing ring = ring();
        MasterKeyRing.WrappedDek wrapped =
                ring.wrap(dek, CredentialEnvelope.aad("tenant-a", "model-credential", "dek-1"));

        assertThatThrownBy(() -> ring.unwrap(
                        wrapped.nonce(),
                        wrapped.wrappedDek(),
                        CredentialEnvelope.aad("tenant-b", "model-credential", "dek-1")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessage("data key cannot be unwrapped by any master key")
                .hasMessageNotContaining(CURRENT);
    }

    /** 包裹密文任意位被篡改必须认证失败。 */
    @Test
    void unwrap_tamperedCiphertext_fails() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing ring = ring();
        MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);
        byte[] tampered = wrapped.wrappedDek();
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> ring.unwrap(wrapped.nonce(), tampered, aad)).isInstanceOf(EnvelopeException.class);
    }

    /** nonce 长度错误直接失败，不尝试降级。 */
    @Test
    void unwrap_invalidNonce_fails() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing ring = ring();
        MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);

        assertThatThrownBy(() -> ring.unwrap(new byte[8], wrapped.wrappedDek(), aad))
                .isInstanceOf(EnvelopeException.class);
    }

    /** 解包结果不是 32 字节时失败并清零，避免把异常长度 DEK 带入运行期。 */
    @Test
    void unwrap_wrongDekLength_fails() throws Exception {
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        byte[] nonce = new byte[MasterKeyRing.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] shortDek = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = encryptWithRawKey(Base64.getDecoder().decode(CURRENT), nonce, shortDek, aad);

        assertThatThrownBy(() -> ring().unwrap(nonce, wrapped, aad))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("invalid length");
    }

    /** 空 DEK 或空 AAD 在包裹前被拒绝。 */
    @Test
    void wrap_invalidArguments_fail() {
        MasterKeyRing ring = ring();

        assertThatThrownBy(() -> ring.wrap(new byte[16], new byte[] {1}))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> ring.wrap(newDek(), new byte[0]))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("AAD");
    }

    /**
     * 不缓存明文：连续两次解包返回不同数组实例，修改其中一份不影响另一份。
     *
     * <p>这是“无明文缓存”的可执行证据；实现若把 DEK 缓存在字段中，两次返回会是同一数组。
     */
    @Test
    void unwrap_returnsFreshArrayEachCall() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing ring = ring();
        MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);

        byte[] first = ring.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad);
        byte[] second = ring.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad);

        assertThat(first).isEqualTo(second);
        assertThat((Object) first).isNotSameAs(second);
        Arrays.fill(first, (byte) 0);
        assertThat(second).isEqualTo(dek);
    }

    /** 包裹结果防御性复制，调用方修改返回值不影响内部状态。 */
    @Test
    void wrappedDek_isDefensiveCopy() {
        byte[] dek = newDek();
        byte[] aad = CredentialEnvelope.aad("platform", "model-credential", "dek-1");
        MasterKeyRing ring = ring();
        MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);
        byte[] returned = wrapped.wrappedDek();
        returned[0] ^= 0x01;

        assertThat(wrapped.wrappedDek()).isNotEqualTo(returned);
        assertThat(ring.unwrap(wrapped.nonce(), wrapped.wrappedDek(), aad)).isEqualTo(dek);
    }

    /** 指纹确定、按 provider 区分、不含明文，且长度固定 64 位十六进制。 */
    @Test
    void fingerprint_isDeterministicAndDoesNotExposeKey() {
        MasterKeyRing ring = ring();

        String first = ring.fingerprint("provider-1", "sk-test-0123456789abcdef");
        String second = ring.fingerprint("provider-1", "sk-test-0123456789abcdef");
        String otherProvider = ring.fingerprint("provider-2", "sk-test-0123456789abcdef");

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        assertThat(otherProvider).isNotEqualTo(first);
        assertThat(first).doesNotContain("sk-test-0123456789abcdef");
    }

    /** pepper 不同时同一 key 的指纹不同，避免备份泄露后成为离线校验 oracle。 */
    @Test
    void fingerprint_dependsOnPepper() {
        MasterKeyRing first = MasterKeyRing.load(CURRENT, "", "mk-test", PEPPER, false);
        MasterKeyRing second = MasterKeyRing.load(CURRENT, "", "mk-test", base64Of(32, (byte) 9), false);

        assertThat(first.fingerprint("provider-1", "sk-test-0123456789abcdef"))
                .isNotEqualTo(second.fingerprint("provider-1", "sk-test-0123456789abcdef"));
    }

    /** 指纹参数为空时拒绝计算，避免生成可碰撞的空值指纹。 */
    @Test
    void fingerprint_blankArguments_fail() {
        MasterKeyRing ring = ring();

        assertThatThrownBy(() -> ring.fingerprint(" ", "sk-test-0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ring.fingerprint("provider-1", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    /** toString 只输出标识与布尔状态，不包含任何密钥材料。 */
    @Test
    void toString_neverExposesKeyMaterial() {
        MasterKeyRing ring = MasterKeyRing.load(CURRENT, PREVIOUS, "mk-test", PEPPER, false);

        assertThat(ring.toString())
                .contains("mk-test")
                .doesNotContain(CURRENT)
                .doesNotContain(PREVIOUS)
                .doesNotContain(PEPPER)
                .doesNotContain(
                        Base64.getEncoder().encodeToString(Base64.getDecoder().decode(CURRENT)));
    }

    /** 用原始密钥执行一次 GCM 加密，用于构造异常长度 DEK 的负向夹具。 */
    private static byte[] encryptWithRawKey(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }
}
