package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import com.riccon.api.support.TestFiles;
import com.riccon.platform.domain.audit.AuditActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 文件上传审计失败必须 rollback 元数据并补偿删除对象（ADR-0007）。
 *
 * <p>只让 {@code file.uploaded} 审计失败，其他动作放行，确保引导管理员和租户夹具仍可创建。
 */
class FileAuditFailClosedTest extends FileApiTestSupport {

    /** 审计仓储 spy；启动与租户夹具走真实实现，仅 file.uploaded 在测试内抛错。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 上传审计失败时返回 500，且不留下 file/parse/run/outbox 或对象。 */
    @Test
    @DisplayName("file.uploaded 审计失败回滚业务并清理对象")
    void upload_auditFailure_rollsBackAndDeletesObject() {
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (AuditActions.FILE_UPLOADED.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));

        TenantFixture fixture = tenantFixture();
        ApiTestClient.ApiResponse response = uploadRaw(
                fixture.member(), fixture.memberWorkspace(), "audit.pdf", "application/pdf", TestFiles.pdf(), null);
        assertThat(response.status()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");

        assertThat(count("SELECT COUNT(*) FROM file WHERE tenant_id = :tenantId", fixture.tenant()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM file_parse WHERE tenant_id = :tenantId", fixture.tenant()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_run WHERE tenant_id = :tenantId", fixture.tenant()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_run_outbox WHERE tenant_id = :tenantId", fixture.tenant()))
                .isZero();
        assertThat(objectPrefixEmpty("tenants/" + fixture.tenant() + "/file/")).isTrue();
    }

    /**
     * 统计租户行数。
     *
     * @param sql SQL，必须含 {@code tenant_id = :tenantId}
     * @param tenant 租户
     * @return 行数
     */
    private long count(String sql, String tenant) {
        return jdbcClient.sql(sql).param("tenantId", tenant).query(Long.class).single();
    }
}
