package com.riccon.worker.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.crypto.domain.SecretValue;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ResolvedModel;
import com.riccon.worker.model.service.ModelRegistry;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestModelStub;
import io.agentscope.core.message.UserMessage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 模型目录、凭据解密与端到端 HTTP 调用。 */
class ModelRegistryIntegrationTest extends WorkerIntegrationTestBase {

    /** 模型桩。 */
    private TestModelStub stub;

    /** 模型解析。 */
    @Autowired
    private ModelRegistry modelRegistry;

    /** 启动桩；恢复 M04 全局模型基线（停用旧模型、清空路由链、复位开关）。 */
    @BeforeEach
    void startStub() {
        resetModelGatewayState();
        stub = new TestModelStub();
    }

    /** 关闭桩。 */
    @AfterEach
    void stopStub() {
        stub.close();
    }

    /** 部署级凭据解密后可调用模型，且仅发送到目录端点。 */
    @Test
    void resolve_andStream_usesDecryptedCredential() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        TenantId tenant = TenantId.of(tenantId);

        ResolvedModel resolved = TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant));
        assertThat(resolved.apiKey().reveal()).isEqualTo(TestCredentials.API_KEY);
        assertThat(resolved.apiKey().toString()).isEqualTo(SecretValue.REDACTED);
        assertThat(resolved.model().modelKey()).isEqualTo("fake-model");

        modelRegistry.createClient(resolved).stream(List.of(new UserMessage("hello")), List.of(), null)
                .blockLast(Duration.ofSeconds(10));

        assertThat(stub.requestCount()).isEqualTo(1);
        assertThat(stub.lastAuthorization()).isEqualTo("Bearer " + TestCredentials.API_KEY);
    }

    /** 密文被篡改时解析失败，错误不包含密钥。 */
    @Test
    void resolve_tamperedCredential_failsWithoutLeakingKey() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        // 只篡改当前 ACTIVE 模型对应的凭据；key id 从真实记录读取，保证失败原因是密文被篡改
        // 而不是 key id 不匹配（测试意图不能因隔离方式变化而弱化）。
        String keyId = jdbcClient.sql("""
                        SELECT pc.key_id FROM provider_credential pc
                         WHERE pc.tenant_id = 'platform'
                           AND pc.provider_id = (SELECT provider_id FROM model
                                                  WHERE status = 'ACTIVE'
                                                  ORDER BY created_at, id LIMIT 1)
                         ORDER BY pc.version DESC
                         LIMIT 1
                        """).query(String.class).single();
        jdbcClient
                .sql("""
                        UPDATE provider_credential
                           SET ciphertext = :ciphertext
                         WHERE tenant_id = 'platform' AND key_id = :keyId
                        """)
                .param("ciphertext", "enc:v1:" + keyId + ":AAAA")
                .param("keyId", keyId)
                .update();
        TenantId tenant = TenantId.of(tenantId);

        assertThatThrownBy(() -> TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant)))
                .isInstanceOf(ModelException.class)
                .hasMessageNotContaining(TestCredentials.API_KEY)
                .satisfies(error ->
                        assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.CONFIGURATION));
    }

    /** 目录 URL 必须为 HTTPS（测试允许 HTTP），且不能含用户信息、查询或片段。 */
    @Test
    void resolve_forbiddenEndpointParts_fail() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        TenantId tenant = TenantId.of(tenantId);
        for (String url : List.of(
                "https://user:pass@api.example.com/v1",
                "https://api.example.com/v1?token=x",
                "https://api.example.com/v1#fragment",
                "ftp://api.example.com/v1",
                "file:/tmp/model")) {
            jdbcClient
                    .sql("UPDATE model_provider SET base_url = :url")
                    .param("url", url)
                    .update();
            assertThatThrownBy(() -> TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant)))
                    .as("endpoint %s", url)
                    .isInstanceOf(ModelException.class)
                    .satisfies(error ->
                            assertThat(((ModelException) error).errorCode()).isEqualTo(ModelException.CONFIGURATION));
        }
    }

    /** HTTPS 目录只做解析，不要求测试环境可达。 */
    @Test
    void resolve_httpsEndpoint_succeeds() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        jdbcClient
                .sql("UPDATE model_provider SET base_url = 'https://api.example.com/v1'")
                .update();
        TenantId tenant = TenantId.of(tenantId);

        ResolvedModel resolved = TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant));

        assertThat(resolved.baseUrl().toString()).isEqualTo("https://api.example.com/v1");
    }

    /** 没有可用模型时失败。 */
    @Test
    void resolve_noCatalog_fails() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        TenantId tenant = TenantId.of(tenantId);

        assertThatThrownBy(() -> TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant)))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("no active model");
    }

    /** 没有凭据时失败。 */
    @Test
    void resolve_noCredential_fails() {
        String tenantId = uniqueTenant("tenant-a");
        data.createTenant(tenantId);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        jdbcClient.sql("DELETE FROM provider_credential").update();
        TenantId tenant = TenantId.of(tenantId);

        assertThatThrownBy(() -> TenantContext.callWithTenant(tenant, () -> modelRegistry.resolve(tenant)))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("no active model credential");
    }
}
