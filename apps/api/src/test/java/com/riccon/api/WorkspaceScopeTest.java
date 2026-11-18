package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * M03 空间数据范围集成测试。
 *
 * <p>使用真实 MySQL 8.4（Testcontainers）与真实迁移，覆盖：
 *
 * <ul>
 *   <li>{@code DEPT}/{@code CUSTOM}/{@code PRIVATE}/{@code TENANT} 的可见性；
 *   <li>V9 收紧后 {@code visibility = NULL} 的旧写法被数据库拒绝；
 *   <li>列表、详情与计数使用同一谓词；
 *   <li>租户管理员、部门负责人、普通成员的 403/404 边界与跨部门隔离；
 *   <li>CUSTOM 成员加入/移出的 409/204 与部门归属约束；
 *   <li>部门停用、成员移出、负责人移交在下一次请求生效；
 *   <li>空间创建、可见性变更与成员变更审计。
 * </ul>
 *
 * <p>测试不 mock 持久化，也不跳过任何用例；每个 fixture 使用唯一租户代码隔离。
 */
class WorkspaceScopeTest extends DepartmentTestSupport {

    /** 测试账号统一密码。 */
    private static final String MEMBER_PASSWORD = "member-password-12";

    /** 部门空间只对本部门当前启用成员可见。 */
    @Test
    @DisplayName("DEPT 空间仅本部门启用成员可见")
    void deptWorkspace_visibleOnlyToDepartmentMembers() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "部门空间", "DEPT")
                .path("id")
                .asText();

        assertThat(get(fixture.admin(), workspaceId).status()).isEqualTo(200);
        assertThat(get(fixture.manager(), workspaceId).status()).isEqualTo(200);
        assertThat(get(fixture.sibling(), workspaceId).status()).isEqualTo(200);
        assertThat(listIds(list(fixture.sibling()))).contains(workspaceId);

        assertThatNotFound(get(fixture.otherDepartment(), workspaceId));
        assertThatNotFound(get(fixture.unassigned(), workspaceId));
        assertThat(listIds(list(fixture.otherDepartment()))).doesNotContain(workspaceId);
        assertThat(listIds(list(fixture.unassigned()))).doesNotContain(workspaceId);
    }

    /** 列表 total 与同一谓词返回的行数一致，不出现“列表过滤一套、详情另一套”。 */
    @Test
    @DisplayName("列表与计数使用同一可见性谓词")
    void listAndCount_useSamePredicate() {
        ScopeFixture fixture = scopeFixture();
        createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "空间一", "DEPT");
        createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "空间二", "CUSTOM");
        createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "空间三", "DEPT");

        JsonNode list = list(fixture.sibling());
        assertThat(list.path("total").asLong()).isEqualTo(list.path("items").size());
        Set<String> listed = new HashSet<>(listIds(list(fixture.sibling())));

        JsonNode teamOnly = list(fixture.sibling(), "TEAM");
        assertThat(teamOnly.path("total").asLong())
                .isEqualTo(teamOnly.path("items").size());
        // CUSTOM 未显式加入，部门成员只能看到两个 DEPT 空间。
        assertThat(teamOnly.path("total").asLong()).isEqualTo(2);
        assertThat(listed).hasSize(3);
    }

    /** CUSTOM 空间需要显式成员行；负责人可管理但内容仍按可见性判定。 */
    @Test
    @DisplayName("CUSTOM 空间要求显式成员，负责人可管理")
    void customWorkspace_requiresExplicitMember() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "自定义空间", "CUSTOM")
                .path("id")
                .asText();

        assertThatNotFound(get(fixture.sibling(), workspaceId));
        assertThat(listIds(list(fixture.sibling()))).doesNotContain(workspaceId);

        // 负责人是本部门成员但不是显式成员：内容不可读，管理入口可用。
        assertThatNotFound(get(fixture.manager(), workspaceId));
        assertThat(patch(fixture.manager(), workspaceId, client.jsonBody("name", "重命名后的空间"))
                        .status())
                .isEqualTo(200);
        assertThat(memberPage(fixture.manager(), workspaceId).path("total").asLong())
                .isZero();

        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);
        assertThat(get(fixture.sibling(), workspaceId).status()).isEqualTo(200);
        assertThat(listIds(list(fixture.sibling()))).contains(workspaceId);
    }

    /** CUSTOM 成员必须属于本部门启用成员；重复 409，移除不存在 204。 */
    @Test
    @DisplayName("CUSTOM 成员约束：跨部门 404、重复 409、移除不存在 204")
    void customMember_addRemoveEnforcesRules() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "成员空间", "CUSTOM")
                .path("id")
                .asText();

        assertThatNotFound(addWorkspaceMember(fixture.manager(), workspaceId, fixture.otherDepartmentId()));
        assertThatNotFound(addWorkspaceMember(fixture.manager(), workspaceId, fixture.unassignedId()));

        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);
        ApiTestClient.ApiResponse duplicate = addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(client.json(duplicate).path("code").asText()).isEqualTo("RICCON-DEPT-409");

        assertThat(removeWorkspaceMember(fixture.manager(), workspaceId, fixture.unassignedId())
                        .status())
                .isEqualTo(204);
        assertThat(removeWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(204);
        assertThat(workspaceMemberCount(fixture.tenant(), UUID.fromString(workspaceId)))
                .isZero();
        assertThatNotFound(get(fixture.sibling(), workspaceId));

        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);
    }

    /** 成员端点只服务 CUSTOM；DEPT/TENANT 与个人空间按契约拒绝。 */
    @Test
    @DisplayName("非 CUSTOM 与个人空间拒绝成员管理")
    void memberEndpoints_rejectNonCustomAndPersonal() {
        ScopeFixture fixture = scopeFixture();
        String deptWorkspace = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "部门空间", "DEPT")
                .path("id")
                .asText();

        JsonNode deptMembers = memberPage(fixture.admin(), deptWorkspace);
        assertThat(deptMembers.path("total").asLong()).isZero();
        assertThat(deptMembers.path("items")).isEmpty();
        assertThat(addWorkspaceMember(fixture.admin(), deptWorkspace, fixture.siblingId())
                        .status())
                .isEqualTo(400);
        assertThat(removeWorkspaceMember(fixture.admin(), deptWorkspace, fixture.siblingId())
                        .status())
                .isEqualTo(400);

        String personalId = personalWorkspaceId(fixture.admin());
        assertThat(addWorkspaceMember(fixture.admin(), personalId, fixture.siblingId())
                        .status())
                .isEqualTo(400);
        assertThat(get(fixture.sibling(), personalId).status()).isEqualTo(404);
        assertThatNotFound(rawMemberPage(fixture.sibling(), personalId));
    }

    /** 历史 TENANT 空间保持 M02 全租户可见面，但不可转换为部门空间。 */
    @Test
    @DisplayName("历史 TENANT 空间全租户可见且不可转换")
    void legacyTenantWorkspace_isTenantWideAndImmutable() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = seedTeamWorkspace(fixture.tenant(), "TENANT", fixture.adminId(), "历史团队空间");

        for (LoginSession session :
                List.of(fixture.admin(), fixture.manager(), fixture.sibling(), fixture.otherDepartment())) {
            ApiTestClient.ApiResponse response = get(session, workspaceId);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode body = client.json(response);
            assertThat(body.path("visibility").asText()).isEqualTo("TENANT");
            assertThat(body.path("departmentId").isNull()).isTrue();
        }
        assertThat(listIds(list(fixture.otherDepartment()))).contains(workspaceId);

        ApiTestClient.ApiResponse convert = patch(fixture.admin(), workspaceId, client.jsonBody("visibility", "DEPT"));
        assertThat(convert.status()).isEqualTo(400);
        assertThat(client.json(convert).path("code").asText()).isEqualTo("RICCON-REQUEST-400");
    }

    /**
     * V9 收紧后 visibility 已是 NOT NULL：M02 旧代码写 NULL 的路径必须在数据库层失败，
     * 不再依赖谓词对 NULL 做降级。该用例由 B3 的 expand 期负向断言改为 contract 期负向断言。
     */
    @Test
    @DisplayName("V9 收紧后 visibility NULL 被数据库拒绝")
    void nullVisibilityWorkspace_isRejectedAfterTightening() {
        ScopeFixture fixture = scopeFixture();
        assertThatThrownBy(() -> seedTeamWorkspaceWithNullVisibility(fixture.tenant(), fixture.adminId(), "NULL 团队空间"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("visibility");
        assertThatThrownBy(() ->
                        seedPersonalWorkspaceWithNullVisibility(fixture.tenant(), fixture.siblingId(), "NULL 个人空间"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("visibility");
    }

    /** 部门停用后成员立即失去空间访问，负责人管理入口也停止。 */
    @Test
    @DisplayName("部门停用立即收紧空间访问")
    void disabledDepartment_hidesWorkspacesUntilEnabled() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "部门空间", "DEPT")
                .path("id")
                .asText();

        assertThat(setDepartmentStatus(fixture.platform(), fixture.tenant(), fixture.departmentA(), "disable")
                        .status())
                .isEqualTo(200);
        assertThatNotFound(get(fixture.sibling(), workspaceId));
        assertThat(listIds(list(fixture.sibling()))).doesNotContain(workspaceId);
        assertThatNotFound(patch(fixture.manager(), workspaceId, client.jsonBody("name", "停用后改名")));
        assertThat(listDepartmentWorkspaces(fixture.manager(), fixture.departmentA())
                        .status())
                .isEqualTo(400);

        assertThat(setDepartmentStatus(fixture.platform(), fixture.tenant(), fixture.departmentA(), "enable")
                        .status())
                .isEqualTo(200);
        assertThat(get(fixture.sibling(), workspaceId).status()).isEqualTo(200);
    }

    /** 成员移出部门后 CUSTOM 显式行不授予访问，且 B2 已清理残留行。 */
    @Test
    @DisplayName("移出部门后空间访问立即失效")
    void removedDepartmentMember_losesAccessAndCustomRows() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "成员空间", "CUSTOM")
                .path("id")
                .asText();
        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);
        assertThat(get(fixture.sibling(), workspaceId).status()).isEqualTo(200);

        assertThat(removeMember(fixture.platform(), fixture.tenant(), fixture.departmentA(), fixture.siblingId())
                        .status())
                .isEqualTo(204);
        assertThatNotFound(get(fixture.sibling(), workspaceId));
        assertThat(workspaceMemberCount(fixture.tenant(), UUID.fromString(workspaceId)))
                .isZero();
    }

    /** 负责人移交在下一请求生效：旧负责人 403，新负责人可管理。 */
    @Test
    @DisplayName("负责人移交后管理能力下一次请求生效")
    void managerChange_takesEffectOnNextRequest() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "部门空间", "DEPT")
                .path("id")
                .asText();
        assertThat(patch(fixture.manager(), workspaceId, client.jsonBody("name", "负责人改名"))
                        .status())
                .isEqualTo(200);

        assertThat(transferManager(fixture.platform(), fixture.tenant(), fixture.departmentA(), fixture.siblingId())
                        .status())
                .isEqualTo(200);
        assertThat(patch(fixture.manager(), workspaceId, client.jsonBody("name", "旧负责人改名"))
                        .status())
                .isEqualTo(403);
        assertThat(patch(fixture.sibling(), workspaceId, client.jsonBody("name", "新负责人改名"))
                        .status())
                .isEqualTo(200);
    }

    /** 可见但无管理能力 403；跨部门不可见 404；租户管理员可管理任意团队空间。 */
    @Test
    @DisplayName("管理能力 403/404 与租户管理员例外")
    void managementAuthorization_distinguishesForbiddenAndNotFound() {
        ScopeFixture fixture = scopeFixture();
        String departmentAWorkspace = createDepartmentWorkspace(
                        fixture.admin(), fixture.departmentA(), "A 部门空间", "DEPT")
                .path("id")
                .asText();
        String departmentBWorkspace = createWorkspace(fixture.admin(), fixture.departmentB(), "B 部门空间", "DEPT")
                .path("id")
                .asText();

        assertThat(patch(fixture.sibling(), departmentAWorkspace, client.jsonBody("name", "普通成员改名"))
                        .status())
                .isEqualTo(403);
        assertThatNotFound(patch(fixture.otherDepartment(), departmentAWorkspace, client.jsonBody("name", "他部门改名")));
        assertThatNotFound(patch(fixture.unassigned(), departmentAWorkspace, client.jsonBody("name", "无部门改名")));

        assertThat(listDepartmentWorkspaces(fixture.sibling(), fixture.departmentA())
                        .status())
                .isEqualTo(403);
        assertThatNotFound(listDepartmentWorkspaces(fixture.otherDepartment(), fixture.departmentA()));
        assertThatNotFound(listDepartmentWorkspaces(fixture.manager(), fixture.departmentB()));

        assertThat(listDepartmentWorkspaces(fixture.admin(), fixture.departmentB())
                        .status())
                .isEqualTo(200);
        assertThat(patch(fixture.admin(), departmentBWorkspace, client.jsonBody("name", "管理员改名"))
                        .status())
                .isEqualTo(200);
    }

    /** 部门路径创建空间的授权矩阵；POST /workspaces 仍要求 tenant admin。 */
    @Test
    @DisplayName("部门空间创建授权与校验")
    void createWorkspace_authorizationMatrix() {
        ScopeFixture fixture = scopeFixture();

        assertThat(createDepartmentWorkspace(fixture.manager(), fixture.departmentA(), "负责人空间", "CUSTOM")
                        .path("departmentId")
                        .asText())
                .isEqualTo(fixture.departmentA().toString());
        assertThatNotFound(createDepartmentWorkspaceRaw(fixture.manager(), fixture.departmentB(), "负责人跨界空间", "DEPT"));
        assertThat(createDepartmentWorkspaceRaw(fixture.sibling(), fixture.departmentA(), "普通成员空间", "DEPT")
                        .status())
                .isEqualTo(403);
        assertThatNotFound(
                createDepartmentWorkspaceRaw(fixture.otherDepartment(), fixture.departmentA(), "他部门空间", "DEPT"));

        JsonNode created = createWorkspace(fixture.admin(), fixture.departmentA(), "管理员创建", "CUSTOM");
        assertThat(created.path("visibility").asText()).isEqualTo("CUSTOM");
        assertThat(created.path("departmentId").asText())
                .isEqualTo(fixture.departmentA().toString());
    }

    /** DEPT/CUSTOM 互转清空旧成员；TENANT 不可转换。 */
    @Test
    @DisplayName("可见性变更清空成员并拒绝 TENANT")
    void visibilityChange_clearsMembersAndRejectsTenant() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.admin(), fixture.departmentA(), "可变空间", "CUSTOM")
                .path("id")
                .asText();
        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse toDept = patch(fixture.manager(), workspaceId, client.jsonBody("visibility", "DEPT"));
        assertThat(toDept.status()).as(toDept.body()).isEqualTo(200);
        assertThat(client.json(toDept).path("visibility").asText()).isEqualTo("DEPT");
        assertThat(workspaceMemberCount(fixture.tenant(), UUID.fromString(workspaceId)))
                .isZero();

        ApiTestClient.ApiResponse toCustom =
                patch(fixture.manager(), workspaceId, client.jsonBody("visibility", "CUSTOM"));
        assertThat(toCustom.status()).isEqualTo(200);
        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);

        ApiTestClient.ApiResponse toTenant =
                patch(fixture.manager(), workspaceId, client.jsonBody("visibility", "TENANT"));
        assertThat(toTenant.status()).isEqualTo(400);

        String legacy = seedTeamWorkspace(fixture.tenant(), "TENANT", fixture.adminId(), "历史空间");
        assertThat(patch(fixture.admin(), legacy, client.jsonBody("visibility", "DEPT"))
                        .status())
                .isEqualTo(400);
    }

    /** 列表不泄露其他部门的名称或空间。 */
    @Test
    @DisplayName("列表不泄露其他部门名称与数量")
    void list_doesNotLeakOtherDepartmentMetadata() {
        ScopeFixture fixture = scopeFixture();
        String departmentBName = "市场秘密部门";
        UUID departmentB = createDepartmentId(fixture.platform(), fixture.tenant(), departmentBName);
        addMember(fixture.platform(), fixture.tenant(), departmentB, fixture.otherDepartmentId());
        createWorkspace(fixture.admin(), departmentB, "B 秘密空间", "DEPT");

        JsonNode list = list(fixture.sibling());
        assertThat(list.toString()).doesNotContain(departmentBName);
        assertThat(list.toString()).doesNotContain("B 秘密空间");
        assertThat(list.path("total").asLong()).isEqualTo(list.path("items").size());
    }

    /** 空间创建、可见性变更与成员变更写审计，details 只含白名单字段。 */
    @Test
    @DisplayName("空间生命周期写审计")
    void workspaceLifecycle_writesAudit() {
        ScopeFixture fixture = scopeFixture();
        String workspaceId = createDepartmentWorkspace(fixture.manager(), fixture.departmentA(), "审计空间", "CUSTOM")
                .path("id")
                .asText();
        assertThat(addWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(201);
        assertThat(removeWorkspaceMember(fixture.manager(), workspaceId, fixture.siblingId())
                        .status())
                .isEqualTo(204);
        assertThat(patch(fixture.manager(), workspaceId, client.jsonBody("visibility", "DEPT"))
                        .status())
                .isEqualTo(200);

        List<AuditRow> created = auditRows("workspace.created").stream()
                .filter(row -> workspaceId.equals(row.targetId()))
                .toList();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).tenantId()).isEqualTo(fixture.tenant());
        assertThat(created.get(0).details())
                .contains(workspaceId, fixture.departmentA().toString(), "CUSTOM");

        assertThat(auditRows("workspace.member.added").stream()
                        .filter(row -> workspaceId.equals(row.targetId()))
                        .toList())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.details()).contains(fixture.siblingId().toString());
                    assertThat(row.details()).doesNotContain("@");
                });
        assertThat(auditRows("workspace.member.removed").stream()
                        .filter(row -> workspaceId.equals(row.targetId()))
                        .toList())
                .hasSize(1);
        assertThat(auditRows("workspace.visibility.changed").stream()
                        .filter(row -> workspaceId.equals(row.targetId()))
                        .toList())
                .singleElement()
                .satisfies(row -> assertThat(row.details()).contains("DEPT", "CUSTOM"));
    }

    /** 个人空间不提供管理能力：所有者 403，其他人 404。 */
    @Test
    @DisplayName("个人空间拒绝管理操作")
    void personalWorkspace_hasNoManagementCapability() {
        ScopeFixture fixture = scopeFixture();
        String personalId = personalWorkspaceId(fixture.admin());

        assertThat(patch(fixture.admin(), personalId, client.jsonBody("name", "改名个人空间"))
                        .status())
                .isEqualTo(403);
        assertThatNotFound(patch(fixture.manager(), personalId, client.jsonBody("name", "他人改名")));
        assertThatNotFound(get(fixture.manager(), personalId));
    }

    /**
     * 创建本测试的隔离租户夹具：两个部门、负责人、同部门成员、其他部门成员与未分配部门账号。
     *
     * @return 夹具
     */
    private ScopeFixture scopeFixture() {
        TenantFixture base = createFixture("scope");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID departmentA = createDepartmentId(base.platform(), base.tenantId(), "研发部" + suffix);
        UUID departmentB = createDepartmentId(base.platform(), base.tenantId(), "市场部" + suffix);
        assertThat(addMember(base.platform(), base.tenantId(), departmentA, base.tenantAdminId())
                        .status())
                .isEqualTo(201);
        assertThat(addMember(base.platform(), base.tenantId(), departmentA, base.memberId())
                        .status())
                .isEqualTo(201);

        CreatedMember sibling = createExtraMember(base.platform(), base.tenantId());
        assertThat(addMember(base.platform(), base.tenantId(), departmentA, sibling.id())
                        .status())
                .isEqualTo(201);
        CreatedMember otherDepartment = createExtraMember(base.platform(), base.tenantId());
        assertThat(addMember(base.platform(), base.tenantId(), departmentB, otherDepartment.id())
                        .status())
                .isEqualTo(201);
        CreatedMember unassigned = createExtraMember(base.platform(), base.tenantId());

        assertThat(transferManager(base.platform(), base.tenantId(), departmentA, base.memberId())
                        .status())
                .isEqualTo(200);

        return new ScopeFixture(
                base.tenantId(),
                base.platform(),
                base.tenantAdmin(),
                base.tenantAdminId(),
                base.member(),
                base.memberId(),
                login(base.tenantId(), sibling.username(), MEMBER_PASSWORD),
                sibling.id(),
                login(base.tenantId(), otherDepartment.username(), MEMBER_PASSWORD),
                otherDepartment.id(),
                login(base.tenantId(), unassigned.username(), MEMBER_PASSWORD),
                unassigned.id(),
                departmentA,
                departmentB);
    }

    /**
     * 在部门下创建空间并断言 201。
     *
     * @param session 会话
     * @param departmentId 部门 ID
     * @param name 名称
     * @param visibility 可见性
     * @return 响应 JSON
     */
    private JsonNode createDepartmentWorkspace(
            LoginSession session, UUID departmentId, String name, String visibility) {
        ApiTestClient.ApiResponse response = createDepartmentWorkspaceRaw(session, departmentId, name, visibility);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 调用部门路径创建空间。
     *
     * @param session 会话
     * @param departmentId 部门 ID
     * @param name 名称
     * @param visibility 可见性
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse createDepartmentWorkspaceRaw(
            LoginSession session, UUID departmentId, String name, String visibility) {
        return client.post(
                "/api/v1/departments/" + departmentId + "/workspaces",
                client.jsonBody("name", name, "visibility", visibility),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 通过 POST /workspaces 创建空间并断言 201。
     *
     * @param session 会话
     * @param departmentId 部门 ID
     * @param name 名称
     * @param visibility 可见性
     * @return 响应 JSON
     */
    private JsonNode createWorkspace(LoginSession session, UUID departmentId, String name, String visibility) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", name, "departmentId", departmentId.toString(), "visibility", visibility),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 读取空间。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse get(LoginSession session, String workspaceId) {
        return client.get("/api/v1/workspaces/" + workspaceId, session.sessionCookie(), Map.of());
    }

    /**
     * 修改空间。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @param body JSON 请求体
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse patch(LoginSession session, String workspaceId, String body) {
        return client.patch("/api/v1/workspaces/" + workspaceId, body, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 加入 CUSTOM 空间成员。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @param userId 账号 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse addWorkspaceMember(LoginSession session, String workspaceId, UUID userId) {
        return client.post(
                "/api/v1/workspaces/" + workspaceId + "/members",
                client.jsonBody("userId", userId.toString()),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }

    /**
     * 移出 CUSTOM 空间成员。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @param userId 账号 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse removeWorkspaceMember(LoginSession session, String workspaceId, UUID userId) {
        return client.delete(
                "/api/v1/workspaces/" + workspaceId + "/members/" + userId,
                session.sessionCookie(),
                session.csrfToken());
    }

    /**
     * 读取空间成员分页。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @return 响应 JSON
     */
    private JsonNode memberPage(LoginSession session, String workspaceId) {
        ApiTestClient.ApiResponse response = rawMemberPage(session, workspaceId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 读取空间成员分页的原始响应。
     *
     * @param session 会话
     * @param workspaceId 空间 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse rawMemberPage(LoginSession session, String workspaceId) {
        return client.get(
                "/api/v1/workspaces/" + workspaceId + "/members?page=1&size=100", session.sessionCookie(), Map.of());
    }

    /**
     * 读取部门空间管理列表。
     *
     * @param session 会话
     * @param departmentId 部门 ID
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse listDepartmentWorkspaces(LoginSession session, UUID departmentId) {
        return client.get(
                "/api/v1/departments/" + departmentId + "/workspaces?page=1&size=100",
                session.sessionCookie(),
                Map.of());
    }

    /**
     * 列出可访问空间。
     *
     * @param session 会话
     * @return 响应 JSON
     */
    private JsonNode list(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 按类型列出可访问空间。
     *
     * @param session 会话
     * @param kind 类型
     * @return 响应 JSON
     */
    private JsonNode list(LoginSession session, String kind) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=" + kind, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 提取列表中的空间 ID。
     *
     * @param list 分页 JSON
     * @return 空间 ID 列表
     */
    private static List<String> listIds(JsonNode list) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : list.path("items")) {
            ids.add(item.path("id").asText());
        }
        return List.copyOf(ids);
    }

    /**
     * 断言统一 404 错误码。
     *
     * @param response 原始响应
     */
    private void assertThatNotFound(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
    }

    /**
     * 读取个人空间 ID。
     *
     * @param session 会话
     * @return 空间 ID
     */
    private String personalWorkspaceId(LoginSession session) {
        JsonNode list = list(session, "PERSONAL");
        assertThat(list.path("items")).isNotEmpty();
        return list.path("items").get(0).path("id").asText();
    }

    /**
     * 停用/启用部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param action {@code disable} 或 {@code enable}
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse setDepartmentStatus(
            LoginSession platform, String tenant, UUID departmentId, String action) {
        return client.post(
                departmentsPath(tenant) + "/" + departmentId + "/" + action,
                null,
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 直接种一个历史 TENANT 团队空间。
     *
     * @param tenant 租户
     * @param visibility 可见性；TENANT 或 NULL
     * @param owner 所有者
     * @param name 名称
     * @return 空间 ID
     */
    private String seedTeamWorkspace(String tenant, String visibility, UUID owner, String name) {
        return insertWorkspace(tenant, "TEAM", visibility, null, owner, name);
    }

    /**
     * 直接种一个 visibility NULL 的历史团队空间。
     *
     * @param tenant 租户
     * @param owner 所有者
     * @param name 名称
     * @return 空间 ID
     */
    private String seedTeamWorkspaceWithNullVisibility(String tenant, UUID owner, String name) {
        return insertWorkspace(tenant, "TEAM", null, null, owner, name);
    }

    /**
     * 直接种一个 visibility NULL 的历史个人空间。
     *
     * @param tenant 租户
     * @param owner 所有者
     * @param name 名称
     * @return 空间 ID
     */
    private String seedPersonalWorkspaceWithNullVisibility(String tenant, UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            department_id, visibility, status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, 'PERSONAL', :name, NULL, :ownerUserId, :ownerUserId,
                            NULL, NULL, 'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(id))
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(owner))
                .param("createdBy", BinaryUuids.toBytes(owner))
                .update();
        return id.toString();
    }

    /**
     * 种一个部门为空的历史空间。
     *
     * @param tenant 租户
     * @param kind 类型
     * @param visibility 可见性；可为空
     * @param departmentId 部门；可为空
     * @param owner 所有者
     * @param name 名称
     * @return 空间 ID
     */
    private String insertWorkspace(
            String tenant, String kind, String visibility, UUID departmentId, UUID owner, String name) {
        return insertWorkspaceId(tenant, kind, visibility, departmentId, owner, name)
                .toString();
    }

    /**
     * 直接插入空间行并返回 ID。
     *
     * @param tenant 租户
     * @param kind 类型
     * @param visibility 可见性；可为空
     * @param departmentId 部门；可为空
     * @param owner 所有者
     * @param name 名称
     * @return 空间 ID
     */
    private UUID insertWorkspaceId(
            String tenant, String kind, String visibility, UUID departmentId, UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            department_id, visibility, status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :kind, :name, NULL, :ownerUserId, NULL,
                            :departmentId, :visibility, 'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(id))
                .param("kind", kind)
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(owner))
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .param("visibility", visibility)
                .param("createdBy", BinaryUuids.toBytes(owner))
                .update();
        return id;
    }

    /**
     * 测试夹具：覆盖两个部门、负责人、同部门成员、其他部门成员与未分配部门账号。
     *
     * @param tenant 租户代码
     * @param platform 平台管理员会话
     * @param admin 租户管理员会话
     * @param adminId 租户管理员账号 ID
     * @param manager 部门 A 负责人会话
     * @param managerId 部门 A 负责人账号 ID
     * @param sibling 部门 A 普通成员会话
     * @param siblingId 部门 A 普通成员账号 ID
     * @param otherDepartment 部门 B 成员会话
     * @param otherDepartmentId 部门 B 成员账号 ID
     * @param unassigned 未分配部门账号会话
     * @param unassignedId 未分配部门账号 ID
     * @param departmentA 部门 A
     * @param departmentB 部门 B
     */
    private record ScopeFixture(
            String tenant,
            LoginSession platform,
            LoginSession admin,
            UUID adminId,
            LoginSession manager,
            UUID managerId,
            LoginSession sibling,
            UUID siblingId,
            LoginSession otherDepartment,
            UUID otherDepartmentId,
            LoginSession unassigned,
            UUID unassignedId,
            UUID departmentA,
            UUID departmentB) {}
}
