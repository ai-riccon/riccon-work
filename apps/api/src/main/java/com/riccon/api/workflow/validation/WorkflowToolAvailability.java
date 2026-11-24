package com.riccon.api.workflow.validation;

import com.riccon.platform.domain.mcp.McpToolRisk;
import java.util.Objects;

/**
 * MCP 工具对当前租户的可用状态。
 *
 * <p>目录状态与租户授权分开表达：工具全局停用返回 {@link #toolDisabled()}，未授权或 server 停用返回
 * {@link #notAuthorized()}，只有工具、server 与租户授权同时可用才返回 {@link #available}。风险级别与
 * {@code requiresApproval} 用于要求工具节点前必须存在直接审批前驱（ADR-0024 §4.7，AC-18）。
 *
 * @param available 工具是否已授权且可用
 * @param disabled 工具或 server 是否被停用
 * @param riskLevel 工具风险级别；不可用时为空
 * @param requiresApproval 是否要求每次调用前置审批
 */
public record WorkflowToolAvailability(
        boolean available, boolean disabled, McpToolRisk riskLevel, boolean requiresApproval) {

    /** 校验状态一致性。 */
    public WorkflowToolAvailability {
        if (available && disabled) {
            throw new IllegalArgumentException("available tool must not be disabled");
        }
        if (available) {
            Objects.requireNonNull(riskLevel, "riskLevel must not be null when available");
        }
    }

    /**
     * 构造已授权且可用状态。
     *
     * @param riskLevel 风险级别
     * @param requiresApproval 是否要求审批
     * @return 可用状态
     */
    public static WorkflowToolAvailability available(McpToolRisk riskLevel, boolean requiresApproval) {
        return new WorkflowToolAvailability(true, false, riskLevel, requiresApproval);
    }

    /**
     * 构造工具或 server 停用状态。
     *
     * @return 停用状态
     */
    public static WorkflowToolAvailability toolDisabled() {
        return new WorkflowToolAvailability(false, true, null, false);
    }

    /**
     * 构造未授权状态；不区分“工具不存在”与“未授权”，避免枚举平台目录。
     *
     * @return 未授权状态
     */
    public static WorkflowToolAvailability notAuthorized() {
        return new WorkflowToolAvailability(false, false, null, false);
    }

    /**
     * 判断工具是否必须经过审批。
     *
     * @return 高风险或显式要求审批时返回 {@code true}
     */
    public boolean requiresApprovalBeforeInvocation() {
        return requiresApproval || riskLevel == McpToolRisk.WRITE;
    }
}
