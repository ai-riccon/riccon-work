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
 * {@code workflow.approval.decided} 审计白名单（ADR-0024 §8.1/§8.3）。
 *
 * <p>验证 ID/枚举必填、未知字段与非字符串 fail-closed，以及 1–500 字符拒绝理由在审计中的
 * 临时口径（U-05）。该策略只用于 Worker 侧决定状态机；T09 的 API 侧白名单保持独立。
 */
class WorkflowApprovalAuditPolicyTest {

    /** 合法 details 通过，理由允许 500 字符（按码点计数，emoji 不按 UTF-16 单元误判）。 */
    @Test
    @DisplayName("IT-WF-108 决定审计接受 ADR 字段与 500 字符理由")
    void validateDecided_acceptsAdrFields() {
        Map<String, Object> details = validDetails();
        details.put("decisionComment", "😀".repeat(500));

        assertThatCode(() -> WorkflowApprovalAuditPolicy.validateDecided(details))
                .doesNotThrowAnyException();
    }

    /** 允许工具名与理由为空。 */
    @Test
    @DisplayName("IT-WF-108 决定审计允许可选字段为空")
    void validateDecided_allowsNullableFields() {
        Map<String, Object> details = validDetails();
        details.put("targetToolName", null);
        details.put("decisionComment", null);

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

    /** 超过 500 字符的理由被拒绝。 */
    @Test
    @DisplayName("IT-WF-108 决定审计拒绝超长理由")
    void validateDecided_rejectsTooLongComment() {
        Map<String, Object> details = validDetails();
        details.put("decisionComment", "理".repeat(501));

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
