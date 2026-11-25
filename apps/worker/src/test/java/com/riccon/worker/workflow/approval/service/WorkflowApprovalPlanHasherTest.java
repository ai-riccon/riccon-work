package com.riccon.worker.workflow.approval.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.worker.workflow.approval.domain.WorkflowApprovalPlanInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 计划绑定哈希的稳定性与漂移敏感度（ADR-0024 §4.7）。
 *
 * <p>对应 IT-WF-108 的“审批绑定可核对”子项：同一版本/配置/上游摘要必须得到同一哈希；版本、
 * 配置或上游摘要变化必须改变哈希，供 T07b 在执行前重算并拒绝 {@code WF_PLAN_DRIFT}。
 */
class WorkflowApprovalPlanHasherTest {

    /** 被测哈希器。 */
    private final WorkflowApprovalPlanHasher hasher = new WorkflowApprovalPlanHasher(new ObjectMapper());

    /** 同一语义的键/用户顺序变化不影响哈希。 */
    @Test
    @DisplayName("IT-WF-108 计划哈希对键与用户顺序稳定")
    void hash_isStableAcrossOrdering() {
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        Map<String, String> first = new LinkedHashMap<>();
        first.put("draft", "草稿摘要");
        first.put("retrieval", "命中 2 条");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("retrieval", "命中 2 条");
        second.put("draft", "草稿摘要");

        String firstHash = hasher.hash(plan(workflowId, versionId, List.of(userB, userA), first));
        String secondHash = hasher.hash(plan(workflowId, versionId, List.of(userA, userB), second));

        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash).matches("^[0-9a-f]{64}$");
    }

    /** 版本变化导致哈希变化（其他参与字段保持不变）。 */
    @Test
    @DisplayName("IT-WF-108 版本漂移改变计划哈希")
    void hash_changesWhenVersionChanges() {
        UUID workflowId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String first = hasher.hash(plan(workflowId, UUID.randomUUID(), List.of(user), Map.of()));
        String second = hasher.hash(plan(workflowId, UUID.randomUUID(), List.of(user), Map.of()));

        assertThat(first).isNotEqualTo(second);
    }

    /** 上游输出摘要变化导致哈希变化。 */
    @Test
    @DisplayName("IT-WF-108 上游摘要漂移改变计划哈希")
    void hash_changesWhenUpstreamSummaryChanges() {
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String first = hasher.hash(plan(workflowId, versionId, List.of(), Map.of("retrieval", "命中 2 条")));
        String second = hasher.hash(plan(workflowId, versionId, List.of(), Map.of("retrieval", "命中 3 条")));

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * 构造计划输入。
     *
     * @param workflowId 流程 ID
     * @param versionId 版本 ID
     * @param users 用户审批人
     * @param upstream 上游摘要
     * @return 计划输入
     */
    private static WorkflowApprovalPlanInput plan(
            UUID workflowId, UUID versionId, List<UUID> users, Map<String, String> upstream) {
        return new WorkflowApprovalPlanInput(
                workflowId,
                versionId,
                "approval",
                WorkflowApprovalActionKind.MCP_TOOL,
                WorkflowApprovalAssigneeType.USER,
                null,
                users,
                "tool",
                UUID.fromString("01990000-0000-7000-8000-000000000901"),
                "market_data",
                upstream);
    }
}
