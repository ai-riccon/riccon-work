package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalAuditPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code workflow.approval.decided} 审计白名单（ADR-0024 §8.1/§8.3、DEC-0050=B）。
 *
 * <p>验证 ID/枚举必填、未知字段与非字符串 fail-closed；审计只允许 {@code commentSha256}，
 * 明文 {@code decisionComment} 必须被拒绝。该策略只用于 Worker 侧决定状态机；API 侧读取白名单
 * 另兼容历史明文行，不由本策略放宽。
 */
class WorkflowApprovalAuditPolicyTest {

    /** 合法 details 通过，理由哈希允许 64 位。 */
    @Test
    @DisplayName("IT-WF-108 决定审计接受 ADR 字段与 commentSha256")
    void validateDecided_acceptsAdrFields() {
        Map<String, Object> details = validDetails();
        details.put("commentSha256", "a".repeat(64));

        assertThatCode(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .doesNotThrowAnyException();
    }

    /** 允许工具名与理由哈希为空。 */
    @Test
    @DisplayName("IT-WF-108 决定审计允许可选字段为空")
    void validateDecided_allowsNullableFields() {
        Map<String, Object> details = validDetails();
        details.put("targetToolName", null);
        details.put("commentSha256", null);

        assertThatCode(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .doesNotThrowAnyException();
    }

    /** 未知字段被拒绝。 */
    @Test
    @DisplayName("SEC-WF-001 决定审计拒绝未登记字段")
    void validateDecided_rejectsUnknownKey() {
        Map<String, Object> details = validDetails();
        details.put("prompt", "不应进入审计");

        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 明文理由不得进入新审计。 */
    @Test
    @DisplayName("SEC-WF-001 决定审计拒绝明文 decisionComment（DEC-0050=B）")
    void validateDecided_rejectsPlaintextDecisionComment() {
        Map<String, Object> details = validDetails();
        details.put("decisionComment", "不合规");

        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisionComment");
    }

    /** 超长/空哈希被拒绝。 */
    @Test
    @DisplayName("IT-WF-108 决定审计拒绝非法 commentSha256")
    void validateDecided_rejectsInvalidCommentHash() {
        Map<String, Object> tooLong = validDetails();
        tooLong.put("commentSha256", "a".repeat(65));
        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(tooLong))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> blank = validDetails();
        blank.put("commentSha256", " ");
        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(blank))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 缺少必填 ID 被拒绝。 */
    @Test
    @DisplayName("IT-WF-108 决定审计要求运行与审批 ID")
    void validateDecided_requiresIdentifiers() {
        Map<String, Object> details = validDetails();
        details.remove("workflowApprovalId");

        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 非法决定枚举被拒绝。 */
    @Test
    @DisplayName("SEC-WF-001 决定审计拒绝非法决定枚举")
    void validateDecided_rejectsInvalidDecision() {
        Map<String, Object> details = validDetails();
        details.put("decision", "MAYBE");

        assertThatThrownBy(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造合法 details。
     *
     * @return details
     */
    private static Map<String, Object> validDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", UUID.randomUUID().toString());
        details.put("workflowApprovalId", UUID.randomUUID().toString());
        details.put("nodeId", "approval");
        details.put("decision", WorkflowApprovalDecision.APPROVE.name());
        details.put("approverUserId", UUID.randomUUID().toString());
        details.put("targetToolName", "market_data");
        return details;
    }
}
