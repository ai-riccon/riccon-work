package com.riccon.worker.crypto.service;

import com.riccon.platform.common.crypto.EnvelopeException;
import com.riccon.platform.common.crypto.MasterKeyRing;
import com.riccon.worker.config.CryptoProperties;
import com.riccon.worker.crypto.domain.CredentialException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 主密钥提供者（Worker 部署物）。
 *
 * <p>M04 起密钥语义集中到 {@code platform-common} 的 {@link MasterKeyRing}，API 与 Worker 共用同一实现；
 * Worker 保留本类作为 Spring 装配与异常适配层，避免破坏既有的审批参数、模型凭据解密调用点。
 *
 * <p>只接受 Base64 编码的 32 字节 AES-256 密钥（ADR-0006）。缺失、长度错误、无法解码或命中已知测试密钥时
 * 构造失败，让 Worker 在接收流量前退出；禁止生成临时密钥或降级明文。解密时先尝试当前主密钥，再尝试
 * 轮换窗口中的上一把；两者都失败返回统一错误，不暴露具体是哪一把失败。
 *
 * <p>已知测试密钥拒绝在非 {@code test} profile 下强制开启，不能被
 * {@code riccon.worker.crypto.reject-test-keys} 关闭；{@code test} profile 使用仓库占位密钥，允许放行。
 *
 * <p>M04 口径（Lead 决策）：Worker 只校验主密钥与 canary；fingerprint pepper 仅由 API 使用并在
 * 非 {@code test} profile 缺失时让 API 启动失败。Worker 解密不需要 pepper，因此本类不注入、不校验
 * pepper，也不新增 Worker 侧 pepper 必填（ADR 原文未改，归属见 B5/B10）。
 */
@Component
public class MasterKeyProvider {

    /** AES-GCM nonce 长度，供信封解密器分配缓冲区。 */
    static final int NONCE_LENGTH = MasterKeyRing.NONCE_BYTES;

    /** 共享主密钥环。 */
    private final MasterKeyRing ring;

    /**
     * 直接构造：语义完全由配置决定，供单元测试与显式装配使用。
     *
     * @param properties 加密配置
     * @throws IllegalStateException 主密钥缺失、长度错误、编码非法或命中测试密钥
     */
    public MasterKeyProvider(CryptoProperties properties) {
        this(properties, properties.rejectTestKeys());
    }

    /**
     * Spring 装配：非 {@code test} profile 强制拒绝已知测试密钥，配置无法关闭；{@code test} profile
     * 使用仓库占位密钥，必须放行，否则集成测试无法启动。
     *
     * @param properties 加密配置
     * @param environment 环境，用于识别 test profile
     * @throws IllegalStateException 主密钥缺失、长度错误、编码非法或命中测试密钥
     */
    @Autowired
    public MasterKeyProvider(CryptoProperties properties, Environment environment) {
        this(properties, !environment.matchesProfiles("test"));
    }

    /**
     * 内部构造：统一装配密钥环。
     *
     * @param properties 加密配置
     * @param rejectTestKeys 是否拒绝已知测试密钥
     */
    private MasterKeyProvider(CryptoProperties properties, boolean rejectTestKeys) {
        this.ring = MasterKeyRing.load(
                properties.masterKey(), properties.previousMasterKey(), properties.masterKeyId(), null, rejectTestKeys);
    }

    /**
     * 返回主密钥标识。
     *
     * @return 例如 {@code mk-1}
     */
    public String masterKeyId() {
        return ring.masterKeyId();
    }

    /**
     * 用当前主密钥包裹新的 DEK。
     *
     * @param dek 32 字节数据密钥
     * @param aad 附加认证数据
     * @return 包裹后的密文与 nonce
     * @throws CredentialException DEK 长度错误或 JDK 加密能力不可用
     */
    public WrappedDek wrap(byte[] dek, byte[] aad) {
        try {
            MasterKeyRing.WrappedDek wrapped = ring.wrap(dek, aad);
            return new WrappedDek(wrapped.wrappedDek(), wrapped.nonce());
        } catch (EnvelopeException ex) {
            throw new CredentialException(EnvelopeException.ERROR_CODE, ex.getMessage());
        }
    }

    /**
     * 用当前或上一把主密钥解密被包裹的 DEK。
     *
     * @param nonce 12 字节 nonce
     * @param ciphertext 密文与认证标签
     * @param aad 附加认证数据
     * @return 32 字节 DEK
     * @throws CredentialException 两把主密钥都无法解密
     */
    public byte[] unwrap(byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            return ring.unwrap(nonce, ciphertext, aad);
        } catch (EnvelopeException ex) {
            throw new CredentialException(EnvelopeException.ERROR_CODE, ex.getMessage());
        }
    }

    /**
     * 被主密钥包裹的数据密钥。
     *
     * @param wrappedDek 密文与认证标签
     * @param nonce 包裹 nonce
     */
    public record WrappedDek(byte[] wrappedDek, byte[] nonce) {

        /** 防御性复制，避免调用方修改包裹结果。 */
        public WrappedDek {
            wrappedDek = wrappedDek == null ? null : wrappedDek.clone();
            nonce = nonce == null ? null : nonce.clone();
        }
    }
}
