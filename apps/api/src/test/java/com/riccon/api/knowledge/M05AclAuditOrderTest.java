package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 连续 ACL 变更审计顺序与 fail-closed（V1 F-008 补齐）。
 *
 * <p>验证 ADR-0023 §5.6 / M05-AC-08 的“连续修改 ACL，审计按时间顺序可查每次前后范围；审计写失败时保存
 * 失败且不出现部分生效”。单次审计 fail-closed 由 {@code KnowledgeAuditFailClosedTest} 覆盖；这里补
 * “第二次连续变更失败时保留第一次成功结果”的顺序语义。
 */
@Tag("security")
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class M05AclAuditOrderTest extends M05AclMatrixTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 审计仓库 spy；只让 ACL 变更审计按开关失败。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 连续三次 ACL 修改的审计按时间顺序记录每次前后范围。 */
    @Test
    @DisplayName("F-008 连续 ACL 修改审计按时间顺序记录前后范围")
    void consecutiveAclChanges_areAuditedInOrder() throws Exception {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        String title = "连续 ACL 文档-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "TENANT", null, null);
        String documentId = document.path("id").asText();

        assertThat(putAcl(
                                fixture.admin(),
                                documentId,
                                Map.of(
                                        "visibility",
                                        "USERS",
                                        "userIds",
                                        List.of(actorId(fixture.member()).toString())))
                        .status())
                .isEqualTo(200);
        assertThat(putAcl(fixture.admin(), documentId, Map.of("visibility", "DEPT", "departmentId", sales.toString()))
                        .status())
                .isEqualTo(200);
        assertThat(putAcl(fixture.admin(), documentId, Map.of("visibility", "TENANT"))
                        .status())
                .isEqualTo(200);

        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("visibility")).isEqualTo("TENANT");
        assertThat(row.get("acl_version")).isEqualTo(4);

        List<AuditRow> changed = documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), documentId);
        assertThat(changed).hasSize(3);
        assertThat(changed.stream()
                        .map(AuditRow::details)
                        .map(details -> {
                            try {
                                return JSON.readTree(details);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                        .map(details -> details.path("previousVisibility").asText() + "->"
                                + details.path("visibility").asText())
                        .toList())
                .containsExactly("TENANT->USERS", "USERS->DEPT", "DEPT->TENANT");
    }

    /** 第二次连续变更审计失败：保存失败并保留第一次结果，不出现部分生效。 */
    @Test
    @DisplayName("F-008 连续 ACL 第二次审计失败保留第一次结果")
    void secondConsecutiveAclChange_auditFailureKeepsFirstChange() {
        TenantFixture fixture = tenantFixture();
        String title = "连续 ACL 失败文档-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "TENANT", null, null);
        String documentId = document.path("id").asText();

        // 第一次成功：TENANT -> USERS。
        assertThat(putAcl(
                                fixture.admin(),
                                documentId,
                                Map.of(
                                        "visibility",
                                        "USERS",
                                        "userIds",
                                        List.of(actorId(fixture.member()).toString())))
                        .status())
                .isEqualTo(200);
        Map<String, Object> firstRow = documentRow(fixture.tenant(), documentId);
        assertThat(firstRow.get("visibility")).isEqualTo("USERS");
        assertThat(firstRow.get("acl_version")).isEqualTo(2);

        // 第二次审计失败：USERS -> TENANT 整体失败，保留 USERS/version 2。
        AtomicBoolean failAclAudit = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (failAclAudit.get() && AuditActions.KNOWLEDGE_ACL_CHANGED.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));

        var failed = putAcl(fixture.admin(), documentId, Map.of("visibility", "TENANT"));
        assertThat(failed.status()).isEqualTo(500);
        assertThat(client.json(failed).path("code").asText()).isEqualTo("RICCON-COMMON-500");

        Map<String, Object> secondRow = documentRow(fixture.tenant(), documentId);
        assertThat(secondRow.get("visibility")).isEqualTo("USERS");
        assertThat(secondRow.get("acl_version")).isEqualTo(2);
        assertThat(aclRowCount(fixture.tenant(), documentId)).isEqualTo(1);
        assertThat(documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), documentId))
                .hasSize(1);

        // 恢复审计后第三次成功：USERS -> TENANT；审计序列仍只有两条成功记录且顺序正确。
        failAclAudit.set(false);
        assertThat(putAcl(fixture.admin(), documentId, Map.of("visibility", "TENANT"))
                        .status())
                .isEqualTo(200);
        List<AuditRow> changed = documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), documentId);
        assertThat(changed).hasSize(2);
        assertThat(documentRow(fixture.tenant(), documentId).get("acl_version")).isEqualTo(3);
    }
}
