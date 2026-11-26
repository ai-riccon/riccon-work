package com.riccon.worker.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.workflow.approval.service.WorkflowApprovalCommentHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审批理由审计哈希（DEC-0050=B）。
 *
 * <p>验证 SHA-256/UTF-8 规则稳定、首尾空白不影响结果、不同理由得到不同哈希；API 与 Worker
 * 各自持有同一实现，固定向量由两侧测试共同锁定，避免跨部署物漂移。
 */
class WorkflowApprovalCommentHashTest {

    /** 固定向量：UTF-8("同意") 的 SHA-256。 */
    private static final String APPROVE_HASH = "905819e2e3a059a02087855e9f326d05c877c1767ec31dc012c5c44652840230";

    @Test
    @DisplayName("U-05 审批理由哈希使用固定 SHA-256/UTF-8 向量")
    void sha256_knownVector_isStable() {
        assertThat(WorkflowApprovalCommentHash.sha256("同意")).isEqualTo(APPROVE_HASH);
    }

    @Test
    @DisplayName("U-05 首尾空白不影响审计哈希")
    void sha256_stripsSurroundingWhitespace() {
        assertThat(WorkflowApprovalCommentHash.sha256("  同意\n")).isEqualTo(APPROVE_HASH);
        assertThat(WorkflowApprovalCommentHash.sha256("\t同意  ")).isEqualTo(APPROVE_HASH);
    }

    @Test
    @DisplayName("U-05 不同理由与内部空白产生不同哈希")
    void sha256_differsForDifferentReasons() {
        assertThat(WorkflowApprovalCommentHash.sha256("同意")).isNotEqualTo(WorkflowApprovalCommentHash.sha256("不同意"));
        assertThat(WorkflowApprovalCommentHash.sha256("同意 执行"))
                .isNotEqualTo(WorkflowApprovalCommentHash.sha256("同意执行"));
    }

    @Test
    @DisplayName("U-05 空理由没有哈希，不写审计键")
    void sha256_nullOrBlank_returnsNull() {
        assertThat(WorkflowApprovalCommentHash.sha256(null)).isNull();
        assertThat(WorkflowApprovalCommentHash.sha256("")).isNull();
        assertThat(WorkflowApprovalCommentHash.sha256("   ")).isNull();
    }

    @Test
    @DisplayName("U-05 哈希固定为 64 位小写十六进制")
    void sha256_isLowercaseHex64() {
        assertThat(WorkflowApprovalCommentHash.sha256("批".repeat(500)))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
