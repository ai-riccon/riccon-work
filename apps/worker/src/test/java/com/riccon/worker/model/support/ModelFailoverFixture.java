package com.riccon.worker.model.support;

import com.riccon.platform.domain.model.ModelPurpose;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.support.TestCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B4 集成测试夹具：平台 key 池、路由链与开关。
 *
 * <p>通过显式 SQL 造真实目录/凭据/链，不绕过外键、唯一键或状态约束；每个测试前由
 * {@code WorkerIntegrationTestBase} 清空业务表。加密复用 {@link TestCredentials}，
 * 与生产信封格式一致。
 */
public final class ModelFailoverFixture {

    /** 测试数据库客户端。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建夹具。
     *
     * @param jdbcClient 测试 JDBC 客户端
     */
    public ModelFailoverFixture(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 创建 provider + model（ACTIVE）。
     *
     * @param baseUrl 端点 URL（测试桩地址）
     * @param modelKey 模型标识
     * @return 模型 ID
     */
    public UUID createModel(String baseUrl, String modelKey) {
        UUID providerId = UuidBinary.newV7();
        UUID modelId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO model_provider (id, code, name, protocol, base_url, status)
                        VALUES (:id, :code, 'B4 Stub', 'OPENAI_COMPATIBLE', :baseUrl, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(providerId))
                .param("code", "b4-" + providerId)
                .param("baseUrl", baseUrl)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO model
                            (id, provider_id, model_key, display_name, context_window, max_output_tokens, status)
                        VALUES (:id, :providerId, :modelKey, :modelKey, 8192, 1024, 'ACTIVE')
                        """)
                .param("id", UuidBinary.toBytes(modelId))
                .param("providerId", UuidBinary.toBytes(providerId))
                .param("modelKey", modelKey)
                .update();
        return modelId;
    }

    /**
     * 读取模型所属 provider。
     *
     * @param modelId 模型 ID
     * @return provider ID
     */
    public UUID providerOf(UUID modelId) {
        return jdbcClient
                .sql("SELECT provider_id FROM model WHERE id = :id")
                .param("id", UuidBinary.toBytes(modelId))
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("provider_id")))
                .single();
    }

    /**
     * 为 provider 增加一把平台托管 key。
     *
     * @param providerId provider ID
     * @param apiKey 明文（仅测试）
     * @param priority 池优先级
     * @param maskedHint 掩码
     * @return 凭据 ID
     */
    public UUID addPlatformKey(UUID providerId, String apiKey, int priority, String maskedHint) {
        return addKey("platform", providerId, apiKey, priority, maskedHint);
    }

    /**
     * 为任意租户增加 key（用于证明租户 BYO 被忽略）。
     *
     * @param tenantId 凭据租户
     * @param providerId provider ID
     * @param apiKey 明文（仅测试）
     * @param priority 池优先级
     * @param maskedHint 掩码
     * @return 凭据 ID
     */
    public UUID addKey(String tenantId, UUID providerId, String apiKey, int priority, String maskedHint) {
        UUID credentialId = UuidBinary.newV7();
        String keyId = "dek-" + credentialId;
        byte[] dek = TestCredentials.newDek();
        TestCredentials.WrappedDek wrapped =
                TestCredentials.wrap(TestCredentials.MASTER_KEY_BASE64, tenantId, TestCredentials.PURPOSE, keyId, dek);
        jdbcClient
                .sql("""
                        INSERT INTO tenant_data_key
                            (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
                        VALUES (:tenantId, :keyId, :purpose, :wrappedDek, :wrapNonce, 'AES-256-GCM', :masterKeyId)
                        """)
                .param("tenantId", tenantId)
                .param("keyId", keyId)
                .param("purpose", TestCredentials.PURPOSE)
                .param("wrappedDek", wrapped.wrappedDek())
                .param("wrapNonce", wrapped.nonce())
                .param("masterKeyId", TestCredentials.MASTER_KEY_ID)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO provider_credential
                            (tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id,
                             version, status, label, masked_hint, priority)
                        VALUES (:tenantId, :id, :providerId, 'API_KEY', :ciphertext, :keyId, :masterKeyId,
                                1, 'ACTIVE', :label, :maskedHint, :priority)
                        """)
                .param("tenantId", tenantId)
                .param("id", UuidBinary.toBytes(credentialId))
                .param("providerId", UuidBinary.toBytes(providerId))
                .param(
                        "ciphertext",
                        TestCredentials.encryptCredential(tenantId, TestCredentials.PURPOSE, keyId, dek, apiKey))
                .param("keyId", keyId)
                .param("masterKeyId", TestCredentials.MASTER_KEY_ID)
                .param("label", "key-" + priority)
                .param("maskedHint", maskedHint)
                .param("priority", priority)
                .update();
        return credentialId;
    }

    /**
     * 整链替换指定用途的条目（position 从 1 开始）。
     *
     * @param purpose 用途
     * @param modelIds 有序模型
     */
    public void route(ModelPurpose purpose, UUID... modelIds) {
        jdbcClient
                .sql("DELETE FROM model_routing_entry WHERE purpose = :purpose")
                .param("purpose", purpose.name())
                .update();
        for (int index = 0; index < modelIds.length; index++) {
            jdbcClient
                    .sql("""
                            INSERT INTO model_routing_entry (purpose, position, model_id)
                            VALUES (:purpose, :position, :modelId)
                            """)
                    .param("purpose", purpose.name())
                    .param("position", index + 1)
                    .param("modelId", UuidBinary.toBytes(modelIds[index]))
                    .update();
        }
    }

    /**
     * 标记链被管理员保存过；用于区分“未配置兼容”和“配置后为空/全过滤”。
     *
     * @param purpose 用途
     * @param configured 是否已保存
     */
    public void markChainConfigured(ModelPurpose purpose, boolean configured) {
        jdbcClient
                .sql("""
                        UPDATE model_routing
                           SET updated_by = :updatedBy
                         WHERE purpose = :purpose
                        """)
                .param("purpose", purpose.name())
                .param("updatedBy", configured ? UuidBinary.toBytes(UuidBinary.newV7()) : null)
                .update();
    }

    /**
     * 更新模型状态。
     *
     * @param modelId 模型 ID
     * @param status ACTIVE 或 DISABLED
     */
    public void setModelStatus(UUID modelId, String status) {
        jdbcClient
                .sql("UPDATE model SET status = :status WHERE id = :id")
                .param("id", UuidBinary.toBytes(modelId))
                .param("status", status)
                .update();
    }

    /**
     * 更新开关。
     *
     * @param routingMode ROUTED 或 BASELINE
     * @param failoverEnabled 降级开关
     */
    public void switches(String routingMode, boolean failoverEnabled) {
        jdbcClient.sql("""
                        UPDATE model_gateway_switch SET switch_value = :value WHERE switch_key = 'routingMode'
                        """).param("value", routingMode).update();
        jdbcClient.sql("""
                        UPDATE model_gateway_switch SET switch_value = :value WHERE switch_key = 'failoverEnabled'
                        """).param("value", Boolean.toString(failoverEnabled)).update();
    }

    /**
     * 读取凭据状态。
     *
     * @param credentialId 凭据 ID
     * @return 状态
     */
    public String credentialStatus(UUID credentialId) {
        return jdbcClient
                .sql("SELECT status FROM provider_credential WHERE tenant_id = 'platform' AND id = :id")
                .param("id", UuidBinary.toBytes(credentialId))
                .query((rs, rowNum) -> rs.getString("status"))
                .single();
    }

    /**
     * 读取凭据连续失败次数。
     *
     * @param credentialId 凭据 ID
     * @return 次数
     */
    public int consecutiveFailures(UUID credentialId) {
        return jdbcClient
                .sql("SELECT consecutive_failures FROM provider_credential WHERE tenant_id = 'platform' AND id = :id")
                .param("id", UuidBinary.toBytes(credentialId))
                .query((rs, rowNum) -> rs.getInt("consecutive_failures"))
                .single();
    }

    /**
     * 计算 SHA-256 十六进制摘要（测试用于填充 fingerprint）。
     *
     * @param value 原文
     * @return 十六进制
     */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
