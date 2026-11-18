package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.organization.domain.DepartmentMemberSnapshot;
import com.riccon.api.organization.domain.DepartmentSnapshot;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 部门后端集成测试。
 *
 * <p>覆盖 ADR-0018 B2 验收：平台管理员跨租户创建/改名/停用/启用、成员加/移与单主部门、负责人移交、
 * 目标租户 404/停用 409、租户管理员 403、审计 details、移出成员清理本部门 CUSTOM 空间成员行，以及
 * {@code OrganizationDirectory} 的租户范围只读语义。使用真实 MySQL 与 HTTP，不 mock 持久化。
 */
class DepartmentAdminApiTest extends DepartmentTestSupport {

    /** 平台管理员创建部门：响应字段、列表与审计 details 均符合 ADR。 */
    @Test
    @DisplayName("IT-DEPT-001 平台管理员创建部门并写组织元数据审计")
    void createDepartment_platformAdmin_returnsMetadataAndAudit() {
        TenantFixture fixture = createFixture("dept");
        String name = "销售部";

        ApiTestClient.ApiResponse created = client.post(
                departmentsPath(fixture.tenantId()),
                client.jsonBody("name", name),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode body = client.json(created);
        UUID departmentId = UUID.fromString(body.path("id").asText());
        assertThat(body.path("name").asText()).isEqualTo(name);
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("memberCount").asInt()).isZero();
        assertThat(body.path("managerUserId").isNull()).as(body.toString()).isTrue();
        assertThat(body.path("managerDisplayName").isNull()).as(body.toString()).isTrue();
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(body.path("updatedAt").asText()).isNotBlank();

        ApiTestClient.ApiResponse list = client.get(
                departmentsPath(fixture.tenantId()), fixture.platform().sessionCookie(), Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        JsonNode page = client.json(list);
        assertThat(page.path("total").asLong()).isEqualTo(1L);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(departmentId.toString());
        assertThat(page.path("items").get(0).path("managerDisplayName").isNull())
                .as(page.toString())
                .isTrue();

        List<AuditRow> rows = departmentAuditRows("department.created", departmentId);
        assertThat(rows).hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.tenantId()).isEqualTo("platform");
        assertThat(row.actorType()).isEqualTo("USER");
        assertThat(row.actorId()).isEqualTo(fixture.platformUserId().toString());
        assertThat(row.targetType()).isEqualTo("DEPARTMENT");
        assertThat(row.outcome()).isEqualTo("SUCCESS");
        assertThat(row.details())
                .contains(fixture.tenantId())
                .contains(departmentId.toString())
                .contains(name);
        // details 不得出现账号 PII 或密码。
        assertThat(row.details()).doesNotContain("@").doesNotContain("password");

        // 同租户内重名冲突必须返回稳定部门错误码。
        ApiTestClient.ApiResponse duplicate = client.post(
                departmentsPath(fixture.tenantId()),
                client.jsonBody("name", name),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(duplicate.status()).as(duplicate.body()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-DEPT-409");
    }

    /** 重命名只允许启用部门；同名请求幂等且不写重复审计。 */
    @Test
    @DisplayName("IT-DEPT-001 重命名部门写 name/previousName 审计且同名幂等")
    void renameDepartment_updatesActiveDepartmentAndAuditsOnce() {
        TenantFixture fixture = createFixture("dept");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "研发部");

        ApiTestClient.ApiResponse renamed = client.patch(
                departmentsPath(fixture.tenantId()) + "/" + departmentId,
                client.jsonBody("name", "平台研发部"),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken());
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(client.json(renamed).path("name").asText()).isEqualTo("平台研发部");

        ApiTestClient.ApiResponse again = client.patch(
                departmentsPath(fixture.tenantId()) + "/" + departmentId,
                client.jsonBody("name", "平台研发部"),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken());
        assertThat(again.status()).as(again.body()).isEqualTo(200);

        List<AuditRow> rows = departmentAuditRows("department.renamed", departmentId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo("platform");
        assertThat(rows.get(0).details())
                .contains(fixture.tenantId())
                .contains("平台研发部")
                .contains("研发部");
    }

    /** 停用/启用幂等，成员归属保留；停用部门不可改名。 */
    @Test
    @DisplayName("IT-DEPT-001 停用启用幂等保留成员，停用部门改名 409")
    void disableAndEnableDepartment_isIdempotentAndKeepsMembership() {
        TenantFixture fixture = createFixture("dept");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "运营部");
        CreatedMember member = createExtraMember(fixture.platform(), fixture.tenantId());
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, member.id())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse disabled = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/disable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        assertThat(client.json(disabled).path("status").asText()).isEqualTo("DISABLED");
        assertThat(userDepartment(fixture.tenantId(), member.id())).isEqualTo(departmentId);

        ApiTestClient.ApiResponse disabledAgain = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/disable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(disabledAgain.status()).as(disabledAgain.body()).isEqualTo(200);
        assertThat(departmentAuditRows("department.disabled", departmentId)).hasSize(1);

        ApiTestClient.ApiResponse renameDisabled = client.patch(
                departmentsPath(fixture.tenantId()) + "/" + departmentId,
                client.jsonBody("name", "运营二部"),
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken());
        assertThat(renameDisabled.status()).as(renameDisabled.body()).isEqualTo(409);
        assertThat(client.json(renameDisabled).path("code").asText()).isEqualTo("RICCON-DEPT-409");

        ApiTestClient.ApiResponse enabled = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/enable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        assertThat(client.json(enabled).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(userDepartment(fixture.tenantId(), member.id())).isEqualTo(departmentId);

        ApiTestClient.ApiResponse enabledAgain = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/enable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(enabledAgain.status()).as(enabledAgain.body()).isEqualTo(200);
        assertThat(departmentAuditRows("department.enabled", departmentId)).hasSize(1);
        assertThat(departmentAuditRows("department.disabled", departmentId)).hasSize(1);
    }

    /** 单主部门：同一账号只能属于一个部门，重复加入返回稳定 409。 */
    @Test
    @DisplayName("IT-DEPT-001 成员加入强制单主部门")
    void addMember_enforcesSinglePrimaryDepartment() {
        TenantFixture fixture = createFixture("dept");
        UUID sales = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        UUID marketing = createDepartmentId(fixture.platform(), fixture.tenantId(), "市场部");
        CreatedMember member = createExtraMember(fixture.platform(), fixture.tenantId());

        ApiTestClient.ApiResponse added = addMember(fixture.platform(), fixture.tenantId(), sales, member.id());
        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(client.json(added).path("id").asText()).isEqualTo(member.id().toString());
        assertThat(client.json(added).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(userDepartment(fixture.tenantId(), member.id())).isEqualTo(sales);

        ApiTestClient.ApiResponse duplicate = addMember(fixture.platform(), fixture.tenantId(), sales, member.id());
        assertThat(duplicate.status()).as(duplicate.body()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-DEPT-409");

        ApiTestClient.ApiResponse otherDepartment =
                addMember(fixture.platform(), fixture.tenantId(), marketing, member.id());
        assertThat(otherDepartment.status()).as(otherDepartment.body()).isEqualTo(409);
        assertThat(client.json(otherDepartment).path("code").asText()).isEqualTo("RICCON-DEPT-409");
        assertThat(userDepartment(fixture.tenantId(), member.id())).isEqualTo(sales);

        List<AuditRow> rows = departmentAuditRows("department.member.added", sales);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo("platform");
        assertThat(rows.get(0).details())
                .contains(fixture.tenantId())
                .contains(sales.toString())
                .contains(member.id().toString());
        // details 只允许 UUID 与枚举，不能出现登录名或展示名。
        assertThat(rows.get(0).details()).doesNotContain(member.username());
    }

    /** 跨租户账号按不存在处理，响应不泄露账号信息。 */
    @Test
    @DisplayName("IT-DEPT-001 跨租户账号加入返回统一 404")
    void addMember_rejectsCrossTenantAccountWithUnifiedNotFound() {
        TenantFixture target = createFixture("depta");
        TenantFixture other = createFixture("deptb");
        UUID departmentId = createDepartmentId(target.platform(), target.tenantId(), "销售部");

        ApiTestClient.ApiResponse response =
                addMember(target.platform(), target.tenantId(), departmentId, other.memberId());
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        assertThat(response.body()).doesNotContain(other.memberUsername());
    }

    /** 移除当前负责人返回 409；移除不存在成员幂等 204。 */
    @Test
    @DisplayName("IT-DEPT-001 移除负责人 409，移除不存在成员 204")
    void removeMember_blocksManagerAndIsIdempotent() {
        TenantFixture fixture = createFixture("dept");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        CreatedMember member = createExtraMember(fixture.platform(), fixture.tenantId());
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, member.id())
                        .status())
                .isEqualTo(201);
        assertThat(transferManager(fixture.platform(), fixture.tenantId(), departmentId, member.id())
                        .status())
                .isEqualTo(200);

        ApiTestClient.ApiResponse blocked =
                removeMember(fixture.platform(), fixture.tenantId(), departmentId, member.id());
        assertThat(blocked.status()).as(blocked.body()).isEqualTo(409);
        assertThat(client.json(blocked).path("code").asText()).isEqualTo("RICCON-DEPT-409");
        assertThat(userDepartment(fixture.tenantId(), member.id())).isEqualTo(departmentId);

        ApiTestClient.ApiResponse missing =
                removeMember(fixture.platform(), fixture.tenantId(), departmentId, UUID.randomUUID());
        assertThat(missing.status()).as(missing.body()).isEqualTo(204);
        assertThat(departmentAuditRows("department.member.removed", departmentId))
                .isEmpty();
    }

    /** 移出成员只清理本部门 CUSTOM 空间成员行，不触碰其他部门。 */
    @Test
    @DisplayName("IT-DEPT-001 移出成员清理本部门 CUSTOM 成员行")
    void removeMember_cleansOnlyDepartmentCustomWorkspaceMembers() {
        TenantFixture fixture = createFixture("dept");
        UUID sales = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        UUID marketing = createDepartmentId(fixture.platform(), fixture.tenantId(), "市场部");
        CreatedMember member = createExtraMember(fixture.platform(), fixture.tenantId());
        assertThat(addMember(fixture.platform(), fixture.tenantId(), sales, member.id())
                        .status())
                .isEqualTo(201);
        UUID salesWorkspace = insertCustomWorkspace(fixture.tenantId(), sales, fixture.tenantAdminId(), "销售部共享空间");
        UUID marketingWorkspace =
                insertCustomWorkspace(fixture.tenantId(), marketing, fixture.tenantAdminId(), "市场部共享空间");
        insertWorkspaceMember(fixture.tenantId(), salesWorkspace, member.id(), fixture.tenantAdminId());
        insertWorkspaceMember(fixture.tenantId(), marketingWorkspace, member.id(), fixture.tenantAdminId());

        ApiTestClient.ApiResponse removed = removeMember(fixture.platform(), fixture.tenantId(), sales, member.id());
        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
        assertThat(userDepartment(fixture.tenantId(), member.id())).isNull();
        assertThat(workspaceMemberCount(fixture.tenantId(), salesWorkspace)).isZero();
        assertThat(workspaceMemberCount(fixture.tenantId(), marketingWorkspace)).isEqualTo(1);

        List<AuditRow> rows = departmentAuditRows("department.member.removed", sales);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tenantId()).isEqualTo("platform");
        assertThat(rows.get(0).details())
                .contains(fixture.tenantId())
                .contains(member.id().toString())
                .contains("\"removedWorkspaceMembers\"")
                .contains(": 1");
        assertThat(rows.get(0).details()).doesNotContain(member.username());
    }

    /** 负责人只能移交给本部门启用成员；同人幂等，一次移交只写一条审计。 */
    @Test
    @DisplayName("IT-DEPT-001 负责人移交仅限本部门启用成员且幂等")
    void transferManager_requiresActiveDepartmentMemberAndIsIdempotent() {
        TenantFixture fixture = createFixture("dept");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        CreatedMember first = createExtraMember(fixture.platform(), fixture.tenantId());
        CreatedMember second = createExtraMember(fixture.platform(), fixture.tenantId());
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, first.id())
                        .status())
                .isEqualTo(201);
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, second.id())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse appointed =
                transferManager(fixture.platform(), fixture.tenantId(), departmentId, first.id());
        assertThat(appointed.status()).as(appointed.body()).isEqualTo(200);
        assertThat(client.json(appointed).path("managerUserId").asText())
                .isEqualTo(first.id().toString());
        assertThat(client.json(appointed).path("memberCount").asInt()).isEqualTo(2);

        ApiTestClient.ApiResponse sameAgain =
                transferManager(fixture.platform(), fixture.tenantId(), departmentId, first.id());
        assertThat(sameAgain.status()).as(sameAgain.body()).isEqualTo(200);
        assertThat(departmentAuditRows("department.manager.changed", departmentId))
                .hasSize(1);

        ApiTestClient.ApiResponse transferred =
                transferManager(fixture.platform(), fixture.tenantId(), departmentId, second.id());
        assertThat(transferred.status()).as(transferred.body()).isEqualTo(200);
        List<AuditRow> rows = departmentAuditRows("department.manager.changed", departmentId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).details())
                .contains(second.id().toString())
                .contains(first.id().toString())
                .doesNotContain(first.username());

        // 停用账号仍是“归属成员”，但不是启用成员，不能成为负责人。
        ApiTestClient.ApiResponse disabledUser = client.patch(
                "/api/v1/users/" + first.id(),
                client.jsonBody("status", "DISABLED"),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken());
        assertThat(disabledUser.status()).as(disabledUser.body()).isEqualTo(200);
        ApiTestClient.ApiResponse inactive =
                transferManager(fixture.platform(), fixture.tenantId(), departmentId, first.id());
        assertThat(inactive.status()).as(inactive.body()).isEqualTo(409);
        assertThat(client.json(inactive).path("code").asText()).isEqualTo("RICCON-DEPT-409");

        TenantFixture other = createFixture("deptx");
        ApiTestClient.ApiResponse crossTenant =
                transferManager(fixture.platform(), fixture.tenantId(), departmentId, other.memberId());
        assertThat(crossTenant.status()).as(crossTenant.body()).isEqualTo(404);
        assertThat(client.json(crossTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /** 租户端只读：成员只看本部门，租户管理员看全部但无管理权限。 */
    @Test
    @DisplayName("IT-DEPT-001 租户端部门只读按角色与部门隔离")
    void tenantReadEndpoints_areScopedByRoleAndDepartment() {
        TenantFixture fixture = createFixture("dept");
        UUID sales = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        UUID marketing = createDepartmentId(fixture.platform(), fixture.tenantId(), "市场部");
        assertThat(addMember(fixture.platform(), fixture.tenantId(), sales, fixture.memberId())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse adminList =
                client.get("/api/v1/departments", fixture.tenantAdmin().sessionCookie(), Map.of());
        assertThat(adminList.status()).as(adminList.body()).isEqualTo(200);
        assertThat(client.json(adminList).path("total").asLong()).isEqualTo(2L);

        ApiTestClient.ApiResponse memberList =
                client.get("/api/v1/departments", fixture.member().sessionCookie(), Map.of());
        assertThat(memberList.status()).as(memberList.body()).isEqualTo(200);
        JsonNode memberPage = client.json(memberList);
        assertThat(memberPage.path("total").asLong()).isEqualTo(1L);
        assertThat(memberPage.path("items").get(0).path("id").asText()).isEqualTo(sales.toString());

        ApiTestClient.ApiResponse ownMembers = client.get(
                "/api/v1/departments/" + sales + "/members", fixture.member().sessionCookie(), Map.of());
        assertThat(ownMembers.status()).as(ownMembers.body()).isEqualTo(200);
        JsonNode memberItems = client.json(ownMembers).path("items");
        assertThat(memberItems).hasSize(1);
        assertThat(memberItems.get(0).path("username").asText()).isEqualTo(fixture.memberUsername());
        assertThat(memberItems.get(0).path("displayName").asText()).isNotBlank();
        assertThat(memberItems.get(0).has("email")).isFalse();
        assertThat(ownMembers.body()).doesNotContain("@");

        ApiTestClient.ApiResponse otherMembers = client.get(
                "/api/v1/departments/" + marketing + "/members",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(otherMembers.status()).as(otherMembers.body()).isEqualTo(404);
        assertThat(client.json(otherMembers).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse adminOtherMembers = client.get(
                "/api/v1/departments/" + marketing + "/members",
                fixture.tenantAdmin().sessionCookie(),
                Map.of());
        assertThat(adminOtherMembers.status()).as(adminOtherMembers.body()).isEqualTo(200);

        ApiTestClient.ApiResponse tenantAdminWrite = client.post(
                departmentsPath(fixture.tenantId()),
                client.jsonBody("name", "伪部门"),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(tenantAdminWrite.status()).as(tenantAdminWrite.body()).isEqualTo(403);
        assertThat(client.json(tenantAdminWrite).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        ApiTestClient.ApiResponse memberWrite = client.post(
                departmentsPath(fixture.tenantId()),
                client.jsonBody("name", "伪部门"),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(memberWrite.status()).as(memberWrite.body()).isEqualTo(403);
    }

    /** 目标租户不存在/已暂停、部门跨租户都使用 ADR 规定的 404/409。 */
    @Test
    @DisplayName("IT-DEPT-001 目标租户 404 与停用 409、部门跨租户 404")
    void platformDepartmentEndpoints_enforceTargetTenantSemantics() {
        TenantFixture target = createFixture("tga");
        TenantFixture other = createFixture("tgb");
        UUID otherDepartment = createDepartmentId(other.platform(), other.tenantId(), "其他租户部门");

        ApiTestClient.ApiResponse unknownTenant = client.get(
                "/api/v1/admin/tenants/no-such-tenant/departments",
                target.platform().sessionCookie(),
                Map.of());
        assertThat(unknownTenant.status()).as(unknownTenant.body()).isEqualTo(404);
        assertThat(client.json(unknownTenant).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse foreignDepartment = client.patch(
                departmentsPath(target.tenantId()) + "/" + otherDepartment,
                client.jsonBody("name", "改名尝试"),
                target.platform().sessionCookie(),
                target.platform().csrfToken());
        assertThat(foreignDepartment.status()).as(foreignDepartment.body()).isEqualTo(404);
        assertThat(client.json(foreignDepartment).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse foreignMember =
                removeMember(target.platform(), target.tenantId(), otherDepartment, other.memberId());
        assertThat(foreignMember.status()).as(foreignMember.body()).isEqualTo(404);

        ApiTestClient.ApiResponse suspended = client.patch(
                "/api/v1/admin/tenants/" + target.tenantId(),
                client.jsonBody("status", "SUSPENDED"),
                target.platform().sessionCookie(),
                target.platform().csrfToken());
        assertThat(suspended.status()).as(suspended.body()).isEqualTo(200);

        ApiTestClient.ApiResponse createOnSuspended = client.post(
                departmentsPath(target.tenantId()),
                client.jsonBody("name", "停用后部门"),
                target.platform().sessionCookie(),
                target.platform().csrfToken(),
                Map.of());
        assertThat(createOnSuspended.status()).as(createOnSuspended.body()).isEqualTo(409);
        assertThat(client.json(createOnSuspended).path("code").asText()).isEqualTo("RICCON-CONFLICT-409");

        ApiTestClient.ApiResponse listOnSuspended =
                client.get(departmentsPath(target.tenantId()), target.platform().sessionCookie(), Map.of());
        assertThat(listOnSuspended.status()).as(listOnSuspended.body()).isEqualTo(409);
    }

    /** OrganizationDirectory 只暴露租户内组织元数据，并随停用/禁用即时变化。 */
    @Test
    @DisplayName("IT-DEPT-001 OrganizationDirectory 显式租户作用域与启用语义")
    void organizationDirectory_exposesTenantScopedOrganizationMetadata() {
        TenantFixture fixture = createFixture("dept");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "销售部");
        CreatedMember active = createExtraMember(fixture.platform(), fixture.tenantId());
        CreatedMember inactive = createExtraMember(fixture.platform(), fixture.tenantId());
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.memberId())
                        .status())
                .isEqualTo(201);
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, active.id())
                        .status())
                .isEqualTo(201);
        assertThat(addMember(fixture.platform(), fixture.tenantId(), departmentId, inactive.id())
                        .status())
                .isEqualTo(201);
        ApiTestClient.ApiResponse disabled = client.patch(
                "/api/v1/users/" + inactive.id(),
                client.jsonBody("status", "DISABLED"),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);

        TenantId tenantId = TenantId.of(fixture.tenantId());
        Optional<DepartmentSnapshot> department = TenantContext.callWithTenant(
                tenantId, () -> organizationDirectory.findDepartment(tenantId, departmentId));
        assertThat(department).isPresent();
        assertThat(department.orElseThrow().name()).isEqualTo("销售部");
        assertThat(department.orElseThrow().status().name()).isEqualTo("ACTIVE");
        assertThat(department.orElseThrow().managerUserId()).isNull();

        Optional<DepartmentSnapshot> ownDepartment = TenantContext.callWithTenant(
                tenantId, () -> organizationDirectory.findDepartmentOfUser(tenantId, fixture.memberId()));
        assertThat(ownDepartment).isPresent();
        assertThat(ownDepartment.orElseThrow().id()).isEqualTo(departmentId);
        assertThat(TenantContext.callWithTenant(
                        tenantId, () -> organizationDirectory.findDepartmentOfUser(tenantId, inactive.id())))
                .isEmpty();
        assertThat(TenantContext.callWithTenant(
                        tenantId,
                        () -> organizationDirectory.isActiveMember(tenantId, departmentId, fixture.memberId())))
                .isTrue();
        assertThat(TenantContext.callWithTenant(
                        tenantId, () -> organizationDirectory.isActiveMember(tenantId, departmentId, inactive.id())))
                .isFalse();
        assertThat(TenantContext.callWithTenant(
                        tenantId,
                        () -> organizationDirectory.isActiveMember(tenantId, departmentId, UUID.randomUUID())))
                .isFalse();

        List<DepartmentMemberSnapshot> members = TenantContext.callWithTenant(
                tenantId, () -> organizationDirectory.listActiveMembers(tenantId, departmentId));
        assertThat(members)
                .extracting(DepartmentMemberSnapshot::userId)
                .containsExactlyInAnyOrder(fixture.memberId(), active.id());
        assertThat(members).allSatisfy(member -> {
            assertThat(member.username()).isNotBlank();
            assertThat(member.displayName()).isNotBlank();
        });

        // 移出部门后，组织目录立即不再把该账号视为启用成员。
        ApiTestClient.ApiResponse removedMember =
                removeMember(fixture.platform(), fixture.tenantId(), departmentId, active.id());
        assertThat(removedMember.status()).as(removedMember.body()).isEqualTo(204);
        assertThat(TenantContext.callWithTenant(
                        tenantId, () -> organizationDirectory.isActiveMember(tenantId, departmentId, active.id())))
                .isFalse();
        assertThat(TenantContext.callWithTenant(
                        tenantId, () -> organizationDirectory.findDepartmentOfUser(tenantId, active.id())))
                .isEmpty();

        TenantFixture other = createFixture("orgb");
        TenantId otherTenant = TenantId.of(other.tenantId());
        assertThat(TenantContext.callWithTenant(
                        otherTenant, () -> organizationDirectory.findDepartment(otherTenant, departmentId)))
                .isEmpty();

        ApiTestClient.ApiResponse disabledDepartment = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/disable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(disabledDepartment.status()).as(disabledDepartment.body()).isEqualTo(200);
        assertThat(TenantContext.callWithTenant(
                                tenantId, () -> organizationDirectory.findDepartment(tenantId, departmentId))
                        .orElseThrow()
                        .status()
                        .name())
                .isEqualTo("DISABLED");
        assertThat(TenantContext.callWithTenant(
                        tenantId,
                        () -> organizationDirectory.isActiveMember(tenantId, departmentId, fixture.memberId())))
                .isFalse();
    }
}
