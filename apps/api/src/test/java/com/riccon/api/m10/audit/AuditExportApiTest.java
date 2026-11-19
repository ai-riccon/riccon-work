package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IT-AUDIT-104：导出申请、审批状态机、自批/并发/过期边界、下载权限与平台租户 403。
 *
 * <p>使用真实 MySQL 与 HTTP；审批人必须是另一名租户管理员，下载只允许申请人本人。并发审批用两个真实
 * 请求同时提交，断言只有一个 200，另一个 409。
 */
class AuditExportApiTest extends AuditTestSupport {

    /** 审计 details 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 申请导出：服务端计算预计行数，状态 PENDING_APPROVAL，写申请审计。
     */
    @Test
    @DisplayName("IT-AUDIT-104 申请导出计算预计行数并写 audit.export.requested")
    void createExport_pendingWithEstimatedRows() throws Exception {
        TenantFixture fixture = createFixture("ax");
        Instant base = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        for (int index = 0; index < 3; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    base.plusSeconds(index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.uploaded",
                    "FILE",
                    "file-" + index,
                    "SUCCESS",
                    "{\"fileId\":\"file-" + index + "\"}");
        }

        ApiTestClient.ApiResponse response =
                createExport(fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode body = client.json(response);
        UUID exportId = UUID.fromString(body.path("id").asText());
        assertThat(body.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(body.path("estimatedRowCount").asInt()).isEqualTo(3);
        assertThat(body.path("requestedBy").asText())
                .isEqualTo(fixture.tenantAdminId().toString());
        assertThat(body.path("requestedByDisplayName").asText()).isEqualTo("User " + fixture.tenantAdminUsername());
        assertThat(body.path("filter").path("occurredFrom").asText()).endsWith("+08:00");
        assertThat(body.path("filter").path("occurredTo").asText()).endsWith("+08:00");
        assertThat(body.path("expiresAt").isNull()).isTrue();
        assertThat(body.path("decidedAt").isNull()).isTrue();

        List<AuditRow> requested = tenantAuditRows(fixture.tenantId(), "audit.export.requested");
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0).targetId()).isEqualTo(exportId.toString());
        assertThat(requested.get(0).targetType()).isEqualTo("AUDIT_EXPORT");
        JsonNode requestedDetails = objectMapper.readTree(requested.get(0).details());
        assertThat(requestedDetails.path("exportId").asText()).isEqualTo(exportId.toString());
        assertThat(requestedDetails.path("estimatedRowCount").asInt()).isEqualTo(3);
        assertThat(requestedDetails.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(requestedDetails.path("filtersHash").asText()).hasSize(64);
        assertThat(requestedDetails.path("action").asText()).isEqualTo("file.uploaded");
    }

    /**
     * 缺少 filter 或审批决定非法返回 RICCON-AUDIT-400。
     */
    @Test
    @DisplayName("IT-AUDIT-104 缺少 filter/非法决定 400")
    void createAndDecide_rejectInvalidBodies() {
        TenantFixture fixture = createFixture("ax");
        ApiTestClient.ApiResponse missingFilter = client.post(
                "/api/v1/audit-exports",
                "{}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(missingFilter.status()).as(missingFilter.body()).isEqualTo(400);
        assertCode(missingFilter, "RICCON-AUDIT-400");

        LoginSession approver = createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));
        ApiTestClient.ApiResponse badDecision = decide(approver, exportId, "MAYBE", null);
        assertThat(badDecision.status()).as(badDecision.body()).isEqualTo(400);
        assertCode(badDecision, "RICCON-AUDIT-400");
    }

    /**
     * 普通员工无审计权限；平台租户自身导出在 M10 不支持。
     */
    @Test
    @DisplayName("IT-AUDIT-104 普通员工 403、平台租户导出 403/下载 404")
    void createExport_memberAndPlatformTenantForbidden() {
        TenantFixture fixture = createFixture("ax");
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));

        ApiTestClient.ApiResponse memberCreate =
                createExport(fixture.member(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));
        assertThat(memberCreate.status()).as(memberCreate.body()).isEqualTo(403);
        assertCode(memberCreate, "RICCON-AUTH-403");
        ApiTestClient.ApiResponse memberList =
                client.get("/api/v1/audit-exports", fixture.member().sessionCookie(), Map.of());
        assertThat(memberList.status()).as(memberList.body()).isEqualTo(403);
        assertCode(memberList, "RICCON-AUTH-403");

        ApiTestClient.ApiResponse platformCreate =
                createExport(fixture.platform(), filter(hoursAgo(1), null, null, null, null, null, null));
        assertThat(platformCreate.status()).as(platformCreate.body()).isEqualTo(403);
        assertCode(platformCreate, "RICCON-AUDIT-403");

        ApiTestClient.ApiResponse platformList =
                client.get("/api/v1/audit-exports", fixture.platform().sessionCookie(), Map.of());
        assertThat(platformList.status()).as(platformList.body()).isEqualTo(403);
        assertCode(platformList, "RICCON-AUDIT-403");

        ApiTestClient.ApiResponse platformDetail = client.get(
                "/api/v1/audit-exports/" + exportId, fixture.platform().sessionCookie(), Map.of());
        assertThat(platformDetail.status()).as(platformDetail.body()).isEqualTo(403);
        assertCode(platformDetail, "RICCON-AUDIT-403");

        ApiTestClient.ApiResponse platformDownload = download(fixture.platform(), exportId);
        assertThat(platformDownload.status()).as(platformDownload.body()).isEqualTo(404);
        assertCode(platformDownload, "RICCON-TENANT-404");
    }

    /**
     * 申请人不得自批；状态保持 PENDING_APPROVAL。
     */
    @Test
    @DisplayName("IT-AUDIT-104 自批 403 RICCON-AUDIT-403")
    void decide_selfApprovalForbidden() {
        TenantFixture fixture = createFixture("ax");
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ApiTestClient.ApiResponse response = decide(fixture.tenantAdmin(), exportId, "APPROVE", "self");
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertCode(response, "RICCON-AUDIT-403");

        JsonNode detail = client.json(client.get(
                "/api/v1/audit-exports/" + exportId, fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(detail.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(tenantAuditRows(fixture.tenantId(), "audit.export.approved")).isEmpty();
    }

    /**
     * 另一管理员批准；申请人下载 CSV；审批人不能下载。
     */
    @Test
    @DisplayName("IT-AUDIT-104 另一管理员批准后申请人下载 CSV，审批人 403")
    void decide_otherAdminApprovesThenRequesterDownloads() throws Exception {
        TenantFixture fixture = createFixture("ax");
        LoginSession approver = createSecondAdmin(fixture);
        Instant base = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        for (int index = 0; index < 3; index++) {
            insertAuditEvent(
                    fixture.tenantId(),
                    base.plusSeconds(index),
                    "USER",
                    fixture.tenantAdminId().toString(),
                    "file.uploaded",
                    "FILE",
                    "file-" + index,
                    "SUCCESS",
                    "{\"fileId\":\"file-" + index + "\"}");
        }
        UUID exportId = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));

        ApiTestClient.ApiResponse decision = decide(approver, exportId, "APPROVE", "同意导出");
        assertThat(decision.status()).as(decision.body()).isEqualTo(200);
        JsonNode approved = client.json(decision);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approvedBy").asText()).isNotBlank();
        assertThat(approved.path("approvedByDisplayName").asText()).startsWith("User adm");
        assertThat(approved.path("decidedAt").asText()).endsWith("+08:00");
        assertThat(approved.path("expiresAt").asText()).endsWith("+08:00");
        assertThat(approved.path("decisionComment").asText()).isEqualTo("同意导出");

        ApiTestClient.ApiResponse approverDownload = download(approver, exportId);
        assertThat(approverDownload.status()).as(approverDownload.body()).isEqualTo(403);
        assertCode(approverDownload, "RICCON-AUDIT-403");

        ApiTestClient.ApiResponse csv = download(fixture.tenantAdmin(), exportId);
        assertThat(csv.status()).as(csv.body()).isEqualTo(200);
        assertThat(csv.header("content-type")).contains("text/csv").contains("charset=utf-8");
        assertThat(csv.header("content-disposition"))
                .isEqualTo("attachment; filename=\"audit-export-" + exportId + ".csv\"");
        assertThat(csv.header("cache-control")).isEqualTo("no-store");
        assertThat(csv.header("x-content-type-options")).isEqualTo("nosniff");
        String body = csv.body();
        assertThat(body).startsWith("\uFEFF# exportId: " + exportId);
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(9);
        assertThat(lines[1]).startsWith("# generatedAt: ");
        assertThat(lines[2]).startsWith("# filters: {");
        assertThat(lines[3]).isEqualTo("# rowCount: 3");
        assertThat(lines[4]).isEmpty();
        assertThat(lines[5])
                .isEqualTo(
                        "eventId,occurredAt,tenantId,actorType,actorId,action,targetType,targetId,outcome,traceId,details");
        assertThat(body).hasSizeGreaterThan(0);
        assertThat(body).doesNotContain(SOURCE_IP).doesNotContain("sourceIp").doesNotContain("requestId");

        List<AuditRow> approvalAudit = tenantAuditRows(fixture.tenantId(), "audit.export.approved");
        assertThat(approvalAudit).hasSize(1);
        assertThat(approvalAudit.get(0).targetId()).isEqualTo(exportId.toString());
        JsonNode approvalDetails = objectMapper.readTree(approvalAudit.get(0).details());
        assertThat(approvalDetails.path("exportId").asText()).isEqualTo(exportId.toString());
        assertThat(approvalDetails.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(approvalDetails.path("estimatedRowCount").asInt()).isEqualTo(3);
        assertThat(approvalDetails.path("filtersHash").asText()).hasSize(64);
        // 审批意见只存导出记录，不进入审计 details。
        assertThat(approvalAudit.get(0).details()).doesNotContain("同意导出");

        List<AuditRow> exportedAudit = tenantAuditRows(fixture.tenantId(), "data.exported");
        assertThat(exportedAudit).hasSize(1);
        assertThat(exportedAudit.get(0).targetId()).isEqualTo(exportId.toString());
        JsonNode exportedDetails = objectMapper.readTree(exportedAudit.get(0).details());
        assertThat(exportedDetails.path("exportId").asText()).isEqualTo(exportId.toString());
        assertThat(exportedDetails.path("scope").asText()).isEqualTo("audit");
        assertThat(exportedDetails.path("rowCount").asInt()).isEqualTo(3);
    }

    /**
     * 拒绝：状态 REJECTED、意见存记录但不进审计、无过期时间。
     */
    @Test
    @DisplayName("IT-AUDIT-104 拒绝保存意见与状态，不写意见进审计")
    void decide_rejectStoresComment() {
        TenantFixture fixture = createFixture("ax");
        LoginSession approver = createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ApiTestClient.ApiResponse rejected = decide(approver, exportId, "REJECT", "敏感原因不入审计");
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        JsonNode body = client.json(rejected);
        assertThat(body.path("status").asText()).isEqualTo("REJECTED");
        assertThat(body.path("decisionComment").asText()).isEqualTo("敏感原因不入审计");
        assertThat(body.path("expiresAt").isNull()).isTrue();
        assertThat(body.path("decidedAt").asText()).endsWith("+08:00");

        List<AuditRow> audit = tenantAuditRows(fixture.tenantId(), "audit.export.rejected");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).targetId()).isEqualTo(exportId.toString());
        assertThat(audit.get(0).details()).contains("REJECT").doesNotContain("敏感原因不入审计");
        assertThat(tenantAuditRows(fixture.tenantId(), "audit.export.approved")).isEmpty();
    }

    /**
     * 未批准下载 409；非申请人 403。
     */
    @Test
    @DisplayName("IT-AUDIT-104 未批准下载 409，非申请人 403")
    void download_pendingAndNonRequesterRejected() {
        TenantFixture fixture = createFixture("ax");
        LoginSession approver = createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ApiTestClient.ApiResponse pending = download(fixture.tenantAdmin(), exportId);
        assertThat(pending.status()).as(pending.body()).isEqualTo(409);
        assertCode(pending, "RICCON-AUDIT-409");

        assertThat(decide(approver, exportId, "APPROVE", null).status()).isEqualTo(200);
        ApiTestClient.ApiResponse nonRequester = download(fixture.member(), exportId);
        // 普通员工无 audit.read，权限拦截先于业务判断。
        assertThat(nonRequester.status()).as(nonRequester.body()).isEqualTo(403);
        assertCode(nonRequester, "RICCON-AUTH-403");

        ApiTestClient.ApiResponse approverDownload = download(approver, exportId);
        assertThat(approverDownload.status()).as(approverDownload.body()).isEqualTo(403);
        assertCode(approverDownload, "RICCON-AUDIT-403");
    }

    /**
     * 并发审批只有一个成功，另一个 409。
     */
    @Test
    @DisplayName("IT-AUDIT-104 并发审批 200/409 各一")
    void decide_concurrentApprovalOneWins() throws Exception {
        TenantFixture fixture = createFixture("ax");
        LoginSession approverOne = createSecondAdmin(fixture);
        LoginSession approverTwo = createSecondAdmin(fixture);
        UUID exportId = createExportId(fixture.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiTestClient.ApiResponse>> futures = new ArrayList<>();
            for (LoginSession session : List.of(approverOne, approverTwo)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return decide(session, exportId, "APPROVE", null);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<ApiTestClient.ApiResponse> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS).status());
            }
            statuses.sort(Integer::compareTo);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }

        List<AuditRow> audit = tenantAuditRows(fixture.tenantId(), "audit.export.approved");
        assertThat(audit).hasSize(1);
    }

    /**
     * 列表按状态过滤（含派生 EXPIRED 为空）。
     */
    @Test
    @DisplayName("IT-AUDIT-104 列表按状态过滤")
    void list_filtersByStatus() {
        TenantFixture fixture = createFixture("ax");
        LoginSession approver = createSecondAdmin(fixture);
        UUID pending = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.uploaded", null, null, null, null));
        UUID approved = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.deleted", null, null, null, null));
        UUID rejected = createExportId(
                fixture.tenantAdmin(), filter(hoursAgo(1), null, "file.downloaded", null, null, null, null));
        assertThat(decide(approver, approved, "APPROVE", null).status()).isEqualTo(200);
        assertThat(decide(approver, rejected, "REJECT", null).status()).isEqualTo(200);

        JsonNode pendingPage = client.json(client.get(
                "/api/v1/audit-exports?status=PENDING_APPROVAL",
                fixture.tenantAdmin().sessionCookie(),
                Map.of()));
        assertThat(pendingPage.path("total").asLong()).isEqualTo(1);
        assertThat(pendingPage.path("items").get(0).path("id").asText()).isEqualTo(pending.toString());

        JsonNode approvedPage = client.json(client.get(
                "/api/v1/audit-exports?status=APPROVED", fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(approvedPage.path("total").asLong()).isEqualTo(1);
        assertThat(approvedPage.path("items").get(0).path("status").asText()).isEqualTo("APPROVED");

        JsonNode rejectedPage = client.json(client.get(
                "/api/v1/audit-exports?status=REJECTED", fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(rejectedPage.path("total").asLong()).isEqualTo(1);

        JsonNode expiredPage = client.json(client.get(
                "/api/v1/audit-exports?status=EXPIRED", fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(expiredPage.path("total").asLong()).isZero();

        JsonNode all = client.json(
                client.get("/api/v1/audit-exports", fixture.tenantAdmin().sessionCookie(), Map.of()));
        assertThat(all.path("total").asLong()).isEqualTo(3);
        assertThat(all.path("items").get(0).path("createdAt").asText()).endsWith("+08:00");

        assertCode(
                client.get(
                        "/api/v1/audit-exports?status=UNKNOWN",
                        fixture.tenantAdmin().sessionCookie(),
                        Map.of()),
                "RICCON-AUDIT-400");
    }

    /**
     * 跨租户导出统一 404，不泄露存在性。
     */
    @Test
    @DisplayName("IT-AUDIT-104 跨租户详情/下载统一 404")
    void export_crossTenantNotFound() {
        TenantFixture owner = createFixture("ax");
        TenantFixture other = createFixture("ax");
        LoginSession otherApprover = createSecondAdmin(other);
        UUID exportId = createExportId(owner.tenantAdmin(), filter(hoursAgo(1), null, null, null, null, null, null));
        assertThat(decide(otherApprover, exportId, "APPROVE", null).status()).isEqualTo(404);

        ApiTestClient.ApiResponse detail = client.get(
                "/api/v1/audit-exports/" + exportId, other.tenantAdmin().sessionCookie(), Map.of());
        assertThat(detail.status()).as(detail.body()).isEqualTo(404);
        assertCode(detail, "RICCON-TENANT-404");

        ApiTestClient.ApiResponse download = download(other.tenantAdmin(), exportId);
        assertThat(download.status()).as(download.body()).isEqualTo(404);
        assertCode(download, "RICCON-TENANT-404");

        ApiTestClient.ApiResponse decision = decide(otherApprover, exportId, "APPROVE", null);
        assertThat(decision.status()).as(decision.body()).isEqualTo(404);
    }
}
