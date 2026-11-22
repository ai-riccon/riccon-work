package com.riccon.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.chat.domain.ChatSubmission;
import com.riccon.api.chat.dto.SendMessageRequest;
import com.riccon.api.chat.service.ChatSubmissionService;
import com.riccon.api.error.ConflictException;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.quota.QuotaApiTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * J1 对话入口的配额接线。
 *
 * <p>直接调用 {@link ChatSubmissionService#prepare} 验证成功准入与幂等冲突不会重复扣减；再通过 HTTP 验证
 * 超限在打开 Worker 流之前返回 JSON 429（不是 SSE），且不创建 run。
 */
class ChatQuotaAdmissionTest extends QuotaApiTestSupport {

    /** 提交服务。 */
    @Autowired
    private ChatSubmissionService chatSubmissionService;

    /** 每个用例前恢复执行模式。 */
    @BeforeEach
    void enableEnforcement() {
        setEnforcement(true);
    }

    /**
     * 对话准入一次、幂等冲突不扣减、超限返回 429。
     */
    @Test
    @DisplayName("IT-QUOTA-105 对话入口只准入一次且超限返回 429")
    void chatSubmission_admitsOnceAndRejectsOverLimit() {
        Fixture fixture = fixture();
        setTenantQuota(fixture.platform(), fixture.tenant(), 10, 10);
        setUserQuota(fixture.platform(), fixture.tenant(), userId(fixture.member()), 1, 10);
        String conversationId = createConversation(fixture.member(), fixture.memberWorkspace());
        RicconPrincipal principal = principal(fixture.member(), fixture.tenant());
        TenantId tenantId = TenantId.of(fixture.tenant());

        ChatSubmission submission = TenantContext.callWithTenant(
                tenantId,
                () -> chatSubmissionService.prepare(
                        principal,
                        UUID.fromString(conversationId),
                        new SendMessageRequest("配额对话主题一", "client-quota-0001", List.of()),
                        "trace-chat-quota-1",
                        "req-chat-quota-1"));
        assertThat(submission.runId()).isNotNull();
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);

        assertThatThrownBy(() -> TenantContext.callWithTenant(
                        tenantId,
                        () -> chatSubmissionService.prepare(
                                principal,
                                UUID.fromString(conversationId),
                                new SendMessageRequest("配额对话重复", "client-quota-0001", List.of()),
                                "trace-chat-quota-2",
                                "req-chat-quota-2")))
                .isInstanceOf(ConflictException.class);
        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);

        ApiTestClient.ApiResponse rejected = client.post(
                "/api/v1/conversations/" + conversationId + "/messages",
                client.jsonBody("content", "配额对话主题二", "clientMessageId", "client-quota-0002"),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of("Accept", "text/event-stream", "Idempotency-Key", "idem-quota-chat-2"));
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(429);
        assertThat(rejected.header("Content-Type")).contains("application/problem+json");
        JsonNode problem = client.json(rejected);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-QUOTA-429");
        assertThat(problem.path("scopeType").asText()).isEqualTo("USER");
        assertThat(problem.path("window").asText()).isEqualTo("DAILY");
        assertThat(problem.path("limit").asInt()).isEqualTo(1);
        assertThat(problem.path("used").asInt()).isEqualTo(1);
        assertThat(problem.path("resetsAt").asText()).endsWith("+08:00");

        assertThat(usedCount(fixture.tenant(), "USER", userId(fixture.member()), "DAILY"))
                .isEqualTo(1);
        assertThat(runCount(fixture.tenant())).isEqualTo(1);
        assertThat(auditRows("quota.exceeded").stream()
                        .filter(row -> fixture.tenant().equals(row.tenantId()))
                        .toList())
                .hasSize(1);
    }
}
