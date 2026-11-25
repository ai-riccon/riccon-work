package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 节点配置（契约 {@code WorkflowNodeConfig} 的 Worker 只读副本）。
 *
 * <p>保存/发布阶段已由 API 校验字段形态；运行入口只解析执行需要的字段，并保留工具/审批节点配置
 * 供 T07a/T07b 使用。未知字段在 {@link WorkflowGraphJson} 解析时拒绝（TM-72）。
 *
 * <p>本对象不承载脚本、代码、HTTP、定时、并行、循环或子流程配置；这些能力在 M06 不存在。
 *
 * @param promptTemplate 模型提示词模板
 * @param queryTemplate 检索查询模板
 * @param topK 检索返回条数
 * @param modelId 模型 ID；为空时使用 REPORT 路由链首模型
 * @param outputKind 模型输出形态
 * @param labels 分类标签
 * @param toolId MCP 工具 ID（T07b 使用）
 * @param argsTemplate 工具参数模板（T07b 使用）
 * @param assigneeType 审批人解析方式（T07a 使用）
 * @param assigneeRole 角色审批码（T07a 使用）
 * @param assigneeUserIds 用户审批人（T07a 使用）
 * @param actionKind 审批解锁动作（T07a 使用）
 * @param targetNodeKey 审批解锁目标节点（T07a 使用）
 * @param condition 条件配置
 * @param resultFrom 结束节点结果来源
 * @param resultTitle 结果标题
 */
public record WorkflowGraphNodeConfig(
        String promptTemplate,
        String queryTemplate,
        Integer topK,
        UUID modelId,
        WorkflowNodeOutputKind outputKind,
        List<String> labels,
        UUID toolId,
        JsonNode argsTemplate,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        List<UUID> assigneeUserIds,
        WorkflowApprovalActionKind actionKind,
        String targetNodeKey,
        WorkflowGraphCondition condition,
        String resultFrom,
        String resultTitle) {

    /** 复制集合与 JSON，避免调用方修改发布快照的内存副本。 */
    public WorkflowGraphNodeConfig {
        labels = labels == null ? null : List.copyOf(labels);
        assigneeUserIds = assigneeUserIds == null ? null : List.copyOf(assigneeUserIds);
        Objects.requireNonNull(argsTemplate, "argsTemplate must not be null");
        argsTemplate = argsTemplate.deepCopy();
    }
}
