package com.riccon.worker.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.persistence.UuidBinary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M05 检索 ACL：TENANT/DEPT/USERS、部门停用、创建人隐式可见与 fail-closed 兼容入口。
 *
 * <p>直接验证 {@link AgentContextJdbc} 的谓词；生产 chat/report 调用方需要把 run 用户传入新重载才会返回
 * 非 TENANT 文档（B3 写范围不含 retriever wiring）。
 */
class AgentContextJdbcAclTest extends WorkerIntegrationTestBase {

    /** 被测检索。 */
    @Autowired
    private AgentContextJdbc contextJdbc;

    /** TENANT 文档对所有启用账号可见。 */
    @Test
    @DisplayName("TENANT 文档对无部门账号可见")
    void search_tenantDocument_isVisibleToAll() {
        String tenant = uniqueTenant("acl-tenant");
        UUID creator = seedTenant(tenant);
        UUID reader = createUser(tenant);
        UUID documentId = createDocument(tenant, "公开", "TENANT", null, creator);
        insertChunk(tenant, documentId, "市场增长数据");
        markIndexed(tenant, documentId);

        assertThat(search(tenant, reader, false, "市场")).isNotEmpty();
    }

    /** DEPT 文档只对同启用部门成员可见；部门停用下一次查询失效。 */
    @Test
    @DisplayName("DEPT 文档按启用部门过滤")
    void search_deptDocument_onlyActiveDepartmentMembers() {
        String tenant = uniqueTenant("acl-dept");
        UUID creator = seedTenant(tenant);
        UUID inDept = createUser(tenant);
        UUID otherDept = createUser(tenant);
        UUID noDept = createUser(tenant);
        UUID deptA = createDepartment(tenant, creator, "A", "ACTIVE");
        UUID deptB = createDepartment(tenant, creator, "B", "ACTIVE");
        assignDepartment(tenant, inDept, deptA);
        assignDepartment(tenant, otherDept, deptB);
        UUID documentId = createDocument(tenant, "部门文档", "DEPT", deptA, creator);
        insertChunk(tenant, documentId, "部门市场数据");
        markIndexed(tenant, documentId);

        assertThat(search(tenant, inDept, false, "市场")).isNotEmpty();
        assertThat(search(tenant, otherDept, false, "市场")).isEmpty();
        assertThat(search(tenant, noDept, false, "市场")).isEmpty();

        disableDepartment(tenant, deptA);
        assertThat(search(tenant, inDept, false, "市场")).isEmpty();
    }

    /** USERS 文档只对显式名单与创建人可见。 */
    @Test
    @DisplayName("USERS 文档按名单与创建人过滤")
    void search_usersDocument_onlyAuthorizedAndCreator() {
        String tenant = uniqueTenant("acl-users");
        UUID creator = seedTenant(tenant);
        UUID authorized = createUser(tenant);
        UUID other = createUser(tenant);
        UUID documentId = createDocument(tenant, "名单文档", "USERS", null, creator);
        insertAcl(tenant, documentId, authorized);
        insertChunk(tenant, documentId, "名单市场数据");
        markIndexed(tenant, documentId);

        assertThat(search(tenant, creator, false, "市场")).isNotEmpty();
        assertThat(search(tenant, authorized, false, "市场")).isNotEmpty();
        assertThat(search(tenant, other, false, "市场")).isEmpty();
    }

    /** 兼容入口没有 actor，fail-closed 只返回 TENANT 文档。 */
    @Test
    @DisplayName("兼容入口只返回 TENANT 文档")
    void search_legacyOverload_onlyTenantDocuments() {
        String tenant = uniqueTenant("acl-legacy");
        UUID creator = seedTenant(tenant);
        UUID dept = createDepartment(tenant, creator, "L", "ACTIVE");
        UUID tenantDoc = createDocument(tenant, "公开", "TENANT", null, creator);
        UUID deptDoc = createDocument(tenant, "部门", "DEPT", dept, creator);
        UUID usersDoc = createDocument(tenant, "名单", "USERS", null, creator);
        insertChunk(tenant, tenantDoc, "市场公开");
        insertChunk(tenant, deptDoc, "市场部门");
        insertChunk(tenant, usersDoc, "市场名单");
        markIndexed(tenant, tenantDoc);
        markIndexed(tenant, deptDoc);
        markIndexed(tenant, usersDoc);

        List<RetrievedChunk> hits = TenantContext.callWithTenant(
                TenantId.of(tenant), () -> contextJdbc.search(TenantId.of(tenant), "市场", 100, 10));
        assertThat(hits).extracting(RetrievedChunk::knowledgeDocumentId).containsExactly(tenantDoc);
    }

    /** 管理性读取不追加 ACL 谓词。 */
    @Test
    @DisplayName("管理性读取返回全部文档")
    void search_unrestricted_returnsAll() {
        String tenant = uniqueTenant("acl-admin");
        UUID creator = seedTenant(tenant);
        UUID dept = createDepartment(tenant, creator, "ADM", "ACTIVE");
        UUID tenantDoc = createDocument(tenant, "公开", "TENANT", null, creator);
        UUID deptDoc = createDocument(tenant, "部门", "DEPT", dept, creator);
        UUID usersDoc = createDocument(tenant, "名单", "USERS", null, creator);
        insertChunk(tenant, tenantDoc, "市场公开");
        insertChunk(tenant, deptDoc, "市场部门");
        insertChunk(tenant, usersDoc, "市场名单");
        markIndexed(tenant, tenantDoc);
        markIndexed(tenant, deptDoc);
        markIndexed(tenant, usersDoc);

        List<RetrievedChunk> hits = search(tenant, creator, true, "市场");
        assertThat(hits)
                .extracting(RetrievedChunk::knowledgeDocumentId)
                .containsExactlyInAnyOrder(tenantDoc, deptDoc, usersDoc);
    }

    /**
     * 执行检索。
     *
     * @param tenant 租户
     * @param actor 用户
     * @param unrestricted 是否管理性读取
     * @param query 查询
     * @return 命中
     */
    private List<RetrievedChunk> search(String tenant, UUID actor, boolean unrestricted, String query) {
        return TenantContext.callWithTenant(
                TenantId.of(tenant),
                () -> contextJdbc.search(TenantId.of(tenant), actor, unrestricted, query, 100, 10));
    }

    /**
     * 创建租户与创建人。
     *
     * @param tenant 租户
     * @return 创建人
     */
    private UUID seedTenant(String tenant) {
        data.createTenant(tenant);
        return createUser(tenant);
    }

    /**
     * 创建唯一用户名的启用账号，避免同一租户内多个测试账号撞唯一键。
     *
     * @param tenant 租户
     * @return 账号 ID
     */
    private UUID createUser(String tenant) {
        UUID userId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(userId))
                .param("username", "user-" + userId)
                .param("displayName", "Test User")
                .update();
        return userId;
    }

    /**
     * 创建部门。
     *
     * @param tenant 租户
     * @param creator 创建人
     * @param name 部门名
     * @param status 状态
     * @return 部门 ID
     */
    private UUID createDepartment(String tenant, UUID creator, String name, String status) {
        UUID departmentId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO department (tenant_id, id, name, manager_user_id, status, created_by)
                        VALUES (:tenantId, :id, :name, NULL, :status, :createdBy)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(departmentId))
                .param("name", name)
                .param("status", status)
                .param("createdBy", UuidBinary.toBytes(creator))
                .update();
        return departmentId;
    }

    /**
     * 停用部门。
     *
     * @param tenant 租户
     * @param departmentId 部门 ID
     */
    private void disableDepartment(String tenant, UUID departmentId) {
        jdbcClient
                .sql("""
                        UPDATE department SET status = 'DISABLED', disabled_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(departmentId))
                .update();
    }

    /**
     * 设置账号主部门。
     *
     * @param tenant 租户
     * @param userId 账号
     * @param departmentId 部门
     */
    private void assignDepartment(String tenant, UUID userId, UUID departmentId) {
        jdbcClient
                .sql("UPDATE app_user SET department_id = :departmentId WHERE tenant_id = :tenantId AND id = :id")
                .param("departmentId", UuidBinary.toBytes(departmentId))
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(userId))
                .update();
    }

    /**
     * 创建知识库文档。
     *
     * @param tenant 租户
     * @param title 标题
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param creator 创建人
     * @return 文档 ID
     */
    private UUID createDocument(String tenant, String title, String visibility, UUID departmentId, UUID creator) {
        UUID documentId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, visibility, department_id, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :title, 'SEED', NULL, :checksum, 'text/plain', 4,
                            'PENDING', :createdBy, :visibility, :departmentId, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .param("title", title)
                .param("checksum", "0".repeat(64))
                .param("createdBy", UuidBinary.toBytes(creator))
                .param("visibility", visibility)
                .param("departmentId", departmentId == null ? null : UuidBinary.toBytes(departmentId))
                .update();
        return documentId;
    }

    /**
     * 写入显式 ACL。
     *
     * @param tenant 租户
     * @param documentId 文档
     * @param userId 用户
     */
    private void insertAcl(String tenant, UUID documentId, UUID userId) {
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document_acl (tenant_id, document_id, user_id, granted_by)
                        VALUES (:tenantId, :documentId, :userId, NULL)
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .param("userId", UuidBinary.toBytes(userId))
                .update();
    }

    /**
     * 写入分块。
     *
     * @param tenant 租户
     * @param documentId 文档
     * @param content 内容
     */
    private void insertChunk(String tenant, UUID documentId, String content) {
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_chunk
                            (tenant_id, id, document_id, ordinal, locator_type, page_no, content)
                        VALUES
                            (:tenantId, :id, :documentId, 1, 'PAGE', 1, :content)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(UuidBinary.newV7()))
                .param("documentId", UuidBinary.toBytes(documentId))
                .param("content", content)
                .update();
    }

    /**
     * 标记文档已索引。
     *
     * @param tenant 租户
     * @param documentId 文档
     */
    private void markIndexed(String tenant, UUID documentId) {
        jdbcClient
                .sql("UPDATE knowledge_document SET status = 'INDEXED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
    }
}
