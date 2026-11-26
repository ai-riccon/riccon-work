package com.riccon.api.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.domain.AuditDetailsPolicy;
import com.riccon.api.audit.service.AuditDetailsInvalidException;
import com.riccon.api.audit.service.AuditDetailsReader;
import com.riccon.api.workflow.approval.service.WorkflowApprovalCommentHash;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批理由审计字段策略（DEC-0050=B）。
 *
 * <p>新写入只允许 {@code commentSha256}（64 位小写十六进制），不写明文 {@code decisionComment}；
 * 审批记录仍保留 ≤500 字符理由。读取端与写入端共用 {@link AuditDetailsPolicy}，因此历史
 * {@code decisionComment} 行（≤500）必须继续可读，501 字符仍 fail-closed。
 */
class WorkflowApprovalAuditCommentPolicyTest {

    /** 独立 JSON 编解码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("U-05 审批决定审计接受 commentSha256")
    void validate_decidedHash_allowed() {
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, hashDetails("同意")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("U-05 commentSha256 必须为 64 位，超长被拒绝")
    void validate_decidedHash_rejectsTooLong() {
        Map<String, Object> details = hashDetails("同意");
        details.put("commentSha256", "a".repeat(65));

        assertThatThrownBy(() -> AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commentSha256");
    }

    @Test
    @DisplayName("U-05 历史 decisionComment 201/500 字符仍通过读取策略")
    void validate_legacyDecisionComment201And500_allowed() {
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("批".repeat(201))))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("批".repeat(500))))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("😀".repeat(500))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("U-05 历史 decisionComment 501 字符仍 fail-closed")
    void validate_legacyDecisionComment501_rejected() {
        assertThatThrownBy(() -> AuditDetailsPolicy.validate(
                        AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("批".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisionComment");
    }

    @Test
    @DisplayName("U-05 写入校验拒绝历史明文键，读取校验仍兼容")
    void validateWrite_rejectsLegacyReadOnlyKey() {
        assertThatCode(() ->
                        AuditDetailsPolicy.validateWrite(AuditActions.WORKFLOW_APPROVAL_DECIDED, hashDetails("同意")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                        AuditDetailsPolicy.validateWrite(AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("历史理由")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only")
                .hasMessageContaining("decisionComment");
        // 读取端仍接受同一历史行，保证旧数据可查询/导出。
        assertThatCode(() -> AuditDetailsPolicy.validate(AuditActions.WORKFLOW_APPROVAL_DECIDED, legacyDetails("历史理由")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("U-05 新 hash 行可被审计读取端读回")
    void read_hash_roundTrip() throws Exception {
        Map<String, Object> allowed = hashDetails("同意");
        String json = JSON.writeValueAsString(allowed);
        Map<String, Object> readBack = AuditDetailsReader.read(JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, json);
        assertThat(readBack.get("commentSha256")).isEqualTo(WorkflowApprovalCommentHash.sha256("同意"));
        assertThat(readBack).doesNotContainKey("decisionComment");
    }

    @Test
    @DisplayName("U-05 历史 decisionComment 行可被审计读取端读回，501 仍拒绝")
    void read_legacyDecisionComment_roundTripAndReject501() throws Exception {
        Map<String, Object> allowed = legacyDetails("批".repeat(500));
        String json = JSON.writeValueAsString(allowed);
        Map<String, Object> readBack = AuditDetailsReader.read(JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, json);
        assertThat((String) readBack.get("decisionComment")).hasSize(500);

        Map<String, Object> tooLong = legacyDetails("批".repeat(501));
        String tooLongJson = JSON.writeValueAsString(tooLong);
        assertThatThrownBy(() -> AuditDetailsReader.read(JSON, AuditActions.WORKFLOW_APPROVAL_DECIDED, tooLongJson))
                .isInstanceOf(AuditDetailsInvalidException.class);
    }

    /**
     * 构造一条新口径（hash）的审批决定 details。
     *
     * @param comment 原始理由，仅用于计算哈希
     * @return details
     */
    private static Map<String, Object> hashDetails(String comment) {
        Map<String, Object> details = baseDetails();
        details.put("commentSha256", WorkflowApprovalCommentHash.sha256(comment));
        return details;
    }

    /**
     * 构造一条历史口径（明文 decisionComment）的审计 details。
     *
     * @param comment 历史理由
     * @return details
     */
    private static Map<String, Object> legacyDetails(String comment) {
        Map<String, Object> details = baseDetails();
        details.put("decisionComment", comment);
        return details;
    }

    /**
     * 构造不含理由字段的公共 details。
     *
     * @return details
     */
    private static Map<String, Object> baseDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", UUID.randomUUID().toString());
        details.put("workflowApprovalId", UUID.randomUUID().toString());
        details.put("nodeId", "approval");
        details.put("decision", "REJECT");
        details.put("approverUserId", UUID.randomUUID().toString());
        return details;
    }
}
