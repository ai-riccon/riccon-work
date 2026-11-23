package com.riccon.worker.agent.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agent.service.KnowledgeRetriever;
import com.riccon.worker.audit.domain.AuditEvent;
import com.riccon.worker.audit.repository.AuditRepository;
import com.riccon.worker.persistence.UuidBinary;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * M05 管理性 SEARCH 审计 fail-closed：审计写入失败必须使检索失败。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeSearchAuditFailClosedTest extends WorkerIntegrationTestBase {

    /** 审计仓储 spy；仅 admin_read 抛错。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 生产检索链。 */
    @Autowired
    private KnowledgeRetriever retriever;

    /** 管理员检索绕过 ACL 时审计失败 -> 检索抛错。 */
    @Test
    @DisplayName("SEARCH 审计失败使检索失败")
    void adminSearch_auditFailureFailsRetrieval() {
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));

        String tenant = uniqueTenant("acl-audit-fc");
        UUID admin = seedTenant(tenant);
        grantAclManage(tenant, admin, admin);
        UUID creator = createUser(tenant);
        UUID department = createDepartment(tenant, creator, "AUDITFC-DEPT");
        UUID documentId = createDocument(tenant, "审计失败检索", "DEPT", department, creator);
        insertChunk(tenant, documentId, "审计失败市场数据");
        markIndexed(tenant, documentId);

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        TenantId.of(tenant),
                        () -> retriever.retrieveKnowledge(TenantId.of(tenant), admin, UuidBinary.newV7(), "市场")))
                .isInstanceOf(DataAccessResourceFailureException.class);
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
     * 创建唯一用户名的启用账号。
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
     * 授予 knowledge.acl.manage。
     *
     * @param tenant 租户
     * @param userId 账号
     * @param grantedBy 授权人
     */
    private void grantAclManage(String tenant, UUID userId, UUID grantedBy) {
        UUID roleId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO role (tenant_id, id, code, name, is_system)
                        VALUES (:tenantId, :id, 'TENANT_ADMIN', '租户管理员', TRUE)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(roleId))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_code)
                        VALUES (:tenantId, :roleId, 'knowledge.acl.manage')
                        """)
                .param("tenantId", tenant)
                .param("roleId", UuidBinary.toBytes(roleId))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO user_role (tenant_id, user_id, role_id, granted_by)
                        VALUES (:tenantId, :userId, :roleId, :grantedBy)
                        """)
                .param("tenantId", tenant)
                .param("userId", UuidBinary.toBytes(userId))
                .param("roleId", UuidBinary.toBytes(roleId))
                .param("grantedBy", UuidBinary.toBytes(grantedBy))
                .update();
    }

    /**
     * 创建部门。
     *
     * @param tenant 租户
     * @param creator 创建人
     * @param name 部门名
     * @return 部门 ID
     */
    private UUID createDepartment(String tenant, UUID creator, String name) {
        UUID departmentId = UuidBinary.newV7();
        jdbcClient
                .sql("""
                        INSERT INTO department (tenant_id, id, name, manager_user_id, status, created_by)
                        VALUES (:tenantId, :id, :name, NULL, 'ACTIVE', :createdBy)
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(departmentId))
                .param("name", name)
                .param("createdBy", UuidBinary.toBytes(creator))
                .update();
        return departmentId;
    }

    /**
     * 创建文档。
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
                .param("departmentId", UuidBinary.toBytes(departmentId))
                .update();
        return documentId;
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
     * 标记已索引。
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
