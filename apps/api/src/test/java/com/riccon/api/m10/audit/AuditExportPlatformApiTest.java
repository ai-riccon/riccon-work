package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IT-AUDIT-105：平台管理员审批单管理员租户导出。
 *
 * <p>平台管理员只能接触请求元数据：不返回审计内容、不提供下载；多管理员租户列表与决策均 409；
 * 平台审批审计写到 {@code platform} 并带 {@code details.targetTenantId}。
 */
class AuditExportPlatformApiTest extends AuditTestSupport {

    /**
     * 单管理员租户：平台列出并批准，租户内不写重复审批事件。
     */
    @Test
    @DisplayName("IT-AUDIT-105 平台审批单管理员租户并写平台审计")
    void platformApproval_singleAdminTenant() {
        TenantFixture fixture = createFixture("ap");
        insertAuditEvent(
                fixture.tenantId(),
                java.time.Instant.now().minusSeconds(30),
                "USER",
                fixture.tenantAdminId().toString(),
                "file.uploaded",
                "FILE",
                "file-1",
                "SUCCESS",
                "{\"fileId\":\"file-1\"}");
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));

        ApiTestClient.ApiResponse list = client.get(
                "/api/v1/admin/tenants/" + fixture.tenantId() + "/audit-exports?status=PENDING_APPROVAL",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        JsonNode page = client.json(list);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("id").asText()).isEqualTo(exportId.toString());
        assertThat(item.path("requestedBy").asText())
                .isEqualTo(fixture.tenantAdminId().toString());
        assertThat(item.path("estimatedRowCount").asInt()).isEqualTo(1);
        assertThat(item.path("filter").path("action").asText()).isEqualTo("file.uploaded");
        // 只有元数据：不出现审计内容字段。
        assertThat(item.has("details")).isFalse();
        assertThat(item.has("traceId")).isFalse();
        assertThat(item.has("eventId")).isFalse();

        ApiTestClient.ApiResponse decision =
                decidePlatform(fixture.platform(), fixture.tenantId(), exportId, "APPROVE", "平台代批");
        assertThat(decision.status()).as(decision.body()).isEqualTo(200);
        JsonNode approved = client.json(decision);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approvedBy").asText())
                .isEqualTo(fixture.platformUserId().toString());
        assertThat(approved.path("expiresAt").asText()).endsWith("+08:00");

        List<AuditRow> platformAudit = tenantAuditRows("platform", "audit.export.approved");
        List<AuditRow> targetAudit = tenantAuditRows(fixture.tenantId(), "audit.export.approved");
        assertThat(platformAudit.stream().filter(row -> exportId.toString().equals(row.targetId())))
                .hasSize(1);
        assertThat(platformAudit.stream()
                        .filter(row -> exportId.toString().equals(row.targetId()))
                        .findFirst()
                        .orElseThrow()
                        .details())
                .contains(fixture.tenantId())
                .contains(exportId.toString())
                .contains("APPROVE");
        assertThat(targetAudit).isEmpty();

        // 只有申请人可下载；平台管理员不提供下载路径。
        ApiTestClient.ApiResponse requesterDownload = download(fixture.tenantAdmin(), exportId);
        assertThat(requesterDownload.status()).as(requesterDownload.body()).isEqualTo(200);
        ApiTestClient.ApiResponse platformDownload = download(fixture.platform(), exportId);
        assertThat(platformDownload.status()).as(platformDownload.body()).isEqualTo(404);
    }

    /**
     * 多管理员租户：列表与决策均 409，且导出保持 PENDING_APPROVAL。
     */
    @Test
    @DisplayName("IT-AUDIT-105 多管理员租户 409 RICCON-AUDIT-409")
    void platformApproval_multiAdminTenantConflicts() {
        TenantFixture fixture = createFixture("ap");
        createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ApiTestClient.ApiResponse list = client.get(
                "/api/v1/admin/tenants/" + fixture.tenantId() + "/audit-exports",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(409);
        assertCode(list, "RICCON-AUDIT-409");

        ApiTestClient.ApiResponse decision =
                decidePlatform(fixture.platform(), fixture.tenantId(), exportId, "APPROVE", null);
        assertThat(decision.status()).as(decision.body()).isEqualTo(409);
        assertCode(decision, "RICCON-AUDIT-409");

        JsonNode detail = client.json(client.get(
                "/api/v1/audit-exports/" + exportId, fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(detail.path("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    /**
     * 平台审批端点只接受 platform.audit.export.approve。
     */
    @Test
    @DisplayName("IT-AUDIT-105 租户管理员/普通员工访问平台审批端点 403")
    void platformApproval_requiresPlatformPermission() {
        TenantFixture fixture = createFixture("ap");
        String path = "/api/v1/admin/tenants/" + fixture.tenantId() + "/audit-exports";

        ApiTestClient.ApiResponse tenantAdmin =
                client.get(path, fixture.tenantAdmin().sessionCookie(), Map.of());
        assertThat(tenantAdmin.status()).as(tenantAdmin.body()).isEqualTo(403);
        assertCode(tenantAdmin, "RICCON-AUTH-403");

        ApiTestClient.ApiResponse member = client.get(path, fixture.member().sessionCookie(), Map.of());
        assertThat(member.status()).as(member.body()).isEqualTo(403);
        assertCode(member, "RICCON-AUTH-403");
    }

    /**
     * 目标租户不存在返回 404。
     */
    @Test
    @DisplayName("IT-AUDIT-105 目标租户不存在 404")
    void platformApproval_unknownTenantNotFound() {
        TenantFixture fixture = createFixture("ap");
        String unknown = uniqueTenantCode("missing");
        ApiTestClient.ApiResponse list = client.get(
                "/api/v1/admin/tenants/" + unknown + "/audit-exports",
                fixture.platform().sessionCookie(),
                Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(404);
        assertCode(list, "RICCON-TENANT-404");

        ApiTestClient.ApiResponse decision =
                decidePlatform(fixture.platform(), unknown, UUID.randomUUID(), "APPROVE", null);
        assertThat(decision.status()).as(decision.body()).isEqualTo(404);
    }

    /**
     * 平台审批路径不能跨租户审批：目标租户下不存在该导出统一 404。
     */
    @Test
    @DisplayName("IT-AUDIT-105 平台跨租户审批 404")
    void platformApproval_crossTenantExportNotFound() {
        TenantFixture owner = createFixture("ap");
        TenantFixture other = createFixture("ap");
        UUID exportId = createExportId(owner.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ApiTestClient.ApiResponse decision =
                decidePlatform(owner.platform(), other.tenantId(), exportId, "APPROVE", null);
        assertThat(decision.status()).as(decision.body()).isEqualTo(404);
        assertCode(decision, "RICCON-TENANT-404");
    }
}
