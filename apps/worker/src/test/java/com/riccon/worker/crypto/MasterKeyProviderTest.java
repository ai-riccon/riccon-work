package com.riccon.worker.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.config.CryptoProperties;
import com.riccon.worker.crypto.domain.CredentialException;
import com.riccon.worker.crypto.service.MasterKeyProvider;
import com.riccon.worker.support.TestCredentials;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 主密钥校验与 DEK 解包。 */
class MasterKeyProviderTest {

    /** 合法主密钥可解包当前密钥包裹的 DEK。 */
    @Test
    void unwrap_currentKey_returnsDek() {
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped = TestCredentials.wrap(
                TestCredentials.MASTER_KEY_BASE64, "platform", TestCredentials.PURPOSE, "dek-test", dek);
        MasterKeyProvider provider = provider(TestCredentials.MASTER_KEY_BASE64, "");

        byte[] unwrapped = provider.unwrap(
                wrapped.nonce(),
                wrapped.wrappedDek(),
                TestCredentials.aad("platform", TestCredentials.PURPOSE, "dek-test"));

        assertThat(unwrapped).isEqualTo(dek);
    }

    /** 轮换窗口内上一把主密钥可解包旧 DEK。 */
    @Test
    void unwrap_previousKey_returnsDek() {
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped = TestCredentials.wrap(
                TestCredentials.PREVIOUS_KEY_BASE64, "platform", TestCredentials.PURPOSE, "dek-test", dek);
        MasterKeyProvider provider = provider(TestCredentials.MASTER_KEY_BASE64, TestCredentials.PREVIOUS_KEY_BASE64);

        byte[] unwrapped = provider.unwrap(
                wrapped.nonce(),
                wrapped.wrappedDek(),
                TestCredentials.aad("platform", TestCredentials.PURPOSE, "dek-test"));

        assertThat(unwrapped).isEqualTo(dek);
    }

    /** AAD 不匹配时解包失败且错误信息不含密钥。 */
    @Test
    void unwrap_wrongAad_failsWithoutLeakingSecret() {
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped = TestCredentials.wrap(
                TestCredentials.MASTER_KEY_BASE64, "tenant-a", TestCredentials.PURPOSE, "dek-test", dek);
        MasterKeyProvider provider = provider(TestCredentials.MASTER_KEY_BASE64, "");

        assertThatThrownBy(() -> provider.unwrap(
                        wrapped.nonce(),
                        wrapped.wrappedDek(),
                        TestCredentials.aad("tenant-b", TestCredentials.PURPOSE, "dek-test")))
                .isInstanceOf(CredentialException.class)
                .hasMessageNotContaining(TestCredentials.MASTER_KEY_BASE64);
    }

    /** 缺失主密钥拒绝启动。 */
    @Test
    void constructor_missingKey_fails() {
        assertThatThrownBy(() -> provider("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RICCON_MASTER_KEY");
    }

    /** 长度不足拒绝启动。 */
    @Test
    void constructor_shortKey_fails() {
        assertThatThrownBy(() -> provider("c2hvcnQ=", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    /** 非法 Base64 拒绝启动。 */
    @Test
    void constructor_invalidBase64_fails() {
        assertThatThrownBy(() -> provider("not-base64!!!", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    /** 已知测试密钥默认拒绝。 */
    @Test
    void constructor_knownTestKey_failsWhenRejected() {
        assertThatThrownBy(() -> new MasterKeyProvider(
                        new CryptoProperties("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "", "mk-1", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known test key");
    }

    /** 显式关闭拒绝后允许测试密钥，供本地夹具使用。 */
    @Test
    void constructor_knownTestKey_allowedWhenNotRejected() {
        MasterKeyProvider provider = new MasterKeyProvider(
                new CryptoProperties("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "", "mk-1", false));
        assertThat(provider.masterKeyId()).isEqualTo("mk-1");
    }

    /** unwrap 输出长度不是 32 字节时失败。 */
    @Test
    void unwrap_wrongDekLength_fails() {
        byte[] shortDek = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[12];
        byte[] wrapped = TestCredentials.encrypt(
                java.util.Base64.getDecoder().decode(TestCredentials.MASTER_KEY_BASE64),
                nonce,
                shortDek,
                TestCredentials.aad("platform", TestCredentials.PURPOSE, "dek-test"));
        MasterKeyProvider provider = provider(TestCredentials.MASTER_KEY_BASE64, "");

        assertThatThrownBy(() -> provider.unwrap(
                        nonce, wrapped, TestCredentials.aad("platform", TestCredentials.PURPOSE, "dek-test")))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("invalid length");
    }

    /** 包裹结果对 null 数组成员保持 null（防御性复制分支），不抛异常也不伪造空数组。 */
    @Test
    void wrappedDek_nullArrays_arePreserved() {
        MasterKeyProvider.WrappedDek missingCiphertext = new MasterKeyProvider.WrappedDek(null, new byte[12]);
        MasterKeyProvider.WrappedDek missingNonce = new MasterKeyProvider.WrappedDek(new byte[16], null);

        assertThat(missingCiphertext.wrappedDek()).isNull();
        assertThat(missingCiphertext.nonce()).hasSize(12);
        assertThat(missingNonce.wrappedDek()).hasSize(16);
        assertThat(missingNonce.nonce()).isNull();
    }

    /** test profile 允许仓库占位测试密钥，保证 Worker 集成测试与 API 往返可启动。 */
    @Test
    void constructor_testProfile_allowsRepositoryTestKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        MasterKeyProvider provider = new MasterKeyProvider(
                new CryptoProperties(TestCredentials.MASTER_KEY_BASE64, "", "mk-test", true), environment);

        assertThat(provider.masterKeyId()).isEqualTo("mk-test");
    }

    /** 非 test profile 即使配置关闭拒绝也必须失败，防止生产关闭已知测试密钥控制。 */
    @Test
    void constructor_nonTestProfile_rejectsRepositoryTestKeyEvenIfDisabled() {
        assertThatThrownBy(() -> new MasterKeyProvider(
                        new CryptoProperties(TestCredentials.MASTER_KEY_BASE64, "", "mk-test", false),
                        new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known test key");
    }

    /** 构造 provider；直接构造使用非仓库测试密钥并显式关闭测试密钥拒绝。 */
    private static MasterKeyProvider provider(String key, String previous) {
        return new MasterKeyProvider(new CryptoProperties(key, previous, "mk-test", false));
    }
}
