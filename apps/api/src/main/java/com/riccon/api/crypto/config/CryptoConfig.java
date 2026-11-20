package com.riccon.api.crypto.config;

import com.riccon.api.config.CryptoProperties;
import com.riccon.api.crypto.CredentialCipher;
import com.riccon.api.crypto.MasterKeyProvider;
import com.riccon.platform.common.crypto.CryptoErrorCodes;
import com.riccon.platform.common.crypto.MasterKeyConfigException;
import com.riccon.platform.common.crypto.MasterKeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * API 信封加密装配与启动校验。
 *
 * <p>ADR-0006 第 3 节与 ADR-0021 §6.5 要求 API 在接收流量前完成主密钥校验：Base64、恰好 32 字节、
 * 拒绝已知测试密钥、canary 自检；pepper 同样必须在部署时注入。缺任何一项都让进程启动失败，
 * 禁止降级为明文、禁止生成临时密钥、禁止健康检查返回 UP。
 *
 * <p>唯一的例外是显式 {@code test} profile：单元/集成测试不应依赖部署注入真实密钥，此时允许以
 * “未配置占位环”启动，但任何加密、解密或指纹操作仍然失败关闭。生产与本地默认（无 profile）
 * 都按 fail-closed 处理。
 *
 * <p>密钥值只从环境变量或 {@code riccon.crypto.*} 读取，从不写入日志；启动日志只输出主密钥标识与
 * 是否处于轮换窗口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    /** 启动日志；只记录标识与布尔状态。 */
    private static final Logger LOG = LoggerFactory.getLogger(CryptoConfig.class);

    /** 当前主密钥环境变量名（ADR-0006 第 1 节）。 */
    static final String MASTER_KEY_ENV = "RICCON_MASTER_KEY";

    /** 上一把主密钥环境变量名。 */
    static final String PREVIOUS_MASTER_KEY_ENV = "RICCON_MASTER_KEY_PREVIOUS";

    /** 主密钥标识环境变量名。 */
    static final String MASTER_KEY_ID_ENV = "RICCON_MASTER_KEY_ID";

    /** 指纹 pepper 环境变量名（ADR-0021 §3.2）。 */
    static final String FINGERPRINT_PEPPER_ENV = "RICCON_CREDENTIAL_FINGERPRINT_PEPPER";

    /**
     * 创建 API 主密钥提供者并执行启动校验。
     *
     * <p>环境变量优先于 {@code riccon.crypto.*} 绑定值，因为部署合同（ADR-0006）以环境变量为准；
     * yml 只是便于本地运行的透传层。
     *
     * @param properties 绑定的加密配置
     * @param environment 环境，用于读取部署注入的密钥与判断 test profile
     * @return 完成自检的主密钥提供者
     * @throws MasterKeyConfigException 主密钥或 pepper 缺失/非法，或 canary 自检失败
     */
    @Bean
    public MasterKeyProvider masterKeyProvider(CryptoProperties properties, Environment environment) {
        boolean testProfile = environment.matchesProfiles("test");
        String masterKey = resolve(environment, MASTER_KEY_ENV, properties.masterKey());
        String previousMasterKey = resolve(environment, PREVIOUS_MASTER_KEY_ENV, properties.previousMasterKey());
        String masterKeyId = resolve(environment, MASTER_KEY_ID_ENV, properties.masterKeyId());
        String pepper = resolve(environment, FINGERPRINT_PEPPER_ENV, properties.fingerprintPepper());

        if (masterKey.isBlank()) {
            if (!testProfile) {
                throw new MasterKeyConfigException(
                        CryptoErrorCodes.MASTER_KEY_CONFIG,
                        MasterKeyConfigException.Reason.MASTER_KEY_MISSING,
                        "RICCON_MASTER_KEY is required; API refuses to start without envelope encryption");
            }
            LOG.warn("RICCON_MASTER_KEY is not set; test profile starts with envelope encryption disabled");
            return new MasterKeyProvider(MasterKeyRing.unconfigured(masterKeyId));
        }
        if (pepper.isBlank() && !testProfile) {
            throw new MasterKeyConfigException(
                    CryptoErrorCodes.MASTER_KEY_CONFIG,
                    MasterKeyConfigException.Reason.PEPPER_MISSING,
                    "RICCON_CREDENTIAL_FINGERPRINT_PEPPER is required; platform key fingerprints cannot be computed");
        }
        // 测试 profile 使用仓库内的占位密钥，必须允许；生产与默认环境继续拒绝已知测试密钥。
        boolean rejectTestKeys = properties.rejectTestKeys() && !testProfile;
        MasterKeyRing ring = MasterKeyRing.load(masterKey, previousMasterKey, masterKeyId, pepper, rejectTestKeys);
        LOG.info(
                "envelope encryption ready: masterKeyId={}, previousKey={}, fingerprintPepper={}",
                ring.masterKeyId(),
                ring.hasPreviousKey(),
                ring.pepperConfigured());
        return new MasterKeyProvider(ring);
    }

    /**
     * 凭据信封加密器；无状态，可在事务内多次调用。
     *
     * @return 凭据加密器
     */
    @Bean
    CredentialCipher credentialCipher() {
        return new CredentialCipher();
    }

    /**
     * 读取配置：环境变量优先，其次 yml 绑定值，最后空串。
     *
     * @param environment 环境
     * @param envName 环境变量名
     * @param boundValue yml 绑定值
     * @return 去除首尾空白后的值；缺失返回空串
     */
    private static String resolve(Environment environment, String envName, String boundValue) {
        String value = environment.getProperty(envName);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return boundValue == null ? "" : boundValue.trim();
    }
}
