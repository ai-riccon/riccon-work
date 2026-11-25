package com.riccon.api.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.audit.service.AuditDetailsInvalidException;
import com.riccon.api.audit.service.AuditDetailsReader;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批理由审计长度契约（关闭 REV-TASK-0219-001 F-001 / REV-TASK-0226-001 F-002）。
 *
 * <p>API 写入与读取共用 {@link AuditDetailsPolicy}；{@code workflow.approval.decided.decisionComment}
 * 必须支持 201–500 字符，与契约、V15 列宽和 Worker T07a 的本地策略一致，不能出现“写得进、读不出”。
 * 长度按 Unicode 码点计算，避免代理对字符被误判。
 */
class WorkflowApprovalAuditCommentPolicyTest {

    /** 独立 JSON 编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("F-001 审批理由 201 与 500 字符通过写入策略")
    void validate_decidedComment201And500_allowed() {
        assertThatCode(() ->
                        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details("批".repeat(201))))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details("批".repeat(500))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F-001 审批理由 501 字符被写入策略拒绝")
    void validate_decidedComment501_rejected() {
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details("批".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisionComment");
    }

    @Test
    @DisplayName("F-001 500 个代理对字符按码点计仍可通过")
    void validate_decidedComment500CodePoints_allowed() {
        assertThatCode(() ->
                        AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details("😀".repeat(500))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F-001 500 字符理由可写入并可被读取端读回，501 读取 fail-closed")
    void read_roundTrip500_andRejects501() throws Exception {
        Map<String, Object> allowed = details("批".repeat(500));
        String json = JSON.writeValueAsString(allowed);
        Map<String, Object> readBack = AuditDetailsReader.read(JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, json);
        assertThat((String) readBack.get("decisionComment")).hasSize(500);

        Map<String, Object> tooLong = details("批".repeat(501));
        String tooLongJson = JSON.writeValueAsString(tooLong);
        assertThatThrownBy(() -> AuditDetailsReader.read(JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, tooLongJson))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }

    /**
     * 构造一条完整的审批决定 details。
     *
     * @param comment 理由
     * @return details
     */
    private static Map<String, Object> details(String comment) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", UUID.randomUUID().toString());
        details.put("workflowApprovalId", UUID.randomUUID().toString());
        details.put("nodeId", "approval");
        details.put("decision", "REJECT");
        details.put("approverUserId", UUID.randomUUID().toString());
        details.put("decisionComment", comment);
        return details;
    }
}
