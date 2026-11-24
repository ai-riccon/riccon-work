package com.riccon.api.workflow.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.version.WorkflowVersionTestSupport;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 发布/停用/移交的审计 fail-closed 集成测试（AC-16，ADR-0007/ADR-0024 §8.2）。
 *
 * <p>只让目标动作的审计写入失败，其他审计保持无语义，避免影响登录与租户创建；断言 HTTP 500 且业务
 * 状态、版本与发布指针全部回滚，不产生无痕变更。真实 MySQL + V15 + 真实会话/CSRF/权限链。
 */
class WorkflowVersionAuditFailClosedTest extends WorkflowVersionTestSupport {

    /** 抛异常的审计仓储。 */
    @MockitoBean
    private AuditRepository auditRepository;

    /** 发布审计失败：不产生版本、不改发布指针。 */
    @Test
    @DisplayName("IT-WF-112 发布审计失败回滚版本与指针")
    void publish_failsWhenAuditWriteFails() {
        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_PUBLISHED.equals(event.action())));

        Fixture fixture = fixture("wfa");
        String workflowId = createDraft(fixture.owner(), "发布审计失败").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        ApiTestClient.ApiResponse response =
                publish(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-9001");
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(versionCount(fixture.tenant(), workflowId)).isZero();
        assertThat(publishedVersionId(fixture.tenant(), workflowId)).isNull();
        assertThat(publishedVersionNo(fixture.tenant(), workflowId)).isNull();
        assertThat(definitionStatus(fixture.tenant(), workflowId)).isEqualTo("ACTIVE");
    }

    /** 停用审计失败：状态保持 ACTIVE。 */
    @Test
    @DisplayName("IT-WF-112 停用审计失败回滚状态")
    void disable_failsWhenAuditWriteFails() {
        Fixture fixture = fixture("wfa");
        String workflowId = createDraft(fixture.owner(), "停用审计失败").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));
        publishOk(fixture.owner(), workflowId, "ONLY_SELF", null, "publish-key-9002");

        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_DISABLED.equals(event.action())));
        ApiTestClient.ApiResponse response = disable(fixture.owner(), workflowId);
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(definitionStatus(fixture.tenant(), workflowId)).isEqualTo("ACTIVE");
    }

    /** 移交审计失败：所有权保持原所有者。 */
    @Test
    @DisplayName("IT-WF-112 移交审计失败回滚所有权")
    void transfer_failsWhenAuditWriteFails() {
        Fixture fixture = fixture("wfa");
        String workflowId = createDraft(fixture.owner(), "移交审计失败").path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, defaultGraph(fixture));

        doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
                .when(auditRepository)
                .append(argThat((AuditEvent event) -> AuditActions.WORKFLOW_OWNER_TRANSFERRED.equals(event.action())));
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workflows/" + workflowId + "/transfer",
                client.jsonBody("ownerUserId", fixture.otherEditorId().toString()),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
        assertThat(ownerUserIdInDatabase(fixture.tenant(), workflowId)).isEqualTo(fixture.ownerId());
    }
}
