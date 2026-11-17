package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 审计写入失败必须使业务失败（ADR-0007 fail-closed）。
 *
 * <p>本测试关闭引导管理员，避免启动阶段写审计；把 {@link AuditRepository} 替换为抛异常的 mock，
 * 验证登录失败路径返回 500 而不是静默成功或无审计返回 401。
 */
@TestPropertySource(properties = {"riccon.bootstrap.admin.username=", "riccon.bootstrap.admin.password="})
class AuditFailClosedTest extends ApiIntegrationTestSupport {

    /** 抛异常的审计仓储。 */
    @MockitoBean
    private AuditRepository auditRepository;

    /** 审计不可用时登录请求必须失败为 500。 */
    @Test
    void loginFailsWhenAuditWriteFails() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(any(AuditEvent.class));

        CsrfSession csrf = csrfSession();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "no-such-tenant",
                        "username", "no-such-user",
                        "password", "wrong-password-12"),
                csrf.cookie(),
                csrf.token(),
                Map.of());
        assertThat(response.status()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(client.json(response).path("traceId").asText()).isNotBlank();
    }
}
