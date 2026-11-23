package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.knowledge.ConnectorSyncStatus;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.connector.domain.ConnectorRecord;
import com.riccon.worker.connector.domain.ConnectorSyncSummary;
import com.riccon.worker.connector.repository.ConnectorRepository;
import com.riccon.worker.connector.service.ConnectorAuditWriter;
import com.riccon.worker.connector.service.ConnectorSyncCompletionService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * 同步终态与完成/失败审计的同事务 fail-closed 负向测试。
 *
 * <p>审计写入抛异常时，{@code finishSync} 必须随事务回滚，不能留下“终态已落库但审计缺失”的假成功。
 * 这里用 {@link MockitoBean} 替换 Worker 审计写入器模拟审计不可用；其余仓储/事务使用真实 MySQL。
 */
class ConnectorSyncCompletionIntegrationTest extends WorkerIntegrationTestBase {

    /** JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 被替换的审计写入器。 */
    @MockitoBean
    private ConnectorAuditWriter auditWriter;

    /** 终态收尾服务。 */
    @Autowired
    private ConnectorSyncCompletionService completionService;

    /** 仓储。 */
    @Autowired
    private ConnectorRepository connectorRepository;

    @Test
    void complete_auditFailureRollsBackTerminalState() {
        String tenant = uniqueTenant("conn");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID connectorId = com.riccon.worker.persistence.UuidBinary.newV7();
        ObjectMapper json = JSON;
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                            schedule_enabled, created_by, created_at, updated_at
                        ) VALUES (
                            :tenant, :id, 'completion-test', 'S3', 'PAUSED', 'local-rustfs', :config,
                            'TENANT', FALSE, :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .param("config", json.writeValueAsString(Map.of("bucket", BUCKET, "prefix", "completion/")))
                .param("createdBy", com.riccon.worker.persistence.UuidBinary.toBytes(user))
                .update();

        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditWriter)
                .syncCompleted(any(), any(), any(), any(), anyLong(), any());
        Instant startedAt = Instant.now().minusSeconds(1);
        Instant finishedAt = Instant.now();
        ConnectorSyncSummary summary = new ConnectorSyncSummary(0, 0, 0, 0, 0, 0, true);
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            ConnectorRecord connector = connectorRepository
                    .findConnector(TenantId.of(tenant), connectorId)
                    .orElseThrow();
            assertThatThrownBy(() -> completionService.complete(
                            TenantId.of(tenant),
                            connector,
                            UUID.randomUUID(),
                            startedAt,
                            finishedAt,
                            ConnectorSyncStatus.SUCCESS,
                            true,
                            null,
                            summary,
                            connector.status(),
                            null,
                            10,
                            false,
                            "conn-test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("audit unavailable");
        }

        Map<String, Object> row = jdbcClient
                .sql("""
                        SELECT last_sync_finished_at, last_sync_status, last_sync_summary
                          FROM kb_connector WHERE tenant_id=:tenant AND id=:id
                        """)
                .param("tenant", tenant)
                .param("id", com.riccon.worker.persistence.UuidBinary.toBytes(connectorId))
                .query()
                .singleRow();
        assertThat(row.get("last_sync_finished_at")).isNull();
        assertThat(row.get("last_sync_status")).isNull();
        assertThat(row.get("last_sync_summary")).isNull();
    }
}
