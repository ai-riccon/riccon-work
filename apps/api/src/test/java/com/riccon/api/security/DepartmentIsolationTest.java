package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.TestFiles;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M03 部门维度越权独立套件（DEPT-001..010）。
 *
 * <p>验证 ADR-0018 第 3 节的数据范围执行点：其他部门、未分配部门与跨租户对空间、会话、文件、报告、
 * 产物、运行、知识库与导出路径的访问必须统一 404，且响应不含业务内容或账号信息；成员移出、部门停用与
 * 负责人移交在下一请求生效；单主部门与负责人约束不能被绕过。
 *
 * <p>所有用例使用 {@code @Tag("dept-isolation")}，零 skip/xfail；断言以攻击者视角直接持有对象 ID。
 */
@Tag("dept-isolation")
class DepartmentIsolationTest extends SecurityTestSupport {

    /** DEPT 套件完整性清单：ID 必须出现在 surefire 报告且方法存在。 */
    @Test
    @DisplayName("DEPT 套件完整性清单 DEPT-001..010")
    void deptSuiteIntegrityManifest() {
        assertSuiteManifest("DEPT", 10, "dept");
    }

    /** DEPT-001：他部门员工持有空间 ID 访问详情与列表，统一 404 且不泄露名称/数量。 */
    @Test
    @DisplayName("DEPT-001 他部门访问空间详情与列表统一 404")
    void dept001_otherDepartmentWorkspaceIsInvisible() {
        Fixture fixture = fixture("dept001");
        String secretName = "销售部机密集群";

        ApiTestClient.ApiResponse detail =
                get(fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace());
        assertNotFound(detail);
        assertNoLeak(detail, secretName, fixture.tenant());

        JsonNode outsiderList = list(fixture.outsider().session(), "/api/v1/workspaces");
        assertThat(outsiderList.path("items").toString()).doesNotContain(fixture.departmentWorkspace());
        assertThat(outsiderList.toString()).doesNotContain("部门共享空间");

        JsonNode unassignedList = list(fixture.unassigned().session(), "/api/v1/workspaces");
        assertThat(unassignedList.path("items").toString()).doesNotContain(fixture.departmentWorkspace());

        ApiTestClient.ApiResponse otherTenant = get(
                crossTenantMember(fixture.platform()).member().session(),
                "/api/v1/workspaces/" + fixture.departmentWorkspace());
        assertNotFound(otherTenant);
    }

    /** DEPT-002：他部门员工持有会话、文件、报告任务、产物 ID 直接访问，统一 404 且无对象元数据。 */
    @Test
    @DisplayName("DEPT-002 他部门访问会话/文件/报告/产物统一 404")
    void dept002_otherDepartmentObjectsAreInvisible() {
        Fixture fixture = fixture("dept002");
        String conversationId = createConversation(fixture.tenantAdmin(), fixture.departmentWorkspace());
        String fileId = upload(
                        fixture.tenantAdmin(),
                        fixture.departmentWorkspace(),
                        "销售部合同.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();
        ResearchSeed research =
                seedResearchTask(fixture.tenant(), fixture.departmentWorkspace(), fixture.tenantAdminId(), "销售部市场报告");
        UUID artifactId =
                seedArtifact(fixture.tenant(), fixture.departmentWorkspace(), fixture.tenantAdminId(), "销售部机密报告");
        UUID runId = seedChatRun(fixture.tenant(), UUID.fromString(conversationId), fixture.tenantAdminId());

        assertNotFound(get(fixture.outsider().session(), "/api/v1/conversations/" + conversationId));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/conversations/" + conversationId + "/messages"));
        assertNotFound(get(
                fixture.outsider().session(),
                "/api/v1/workspaces/" + fixture.departmentWorkspace() + "/conversations"));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/files/" + fileId));
        assertNotFound(
                get(fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace() + "/files"));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/research-tasks/" + research.taskId()));
        assertNotFound(get(
                fixture.outsider().session(),
                "/api/v1/workspaces/" + fixture.departmentWorkspace() + "/research-tasks"));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/artifacts/" + artifactId));
        assertNotFound(get(
                fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace() + "/artifacts"));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/runs/" + runId));

        ApiTestClient.ApiResponse conversationLeak =
                get(fixture.outsider().session(), "/api/v1/conversations/" + conversationId);
        assertNoLeak(conversationLeak, "销售部合同.pdf", "销售部市场报告", "销售部机密报告");
        assertThat(conversationLeak.body()).doesNotContain("@");
    }

    /** DEPT-003：他部门员工请求文件/产物下载，统一 404；响应不签发 URL、不泄露对象存储 key。 */
    @Test
    @DisplayName("DEPT-003 他部门下载文件与产物被拒且不签发 URL")
    void dept003_otherDepartmentDownloadsAreRejected() {
        Fixture fixture = fixture("dept003");
        String fileId = upload(
                        fixture.tenantAdmin(),
                        fixture.departmentWorkspace(),
                        "报价单.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();
        UUID artifactId =
                seedArtifact(fixture.tenant(), fixture.departmentWorkspace(), fixture.tenantAdminId(), "报价分析");

        ApiTestClient.ApiResponse fileDownload =
                get(fixture.outsider().session(), "/api/v1/files/" + fileId + "/download-url");
        assertNotFound(fileDownload);
        assertThat(fileDownload.body())
                .doesNotContain("http")
                .doesNotContain("X-Amz")
                .doesNotContain("tenants/");

        ApiTestClient.ApiResponse artifactDownload =
                get(fixture.outsider().session(), "/api/v1/artifacts/" + artifactId + "/versions/1/download-url");
        assertNotFound(artifactDownload);
        assertThat(artifactDownload.body())
                .doesNotContain("http")
                .doesNotContain("X-Amz")
                .doesNotContain("tenants/");

        ApiTestClient.ApiResponse artifactContent =
                get(fixture.outsider().session(), "/api/v1/artifacts/" + artifactId + "/versions/1/content");
        assertNotFound(artifactContent);
        assertThat(artifactContent.body()).doesNotContain("安全测试产物摘要");

        CrossTenantMember crossTenant = crossTenantMember(fixture.platform());
        ApiTestClient.ApiResponse crossDownload =
                get(crossTenant.member().session(), "/api/v1/files/" + fileId + "/download-url");
        assertNotFound(crossDownload);
        assertThat(crossDownload.body()).doesNotContain("tenants/").doesNotContain("X-Amz");
    }

    /** DEPT-004：同部门另一成员访问个人空间会话/文件统一 404；个人对象不出现在部门列表与知识库。 */
    @Test
    @DisplayName("DEPT-004 个人空间对同部门成员仍私有")
    void dept004_personalWorkspaceStaysPrivateWithinDepartment() {
        Fixture fixture = fixture("dept004");
        String personalConversation = createConversation(fixture.tenantAdmin(), fixture.adminPersonal());
        String personalFile = upload(
                        fixture.tenantAdmin(),
                        fixture.adminPersonal(),
                        "个人笔记.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();

        assertNotFound(get(fixture.sibling().session(), "/api/v1/conversations/" + personalConversation));
        assertNotFound(get(fixture.sibling().session(), "/api/v1/files/" + personalFile));
        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.adminPersonal()));
        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.adminPersonal() + "/files"));

        JsonNode siblingList = list(fixture.sibling().session(), "/api/v1/workspaces");
        assertThat(siblingList.path("items").toString()).doesNotContain(fixture.adminPersonal());
        assertThat(siblingList.toString()).doesNotContain("个人笔记.pdf");

        JsonNode knowledge = list(fixture.sibling().session(), "/api/v1/knowledge/documents");
        assertThat(knowledge.path("items").toString()).doesNotContain(personalFile);
    }

    /** DEPT-005：单主部门约束不可绕过；跨租户账号 404；移除负责人 409。 */
    @Test
    @DisplayName("DEPT-005 单主部门与负责人约束")
    void dept005_singleDepartmentConstraintCannotBeBypassed() {
        Fixture fixture = fixture("dept005");
        CreatedMember candidate = createMember(fixture.platform(), fixture.tenant());
        assertThat(addDepartmentMember(fixture.platform(), fixture.tenant(), fixture.departmentA(), candidate.id())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse duplicate =
                addDepartmentMember(fixture.platform(), fixture.tenant(), fixture.departmentA(), candidate.id());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-DEPT-409");

        ApiTestClient.ApiResponse secondDepartment =
                addDepartmentMember(fixture.platform(), fixture.tenant(), fixture.departmentB(), candidate.id());
        assertThat(secondDepartment.status()).isEqualTo(409);
        assertThat(client.json(secondDepartment).path("code").asText()).isEqualTo("RICCON-DEPT-409");
        assertThat(userDepartment(fixture.tenant(), candidate.id())).isEqualTo(fixture.departmentA());

        CrossTenantMember crossTenant = crossTenantMember(fixture.platform());
        ApiTestClient.ApiResponse crossTenantAdd = addDepartmentMember(
                fixture.platform(),
                fixture.tenant(),
                fixture.departmentA(),
                crossTenant.member().id());
        assertThat(crossTenantAdd.status()).isEqualTo(404);
        assertThat(client.json(crossTenantAdd).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(crossTenantAdd.body()).doesNotContain(crossTenant.member().username());
        assertThat(userDepartmentOrNull(
                        crossTenant.tenant(), crossTenant.member().id()))
                .isNull();

        ApiTestClient.ApiResponse removeManager = removeDepartmentMember(
                fixture.platform(), fixture.tenant(), fixture.departmentA(), fixture.managerId());
        assertThat(removeManager.status()).isEqualTo(409);
        assertThat(client.json(removeManager).path("code").asText()).isEqualTo("RICCON-DEPT-409");
        assertThat(userDepartment(fixture.tenant(), fixture.managerId())).isEqualTo(fixture.departmentA());
    }

    /** DEPT-006：部门停用后下一次请求即 404，重新启用恢复原可见面。 */
    @Test
    @DisplayName("DEPT-006 部门停用立即撤销访问，启用后恢复")
    void dept006_disabledDepartmentRevokesAccessImmediately() {
        Fixture fixture = fixture("dept006");
        String conversationId = createConversation(fixture.tenantAdmin(), fixture.departmentWorkspace());
        assertThat(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace())
                        .status())
                .isEqualTo(200);

        assertThat(setDepartmentStatus(fixture.platform(), fixture.tenant(), fixture.departmentA(), "disable")
                        .status())
                .isEqualTo(200);
        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace()));
        assertNotFound(get(fixture.manager(), "/api/v1/workspaces/" + fixture.departmentWorkspace()));
        assertNotFound(get(fixture.sibling().session(), "/api/v1/conversations/" + conversationId));
        assertThat(list(fixture.sibling().session(), "/api/v1/workspaces").toString())
                .doesNotContain(fixture.departmentWorkspace());

        assertThat(setDepartmentStatus(fixture.platform(), fixture.tenant(), fixture.departmentA(), "enable")
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace())
                        .status())
                .isEqualTo(200);
    }

    /** DEPT-007：成员移出部门后 DEPT/CUSTOM 访问均失效，残留成员行不授权。 */
    @Test
    @DisplayName("DEPT-007 成员移出后部门空间访问立即失效")
    void dept007_removedMemberLosesDepartmentAccess() {
        Fixture fixture = fixture("dept007");
        assertThat(addWorkspaceMember(
                                fixture.manager(),
                                fixture.customWorkspace(),
                                fixture.sibling().id())
                        .status())
                .isEqualTo(201);
        assertThat(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.customWorkspace())
                        .status())
                .isEqualTo(200);

        assertThat(removeDepartmentMember(
                                fixture.platform(),
                                fixture.tenant(),
                                fixture.departmentA(),
                                fixture.sibling().id())
                        .status())
                .isEqualTo(204);

        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace()));
        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.customWorkspace()));
        assertNotFound(
                get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.customWorkspace() + "/members"));
        assertThat(workspaceMemberCount(fixture.tenant(), fixture.customWorkspace()))
                .isZero();
        assertThat(list(fixture.sibling().session(), "/api/v1/workspaces").toString())
                .doesNotContain(fixture.customWorkspace());

        // 残留行即使被重新种入，也必须因“非本部门成员”而不授权。
        jdbcClient
                .sql("""
                        INSERT INTO workspace_member (tenant_id, workspace_id, user_id, granted_by)
                        VALUES (:tenantId, :workspaceId, :userId, :grantedBy)
                        """)
                .param("tenantId", fixture.tenant())
                .param(
                        "workspaceId",
                        com.riccon.api.persistence.BinaryUuids.toBytes(UUID.fromString(fixture.customWorkspace())))
                .param(
                        "userId",
                        com.riccon.api.persistence.BinaryUuids.toBytes(
                                fixture.sibling().id()))
                .param("grantedBy", com.riccon.api.persistence.BinaryUuids.toBytes(fixture.managerId()))
                .update();
        assertNotFound(get(fixture.sibling().session(), "/api/v1/workspaces/" + fixture.customWorkspace()));
    }

    /** DEPT-008：他部门负责人不能管理本部门空间；负责人移交后旧负责人 403、新负责人 200 并审计。 */
    @Test
    @DisplayName("DEPT-008 负责人越权与移交")
    void dept008_managerCannotCrossManageAndTransferTakesEffect() {
        Fixture fixture = fixture("dept008");
        assertThat(transferDepartmentManager(
                                fixture.platform(),
                                fixture.tenant(),
                                fixture.departmentB(),
                                fixture.outsider().id())
                        .status())
                .isEqualTo(200);

        assertNotFound(get(fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace()));
        assertNotFound(patchWorkspace(fixture.outsider().session(), fixture.departmentWorkspace(), "他部门改名"));
        assertNotFound(
                get(fixture.outsider().session(), "/api/v1/departments/" + fixture.departmentA() + "/workspaces"));
        assertNotFound(
                createDepartmentWorkspaceRaw(fixture.outsider().session(), fixture.departmentA(), "越权空间", "DEPT"));

        assertThat(patchWorkspace(fixture.manager(), fixture.departmentWorkspace(), "负责人改名")
                        .status())
                .isEqualTo(200);
        assertThat(transferDepartmentManager(
                                fixture.platform(),
                                fixture.tenant(),
                                fixture.departmentA(),
                                fixture.sibling().id())
                        .status())
                .isEqualTo(200);

        assertThat(patchWorkspace(fixture.manager(), fixture.departmentWorkspace(), "旧负责人改名")
                        .status())
                .isEqualTo(403);
        assertThat(patchWorkspace(fixture.sibling().session(), fixture.departmentWorkspace(), "新负责人改名")
                        .status())
                .isEqualTo(200);

        List<AuditRow> managerChanges = auditRows("department.manager.changed").stream()
                .filter(row -> fixture.departmentA().toString().equals(row.targetId()))
                .toList();
        assertThat(managerChanges).hasSize(2);
        assertThat(managerChanges.get(0).tenantId()).isEqualTo("platform");
        assertThat(managerChanges.get(0).details())
                .contains(fixture.tenant())
                .contains(fixture.departmentA().toString())
                .contains(fixture.managerId().toString());
        assertThat(managerChanges.get(1).details())
                .contains(fixture.sibling().id().toString());
    }

    /** DEPT-009：空间枚举的 404 状态、错误码与响应结构一致，不泄露其他部门存在性。 */
    @Test
    @DisplayName("DEPT-009 空间枚举不泄露存在性")
    void dept009_enumerationDoesNotRevealExistence() {
        Fixture fixture = fixture("dept009");
        String missing = UUID.randomUUID().toString();

        ApiTestClient.ApiResponse missingResponse = get(fixture.outsider().session(), "/api/v1/workspaces/" + missing);
        ApiTestClient.ApiResponse existingResponse =
                get(fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace());
        assertThat(missingResponse.status())
                .isEqualTo(existingResponse.status())
                .isEqualTo(404);
        JsonNode missingBody = client.json(missingResponse);
        JsonNode existingBody = client.json(existingResponse);
        assertThat(missingBody.path("code").asText())
                .isEqualTo(existingBody.path("code").asText());
        assertThat(missingBody.path("title").asText())
                .isEqualTo(existingBody.path("title").asText());
        assertThat(fieldNames(missingBody)).isEqualTo(fieldNames(existingBody));

        Duration missingAverage =
                averageDuration(() -> get(fixture.outsider().session(), "/api/v1/workspaces/" + missing));
        Duration existingAverage = averageDuration(
                () -> get(fixture.outsider().session(), "/api/v1/workspaces/" + fixture.departmentWorkspace()));
        long allowed = Math.max(300, missingAverage.toMillis() * 10);
        assertThat(existingAverage.toMillis()).as("其他部门空间响应时间不得显著高于普通不存在").isLessThanOrEqualTo(allowed);

        JsonNode outsiderList = list(fixture.outsider().session(), "/api/v1/workspaces");
        assertThat(outsiderList.path("total").asLong())
                .isEqualTo(outsiderList.path("items").size());
        assertThat(outsiderList.path("items").toString())
                .doesNotContain(fixture.departmentWorkspace())
                .doesNotContain("部门共享空间");
    }

    /** DEPT-010：知识库引用租户级可读但源文件 404；M03 无审计导出接口。 */
    @Test
    @DisplayName("DEPT-010 知识库租户级引用与源文件边界")
    void dept010_knowledgeIsTenantWideButSourceFileStaysScoped() {
        Fixture fixture = fixture("dept010");
        String fileId = upload(
                        fixture.tenantAdmin(),
                        fixture.departmentWorkspace(),
                        "市场研究.pdf",
                        "application/pdf",
                        TestFiles.pdf(),
                        null)
                .path("id")
                .asText();
        ApiTestClient.ApiResponse created = client.post(
                "/api/v1/knowledge/documents",
                client.jsonBody("title", "销售部知识文档", "fileId", fileId),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of("Idempotency-Key", "security-knowledge-" + randomSuffix()));
        assertThat(created.status()).as(created.body()).isEqualTo(202);
        String documentId = client.json(created).path("id").asText();

        JsonNode outsiderKnowledge = list(fixture.outsider().session(), "/api/v1/knowledge/documents");
        assertThat(outsiderKnowledge.path("items").toString()).contains(documentId);
        assertThat(get(fixture.outsider().session(), "/api/v1/knowledge/documents/" + documentId)
                        .status())
                .isEqualTo(200);
        assertNotFound(get(fixture.outsider().session(), "/api/v1/files/" + fileId));
        assertNotFound(get(fixture.outsider().session(), "/api/v1/files/" + fileId + "/download-url"));

        // M03 不提供审计查询/导出；任何导出路径不得返回内容。
        for (String path :
                List.of("/api/v1/audit/exports", "/api/v1/audit/events/export", "/api/v1/audit/export-requests")) {
            ApiTestClient.ApiResponse export = get(fixture.tenantAdmin(), path);
            assertThat(export.status()).as(path).isIn(403, 404);
            assertThat(export.body()).doesNotContain("eventId").doesNotContain("tenant_id");
        }
    }

    /**
     * PATCH 空间。
     *
     * @param session 会话
     * @param workspaceId 空间
     * @param name 新名称
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse patchWorkspace(LoginSession session, String workspaceId, String name) {
        return client.patch(
                "/api/v1/workspaces/" + workspaceId,
                client.jsonBody("name", name),
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 切换部门状态；停用/启用均为幂等 POST。
     *
     * @param platform 平台管理员
     * @param tenant 租户
     * @param departmentId 部门
     * @param action disable 或 enable
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse setDepartmentStatus(
            LoginSession platform, String tenant, UUID departmentId, String action) {
        return client.post(
                adminDepartmentsPath(tenant) + "/" + departmentId + "/" + action,
                null,
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 读取 ProblemDetail 的字段名集合。
     *
     * @param body JSON
     * @return 排序后的字段名
     */
    private List<String> fieldNames(JsonNode body) {
        return body.properties().stream().map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * 计算 3 次请求的平均耗时。
     *
     * @param action 请求动作
     * @return 平均耗时
     */
    private Duration averageDuration(Runnable action) {
        long start = System.nanoTime();
        for (int index = 0; index < 3; index += 1) {
            action.run();
        }
        return Duration.ofNanos((System.nanoTime() - start) / 3);
    }
}
