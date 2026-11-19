package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IT-AUDIT-106：CSV 元数据、字段白名单、公式注入转义与重复下载审计。
 *
 * <p>数据行中的目标 ID 使用表格软件危险前缀（{@code =}/{@code +}/{@code -}/{@code @}），断言 CSV 前置
 * 单引号；同时断言响应不含来源 IP、requestId、密码或邮箱。
 */
class AuditCsvExportTest extends AuditTestSupport {

    /** 危险前缀目标 ID。 */
    private static final List<String> DANGEROUS_TARGETS = List.of("=1+1", "@SUM(A1)", "-2+3", "+cmd", "normal-target");

    /**
     * CSV 元数据、表头、白名单与公式注入转义。
     */
    @Test
    @DisplayName("IT-AUDIT-106 CSV 元数据/字段白名单/公式注入转义/重复下载")
    void csv_metadataWhitelistAndFormulaInjection() {
        TenantFixture fixture = createFixture("ac");
        LoginSession approver = createSecondAdmin(fixture);
        Instant base = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        for (int index = 0; index < DANGEROUS_TARGETS.size(); index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    base.plusSeconds(index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.uploaded",
                    "FILE",
                    DANGEROUS_TARGETS.get(index),
                    "SUCCESS",
                    "{\"fileId\":\"file-" + index + "\",\"sizeBytes\":1,\"contentType\":\"text/plain\"}");
        }
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));
        assertThat(decide(approver, exportId, "APPROVE", null).status()).isEqualTo(200);

        ApiTestClient.ApiResponse response = download(fixture.tenantAdmin(), exportId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String body = response.body();
        String[] lines = body.split("\r\n");
        assertThat(lines[0]).startsWith("\uFEFF# exportId: " + exportId);
        assertThat(lines[1]).startsWith("# generatedAt: ");
        assertThat(lines[2]).startsWith("# filters: ");
        assertThat(lines[3]).isEqualTo("# rowCount: 5");
        assertThat(lines[4]).isEmpty();
        assertThat(lines[5])
                .isEqualTo(
                        "eventId,occurredAt,tenantId,actorType,actorId,action,targetType,targetId,outcome,traceId,details");
        assertThat(lines).hasSize(11);

        // 公式注入防护：危险前缀前置单引号。
        assertThat(body)
                .contains("'=1+1")
                .contains("'@SUM(A1)")
                .contains("'-2+3")
                .contains("'+cmd");
        assertThat(body).contains("normal-target");
        // 字段白名单：details 保留合法键，响应与 CSV 不含来源 IP/请求 ID/密码/邮箱列。
        assertThat(body).contains("\"fileId\"");
        assertThat(body).contains("\"contentType\"");
        assertThat(body).doesNotContain(SOURCE_IP);
        assertThat(body).doesNotContain("source_ip");
        assertThat(body).doesNotContain("requestId");
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("email");

        // 重复下载在有效期内允许，并逐次写 data.exported。
        ApiTestClient.ApiResponse again = download(fixture.tenantAdmin(), exportId);
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        List<AuditRow> exported = tenantAuditRows(fixture.tenantId(), "data.exported");
        assertThat(exported).hasSize(2);
        JsonNode exportedDetails = new tools.jackson.databind.ObjectMapper()
                .readTree(exported.get(0).details());
        assertThat(exportedDetails.path("rowCount").asInt()).isEqualTo(5);
        assertThat(exportedDetails.path("exportId").asText()).isEqualTo(exportId.toString());
    }

    /**
     * 过期导出不可下载：410，且不新增成功审计。
     */
    @Test
    @DisplayName("IT-AUDIT-106 过期下载 410 且不写成功审计")
    void download_expiredExportGone() {
        TenantFixture fixture = createFixture("ac");
        LoginSession approver = createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));
        assertThat(decide(approver, exportId, "APPROVE", null).status()).isEqualTo(200);

        // 直接改库把 expires_at 移到过去，模拟时间流逝；不改写 status，过期仍按时间派生。
        jdbcClient
                .sql("""
                        UPDATE audit_export_request
                           SET expires_at = :expiresAt
                         WHERE tenant_id = :tenantId AND id = :exportId
                        """)
                .param(
                        "expiresAt",
                        com.riccon.api.persistence.SqlTimestamps.toDatabase(
                                Instant.now().minusSeconds(5)))
                .param("tenantId", fixture.tenantId())
                .param("exportId", com.riccon.api.persistence.BinaryUuids.toBytes(exportId))
                .update();

        ApiTestClient.ApiResponse response = download(fixture.tenantAdmin(), exportId);
        assertThat(response.status()).as(response.body()).isEqualTo(410);
        assertCode(response, "RICCON-AUDIT-410");

        JsonNode detail = client.json(client.get(
                "/api/v1/audit-exports/" + exportId, fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(detail.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(tenantAuditRows(fixture.tenantId(), "data.exported")).isEmpty();
    }
}
