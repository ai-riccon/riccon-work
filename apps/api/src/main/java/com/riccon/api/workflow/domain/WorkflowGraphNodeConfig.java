package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 节点配置（契约 {@code WorkflowNodeConfig}）。
 *
 * <p>按节点类型只使用其中一部分字段；保存阶段只校验字段形态与上限，允许未完成草稿。必填组合、
 * 工具/模型可用性、审批可达性、条件类型匹配等由校验器与发布流程拒绝（ADR-0024 第 4.4 节）。
 *
 * <p>安全边界：本对象只承载封闭字段，不接受 {@code script}/{@code code}/{@code http}/{@code cron}/
 * {@code parallel}/{@code loop}/{@code subflow} 等任意执行配置；未知字段由
 * {@link WorkflowGraphJson} 在反序列化时拒绝（TM-72）。
 *
 * @param promptTemplate 模型提示词模板，最多 8000 字符
 * @param queryTemplate 检索查询模板，最多 2000 字符
 * @param topK 检索命中数 1–20
 * @param modelId 模型 ID；为空时走 REPORT 路由
 * @param outputKind 模型输出形态
 * @param labels 分类标签，最多 10 个
 * @param toolId MCP 工具 ID
 * @param argsTemplate 工具参数模板，最多 16 个顶层键
 * @param assigneeType 审批人解析方式
 * @param assigneeRole 角色审批码
 * @param assigneeUserIds 用户审批人 ID，最多 50 个
 * @param actionKind 审批解锁的动作类型
 * @param targetNodeKey 审批解锁的下游节点 key
 * @param condition 条件节点配置
 * @param resultFrom 结束节点的结果来源节点 ID
 * @param resultTitle 结果标题，最多 200 字符
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphNodeConfig(
        String promptTemplate,
        String queryTemplate,
        Integer topK,
        UUID modelId,
        WorkflowGraphOutputKind outputKind,
        List<String> labels,
        UUID toolId,
        Map<String, Object> argsTemplate,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        List<UUID> assigneeUserIds,
        WorkflowApprovalActionKind actionKind,
        String targetNodeKey,
        WorkflowGraphCondition condition,
        String resultFrom,
        String resultTitle) {

    /** 提示词模板最大长度，与契约一致。 */
    public static final int MAX_PROMPT_TEMPLATE_LENGTH = 8000;

    /** 查询模板最大长度，与契约一致。 */
    public static final int MAX_QUERY_TEMPLATE_LENGTH = 2000;

    /** 检索 topK 下限。 */
    public static final int MIN_TOP_K = 1;

    /** 检索 topK 上限。 */
    public static final int MAX_TOP_K = 20;

    /** 分类标签最大数量。 */
    public static final int MAX_LABELS = 10;

    /** 单个分类标签最大长度。 */
    public static final int MAX_LABEL_LENGTH = 64;

    /** 工具参数模板最大顶层键数。 */
    public static final int MAX_ARGS_TEMPLATE_KEYS = 16;

    /** 用户审批人最大数量。 */
    public static final int MAX_ASSIGNEE_USER_IDS = 50;

    /** 审批节点允许的角色码，与契约枚举一致。 */
    public static final Set<String> ASSIGNEE_ROLES =
            Set.of(RoleCodes.TENANT_ADMIN, RoleCodes.WORKFLOW_EDITOR, RoleCodes.MEMBER);

    /** 校验长度、范围与集合副本。 */
    public WorkflowGraphNodeConfig {
        requireMaxLength(promptTemplate, "promptTemplate", MAX_PROMPT_TEMPLATE_LENGTH);
        requireMaxLength(queryTemplate, "queryTemplate", MAX_QUERY_TEMPLATE_LENGTH);
        if (topK != null && (topK < MIN_TOP_K || topK > MAX_TOP_K)) {
            throw new IllegalArgumentException("topK must be between " + MIN_TOP_K + " and " + MAX_TOP_K);
        }
        if (labels != null) {
            if (labels.size() > MAX_LABELS) {
                throw new IllegalArgumentException("labels must not exceed " + MAX_LABELS + " items");
            }
            for (String label : labels) {
                requireMaxLength(label, "label", MAX_LABEL_LENGTH);
                if (label == null || label.isBlank()) {
                    throw new IllegalArgumentException("label must not be blank");
                }
            }
            labels = List.copyOf(labels);
        }
        if (argsTemplate != null) {
            if (argsTemplate.size() > MAX_ARGS_TEMPLATE_KEYS) {
                throw new IllegalArgumentException("argsTemplate must not exceed " + MAX_ARGS_TEMPLATE_KEYS + " keys");
            }
            // 允许 JSON null 值，因此不能用 Map.copyOf（它会拒绝 null 值）。
            argsTemplate = Collections.unmodifiableMap(new LinkedHashMap<>(argsTemplate));
        }
        if (assigneeRole != null && !ASSIGNEE_ROLES.contains(assigneeRole)) {
            throw new IllegalArgumentException("assigneeRole is not a supported role code");
        }
        if (assigneeUserIds != null) {
            if (assigneeUserIds.size() > MAX_ASSIGNEE_USER_IDS) {
                throw new IllegalArgumentException(
                        "assigneeUserIds must not exceed " + MAX_ASSIGNEE_USER_IDS + " items");
            }
            assigneeUserIds = List.copyOf(assigneeUserIds);
        }
        if (targetNodeKey != null) {
            if (targetNodeKey.isBlank() || targetNodeKey.length() > 64) {
                throw new IllegalArgumentException("targetNodeKey must be 1-64 characters");
            }
        }
        requireMaxLength(resultFrom, "resultFrom", 64);
        requireMaxLength(resultTitle, "resultTitle", 200);
    }

    /**
     * 校验可空文本的最大长度。
     *
     * @param value 值
     * @param field 字段名
     * @param maxLength 最大长度
     */
    private static void requireMaxLength(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
    }
}
