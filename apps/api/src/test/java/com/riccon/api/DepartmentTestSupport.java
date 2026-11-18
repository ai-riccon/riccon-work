package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.organization.OrganizationDirectory;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 部门管理集成测试基座。
 *
 * <p>复用真实 MySQL（Testcontainers）与随机端口 Tomcat；每个 fixture 使用唯一租户代码隔离数据。提供部门、
 * 成员、负责人、CUSTOM 空间种子与审计查询辅助方法，供 {@link DepartmentAdminApiTest} 使用，避免测试里出现
 * 手写路径或重复的账号创建逻辑。
 */
abstract class DepartmentTestSupport extends ApiIntegrationTestSupport {

    /** B1a 定义、B2 实现的组织目录只读接口。 */
    @Autowired
    protected OrganizationDirectory organizationDirectory;

    /**
     * 创建带租户管理员与普通成员的隔离租户。
     *
     * @param prefix 租户代码前缀
     * @return 测试夹具
     */
    protected TenantFixture createFixture(String prefix) {
        LoginSession platform = loginPlatformAdmin();
        String tenantId = uniqueTenantCode(prefix);
        createTenant(platform, tenantId);
        String suffix = randomSuffix();
        String adminUsername = "adm" + suffix;
        String memberUsername = "mem" + suffix;
        JsonNode admin =
                createUser(platform, tenantId, adminUsername, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        JsonNode member = createUser(platform, tenantId, memberUsername, "member-password-12", List.of("MEMBER"));
        UUID platformUserId =
                UUID.fromString(platform.body().path("user").path("id").asText());
        return new TenantFixture(
                tenantId,
                platform,
                platformUserId,
                login(tenantId, adminUsername, "tenant-admin-password-12"),
                UUID.fromString(admin.path("id").asText()),
                login(tenantId, memberUsername, "member-password-12"),
                UUID.fromString(member.path("id").asText()),
                memberUsername);
    }

    /**
     * 在租户内创建额外普通成员，返回账号 ID 与登录名。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @return 新成员
     */
    protected CreatedMember createExtraMember(LoginSession platform, String tenantId) {
        String username = "mem" + randomSuffix();
        JsonNode user = createUser(platform, tenantId, username, "member-password-12", List.of("MEMBER"));
        return new CreatedMember(UUID.fromString(user.path("id").asText()), username);
    }

    /**
     * 组装平台部门管理路径。
     *
     * @param tenantId 目标租户
     * @return 路径前缀
     */
    protected String departmentsPath(String tenantId) {
        return "/api/v1/admin/tenants/" + tenantId + "/departments";
    }

    /**
     * 通过平台管理员创建部门并断言 201。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param name 部门名
     * @return 部门响应
     */
    protected JsonNode createDepartment(LoginSession platform, String tenantId, String name) {
        ApiTestClient.ApiResponse response = client.post(
                departmentsPath(tenantId),
                client.jsonBody("name", name),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 创建部门并返回部门 ID。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param name 部门名
     * @return 部门 ID
     */
    protected UUID createDepartmentId(LoginSession platform, String tenantId, String name) {
        return UUID.fromString(
                createDepartment(platform, tenantId, name).path("id").asText());
    }

    /**
     * 平台管理员把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 原始响应，便于断言状态码
     */
    protected ApiTestClient.ApiResponse addMember(
            LoginSession platform, String tenantId, UUID departmentId, UUID userId) {
        return client.post(
                departmentsPath(tenantId) + "/" + departmentId + "/members",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
    }

    /**
     * 平台管理员把账号移出部门。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     * @return 原始响应，便于断言状态码
     */
    protected ApiTestClient.ApiResponse removeMember(
            LoginSession platform, String tenantId, UUID departmentId, UUID userId) {
        return client.delete(
                departmentsPath(tenantId) + "/" + departmentId + "/members/" + userId,
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 平台管理员任命/移交负责人。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 目标负责人
     * @return 原始响应，便于断言状态码
     */
    protected ApiTestClient.ApiResponse transferManager(
            LoginSession platform, String tenantId, UUID departmentId, UUID userId) {
        return client.put(
                departmentsPath(tenantId) + "/" + departmentId + "/manager",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken());
    }

    /**
     * 读取账号当前主部门。
     *
     * @param tenantId 租户
     * @param userId 账号 ID
     * @return 部门 ID；未分配时为 {@code null}
     */
    protected UUID userDepartment(String tenantId, UUID userId) {
        List<byte[]> rows = jdbcClient
                .sql("SELECT department_id FROM app_user WHERE tenant_id = :tenantId AND id = :userId")
                .param("tenantId", tenantId)
                .param("userId", BinaryUuids.toBytes(userId))
                .query((rs, rowNum) -> rs.getBytes("department_id"))
                .list();
        assertThat(rows).as("user row must exist").hasSize(1);
        return BinaryUuids.fromBytes(rows.get(0));
    }

    /**
     * 直接种一个本部门 CUSTOM 空间；B2 只负责移出成员时的成员行清理，空间写入口归 B3。
     *
     * @param tenantId 租户
     * @param departmentId 部门 ID
     * @param ownerUserId 所有者账号 ID
     * @param name 空间名
     * @return 空间 ID
     */
    protected UUID insertCustomWorkspace(String tenantId, UUID departmentId, UUID ownerUserId, String name) {
        UUID workspaceId = UUID.randomUUID();
        jdbcClient
                .sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            status, created_by, department_id, visibility
                        ) VALUES (
                            :tenantId, :id, 'TEAM', :name, NULL, :ownerUserId, NULL,
                            'ACTIVE', :createdBy, :departmentId, 'CUSTOM'
                        )
                        """)
                .param("tenantId", tenantId)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(ownerUserId))
                .param("createdBy", BinaryUuids.toBytes(ownerUserId))
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .update();
        return workspaceId;
    }

    /**
     * 种一条 CUSTOM 空间显式成员行。
     *
     * @param tenantId 租户
     * @param workspaceId 空间 ID
     * @param userId 成员账号 ID
     * @param grantedBy 授权人账号 ID
     */
    protected void insertWorkspaceMember(String tenantId, UUID workspaceId, UUID userId, UUID grantedBy) {
        jdbcClient
                .sql("""
                        INSERT INTO workspace_member (tenant_id, workspace_id, user_id, granted_by)
                        VALUES (:tenantId, :workspaceId, :userId, :grantedBy)
                        """)
                .param("tenantId", tenantId)
                .param("workspaceId", BinaryUuids.toBytes(workspaceId))
                .param("userId", BinaryUuids.toBytes(userId))
                .param("grantedBy", BinaryUuids.toBytes(grantedBy))
                .update();
    }

    /**
     * 统计 CUSTOM 空间显式成员行数。
     *
     * @param tenantId 租户
     * @param workspaceId 空间 ID
     * @return 行数
     */
    protected long workspaceMemberCount(String tenantId, UUID workspaceId) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM workspace_member
                        WHERE tenant_id = :tenantId AND workspace_id = :workspaceId
                        """)
                .param("tenantId", tenantId)
                .param("workspaceId", BinaryUuids.toBytes(workspaceId))
                .query(Long.class)
                .single();
    }

    /**
     * 查询某部门作为审计目标的动作记录。
     *
     * @param action 动作名
     * @param departmentId 部门 ID
     * @return 审计行
     */
    protected List<AuditRow> departmentAuditRows(String action, UUID departmentId) {
        return auditRows(action).stream()
                .filter(row -> departmentId.toString().equals(row.targetId()))
                .toList();
    }

    /**
     * 生成 8 位随机后缀，保证同容器内账号名唯一。
     *
     * @return 后缀
     */
    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 租户夹具。
     *
     * @param tenantId 租户代码
     * @param platform 平台管理员会话
     * @param platformUserId 平台管理员账号 ID
     * @param tenantAdmin 租户管理员会话
     * @param tenantAdminId 租户管理员账号 ID
     * @param member 普通成员会话
     * @param memberId 普通成员账号 ID
     * @param memberUsername 普通成员登录名
     */
    protected record TenantFixture(
            String tenantId,
            LoginSession platform,
            UUID platformUserId,
            LoginSession tenantAdmin,
            UUID tenantAdminId,
            LoginSession member,
            UUID memberId,
            String memberUsername) {}

    /**
     * 额外成员。
     *
     * @param id 账号 ID
     * @param username 登录名
     */
    protected record CreatedMember(UUID id, String username) {}
}
